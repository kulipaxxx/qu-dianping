package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @Description: CacheClient
 * @Author cheng
 * @Date: 2023/2/8 19:04
 * @Version 1.0
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setLogic(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //转为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public <T,ID> T queryWithPassThrough(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //1.先读缓存(可以使用Redis AOP切入)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            return JSONUtil.toBean((JSONObject) redisData.getData(), type);
        }
        if (json != null) { // 判断是否为空值
            return null;
        }

        //4.不存在，根据id查询数据库
        T r = dbFallback.apply(id);

        //5.不存在用户，返回错误
        if (r == null) {
            //将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 存在，写入redis
        this.set(key, r, time, timeUnit);
        //7.返回
        return r;
    }

    //缓存穿透 逻辑过期解决，AP
    public <R, ID> R queryWithLogicExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //1.先读缓存(可以使用Redis AOP切入)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //6. 未过期，直接返回
            return r;
        }
        //7. 过期，需要缓存重建
        //7.1获取互斥锁

        String locoKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(locoKey);
        //7.2 判断是否获取锁成功
        if (isLock) {//7.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //重建缓存,写入redis
                    this.setLogic(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(locoKey);
                }
            });
        }else {//7.4 失败，返回过期商铺信息
            return null;
        }

        //8.返回
        return r;
    }

    private boolean tryLock(String key) {
        //设置有效期，防止程序出现问题，缓存一直存在，造成锁一直被其持有
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
