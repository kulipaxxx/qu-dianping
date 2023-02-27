package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    /**
     * 遵循
     *
     * @param followUserId 遵循用户id
     * @param isFollow     是遵循
     * @return {@link Result}
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.判断是关注还是取关
        Long userId = UserHolder.getUser().getId();
        if(userId == null)
            return Result.ok("未登录");
        String key = "follows:" + userId;
        if(isFollow){
            //2.关注新增数据
            Follow follow = new Follow();
            //2.1 取出用户id
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            //3. 取关删除记录
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        if(userId == null)
            return Result.ok("未登录");
        // 2. 查询是否关注 select * from tb_follow where userId = ? and followUserId = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * followcommons
     *
     * @param id id
     * @return {@link Result}
     */
    @Override
    public Result followcommons(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //拼接两个用户的key
        // == 是运算符，比较的是基本数据类型的话，就是比较值，比较引用数据类型
        // 比较的是地址。
        // equals object方法， 比较的是引用类型的内存地址，如果重写了的话一般是比较值，比如String类
        // 重写euals 必须重写hashCode
        String key1 = "follows" + userId;
        String key2 = "follows" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //如果没有交集
        if (intersect == null || intersect.size() == 0){
            return Result.fail("没有共同关注");
        }
        //有共同关注
        //解析交集
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> users = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
