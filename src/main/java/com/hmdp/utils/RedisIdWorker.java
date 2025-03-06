package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/*
 * 全局唯一 id 生成器
 * 基于 redis 的原子自增操作, 在分布式系统下保证了 id 的唯一
 */
@Component
public class RedisIdWorker {

    /*
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /*
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        
        // 生成序列号, 先获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 当前日期对应 key 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 将 timestamp 和 count 拼接 (count 预留了 32 位, 即每天最多 2^32 个 id)
        return timestamp << COUNT_BITS | count;
    }
}
