package com.hmdp.utils;

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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /* 設置邏輯過期 */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit seconds) {
        // 設置邏輯過期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds.toSeconds(time)));
        // 寫入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        // 從redis查詢店鋪緩存
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 判斷查詢到的是否是空字串
        if (json != null) {
            return null;
        }

        // 緩存不存在, 根據id查詢mySQL
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }

        // mySQL存在, 寫入redis
        this.set(key, r, time, unit);

        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 從redis查詢店鋪緩存
        String json = stringRedisTemplate.opsForValue().get(key);

        // redis沒有緩存, 直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 命中, 先把json反序列化為對象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判斷是否過期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未過期, 直接返回店鋪資訊
            return r;
        }

        // 已過期, 需要重建緩存
        // 獲取互斥鎖
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        boolean isLock = trylock(lockKey);

        // 判斷是否獲取成功
        if (isLock) {
            // 成功, 開啟獨立線程, 重建緩存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查詢資料庫
                    R r1 = dbFallback.apply(id);

                    // 重建緩存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 釋放鎖
                    unlock(lockKey);
                }
            });
        }

        // 失敗, 直接返回過期的店鋪資訊
        return r;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
