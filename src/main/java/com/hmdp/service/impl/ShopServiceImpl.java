package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,
        //        id, Shop.class, this::getById, 2L, TimeUnit.SECONDS);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //if (shop==null) {
        //    return Result.fail("店铺不存在");
        //}

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicExpire(id);
        //if (shop == null) {
        //    return Result.fail("店铺不存在");
        //}
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY,
                id, Shop.class, this::getById, 2L, TimeUnit.MINUTES);
        //7.返回
        System.out.println("================");
        return Result.ok(shop);
    }

    //缓存穿透 逻辑过期解决，AP
    public Shop queryWithLogicExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.先读缓存(可以使用Redis AOP切入)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //6. 未过期，直接返回
            return shop;
        }
        //7. 过期，需要缓存重建
        //7.1获取互斥锁
        String locoKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(locoKey);
        //7.2 判断是否获取锁成功
        if (isLock) {//7.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {

                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
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
        return shop;
    }


    //缓存击穿 互斥锁解决，CP
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.先读缓存(可以使用Redis AOP切入)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) { // 判断是否为空值
            return null;
        }

        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = null;
        Shop shop = null;
        try {
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 失败，休眠之后再重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //模拟重建延时
            Thread.sleep(200);

            //4.4成功获取互斥锁，根据id查询数据库
            shop = getById(id);

            //5.不存在店铺，返回错误
            if (shop == null) {
                return null;
            }
            //6. 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }


        //7.返回
        return shop;
    }

    private boolean tryLock(String key) {
        //设置有效期，防止程序出现问题，缓存一直存在，造成锁一直被其持有
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.先读缓存(可以使用Redis AOP切入)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) { // 判断是否为空值
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = getById(id);

        //5.不存在用户，返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);

        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional // 事务控制原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null)
            return Result.fail("店铺不存在");

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
