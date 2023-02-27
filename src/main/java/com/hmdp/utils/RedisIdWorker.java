package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.awt.font.TextHitInfo;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Description: RedisIdWorker
 * @Author cheng
 * @Date: 2023/2/9 17:44
 * @Version 1.0
 */
@Component
public class RedisIdWorker {


    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1672531200L;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    /**
     * 下一个id
     *
     * @param keyPrefix 关键前缀
     * @return long
     */
    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号

        //同一id，会有数量超出限制的可能，所以可以在之后拼接日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long aLong = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接返回
        return timeStamp << 32 | aLong;
    }


    //public static void main(String[] args) {
    //    LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0);
    //    long second = time.toEpochSecond(ZoneOffset.UTC);
    //    System.out.println("seconds:" + second);
    //}
}
