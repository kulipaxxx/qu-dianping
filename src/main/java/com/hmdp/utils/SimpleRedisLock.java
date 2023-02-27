package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @Description: simpleRedisLock
 * @Author cheng
 * @Date: 2023/2/11 22:54
 * @Version 1.0
 */
//@Service
public class SimpleRedisLock implements ILock{

    //业务名称
    private String name;
    //前缀
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";//isSimple:trie 去掉下划线

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean isSuccess = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(isSuccess);
    }

    @Override
    public void unLock() {
        //调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT
                , Collections.singletonList(KEY_PREFIX + name)
                , ID_PREFIX + Thread.currentThread().getId()
                );
    }


    //@Override
    //public void unLock() {
    //    //获取线程标识
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    //获取redis中存的线程标识
    //    String redisValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //
    //    // 释放锁,判断是否一致
    //    if (threadId.equals(redisValue)) {
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}
