package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author Steven
 * @version 1.0
 * @description:
 * @date 2024/12/20 18:45
 */
@Component
@RequiredArgsConstructor
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryByIdWithPassThrough(String keyPrefix,
                                              ID id,
                                              Class<R> clazz,
                                              Function<ID, R> dbFallBack,
                                              Long expireTime,
                                              TimeUnit timeUnit) {
        // 查询缓存中是否存在
        String shopKey = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        R r = null;

        // 缓存中存在，直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            r = JSONUtil.toBean(shopJson, clazz);
            return r;
        }

        // 命中的是空值，即缓存中存在且是空值
        if(shopJson != null) {
            return null;
        }

        // 缓存中不存在，查询数据库，并更新缓存
        r = dbFallBack.apply(id);
        if(r == null) {
            // 更新缓存空值
            stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(shopKey, r, expireTime, timeUnit);

        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            10,
            10,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>()
    );

    public <R, ID> R queryByIdWithLogicalExpire(String keyPrefix,
                                                 String lockPrefix,
                                                 ID id,
                                                 Class<R> clazz,
                                                 Function<ID, R> dbFallBack,
                                                 Long expireTime,
                                                 TimeUnit timeUnit) {
        // 从缓存中取值
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);

        // 如果没有过期则返回值
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        // 过期则开新线程更新缓存
        String lockKey = lockPrefix + id;
        if(tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallBack.apply(id);
                    setWithLogicalExpire(key, r1, expireTime, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        // 返回旧值
        return r;
    }

    private boolean tryLock(String lockKey) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    public <R, ID> R queryByIdWithRedisson(String keyPrefix,
                                        String lockPrefix,
                                        ID id,
                                        Class<R> clazz,
                                        Function<ID, R> dbFallBack,
                                        Long expireTime,
                                        TimeUnit timeUnit) {
        // 查询缓存中是否存在
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        R r = null;

        // 缓存中存在，直接返回
        if(StrUtil.isNotBlank(json)) {
            r = JSONUtil.toBean(json, clazz);
            return r;
        }

        // 命中的是空值，即缓存中存在且是空值
        if(json != null) {
            return null;
        }

        // 缓存中不存在，查询数据库，并更新缓存，使用双重判定锁保证只有一个线程更新缓存（防止缓存击穿）
        String lockKey = lockPrefix + id;
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();

        try {
            // 双重判定锁
            json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(json)) {
                r = JSONUtil.toBean(json, clazz);
                return r;
            }
            r = dbFallBack.apply(id);
            if(r == null) {
                // 更新缓存空值
                set(key, "", expireTime, timeUnit);
                return null;
            }
            set(key, JSONUtil.toJsonStr(r), expireTime, timeUnit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

        return r;
    }

}
