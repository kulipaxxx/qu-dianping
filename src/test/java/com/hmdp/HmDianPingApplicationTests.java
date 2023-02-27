package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;

import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

@SpringBootTest
@RunWith(SpringRunner.class)
@Slf4j
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch downLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = :" + id);
            }
            downLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        downLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time=:" + (end - begin));
    }

    @Test
    void testShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void method1(){
        RLock lock = redissonClient.getLock("order");
        boolean isLock = lock.tryLock();
        if (!isLock){
            log.info("获取锁失败");
            return;
        }
        try {
            method2();
            log.info("method1 获取锁");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
            log.info("method1 释放锁");
        }
    }

    @Test
    void method2() throws InterruptedException {
        RLock lock = redissonClient.getLock("order");
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock){
            log.info("获取锁失败");
            return;
        }
        try {
            log.info("method2 获取锁");
        }finally {
            lock.unlock();
            log.info("method2 释放锁");
        }
    }

    @Test
    void test(){
        int[] ints = testMove(new int[]{0, 1, 0, 2, 3});
        for (int i : ints) {
            System.out.println(i);
        }
    }

    int[] testMove(int[] nums){
        int slow = 0;
        int fast = 0;
        boolean flag = true;
        while (fast < nums.length && flag){
            while (nums[fast] == 0){ // 找到不为0的位置
                fast++;
                if (nums.length == fast) {
                    flag = false;
                    break;
                }
            }
            if (!flag)
                break;
            if (nums[slow] == 0){
                int temp = nums[fast];
                nums[fast] = nums[slow];
                nums[slow] = temp;
                slow++;
            }
        }
        return nums;
    }
}
