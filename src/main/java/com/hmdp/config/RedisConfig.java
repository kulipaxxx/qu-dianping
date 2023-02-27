package com.hmdp.config;

import io.lettuce.core.RedisClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Description: RedisConfig
 * @Author cheng
 * @Date: 2023/2/17 21:39
 * @Version 1.0
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(){
        //创建配置
        Config config = new Config();

        //配置
        config.useSingleServer().setAddress("redis://47.109.91.229:6379").setPassword("123456");

        return Redisson.create(config);
    }

}

