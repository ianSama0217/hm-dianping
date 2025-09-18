package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIdWorker {

    /*
     * 開始時間戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /*
     * 序列號位數
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 生成時間戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 生成序列號
        // 1.獲取當前日期, 精確到天
        String date = now.toLocalDate().toString();
        // 2.自增
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 組合並返回
        return timestamp << COUNT_BITS | count;
    }
}
