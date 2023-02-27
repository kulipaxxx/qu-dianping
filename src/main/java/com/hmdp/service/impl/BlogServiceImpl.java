package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource //byName default
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryById(Long id) {
        //1. 查询Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //2. 查询blog相关对象
        queryBlogUser(blog);
        //3. 查询当前用户是否点赞该博文
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        //2.判断当前用户是否点赞
        String key = "blog:liked" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        //2.判断当前用户是否点赞
        String key = "blog:liked" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        //3. 如果未点赞
        if (score == null) {
            //3.1 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存当前用户进redis SET集合
            if (isSuccess)
                stringRedisTemplate.opsForZSet().add(key, user.getId().toString(), System.currentTimeMillis());
        } else {//4. 如果已经点赞
            //4.1 数据库点赞数量 -1
            update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 在redis中移除该用户
            stringRedisTemplate.opsForZSet().remove(key, user.getId().toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.得到key
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //2.查询top5的点赞用户
        Set<String> users = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //判空
        if(users == null || users.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3.解析得到top5用户
        List<Long> top5 = users.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", top5);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", top5).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){ //失败
            return Result.fail("新增博文失败");
        }
        //查询所有粉丝 select * from tb_follow where follow_user_id = ?;
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记给粉丝
        for (Follow follow : follows) {
            //1. 获取粉丝id
            Long userId = follow.getId();
            //2. zset 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
