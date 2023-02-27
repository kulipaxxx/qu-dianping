package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        if(isFollow){
            //2.关注新增数据
            Follow follow = new Follow();
            //2.1 取出用户id
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
        }else {
            //3. 取关删除记录
            remove(new QueryWrapper<Follow>().eq("follow_user_id", followUserId).eq("user_id", userId));
        }

        return Result.ok("操作成功");
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
}
