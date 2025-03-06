package com.hmdp.utils;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
     * 设置逻辑过期
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /*
     * 解决缓存穿透: 空值
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存数据存在, 直接返回数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 如果是空值, 说明数据不存在, 返回不存在
        if ("".equals(json)) {
            return null;
        }

        R result = dbFallback.apply(id);
        
        // 如果数据不存在, 写入空值, 返回不存在
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 如果数据存在, 写入数据, 返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result), time, unit);
        return result;
    }

    /*
     * 解决缓存击穿: 逻辑过期
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存不存在, 说明数据不存在, 返回不存在
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 如果缓存存在, 需要检查是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R result = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 如果未过期, 直接返回数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return result;
        }

        // 如果已过期, 需要缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean getLock = tryLock(lockKey);

        // 如果拿到锁, 开启独立线程, 实现缓存重建
        if (getLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newResult = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newResult, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 返回数据, 无论是否过期
        return result;
    }

    /*
     * 解决缓存击穿: 互斥锁
     * 解决缓存穿透: 空值
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存数据存在, 直接返回数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 如果是空值, 说明数据不存在, 返回不存在
        if ("".equals(json)) {
            return null;
        }

        // 如果缓存不存在, 需要只让拿到锁的一个线程进行缓存重建, 其余线程重试获取缓存
        String lockKey = LOCK_SHOP_KEY + id;
        R result = null;

        try {
            boolean getLock = tryLock(lockKey);

            // 如果没拿到锁, 重试获取缓存
            if (!getLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }

            // 如果拿到锁, 缓存重建
            result = dbFallback.apply(id);

            // 如果数据不存在, 写入空值, 返回不存在
            if (result == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 如果数据存在, 写入数据, 返回数据
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result), time, unit);
            return result;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
    }

    private boolean tryLock(String key) {
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "tryLock", LOCK_SHOP_TTL, TimeUnit.SECONDS));
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
