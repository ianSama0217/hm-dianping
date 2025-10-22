package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public Result queryById(Long id) {
        // 緩存穿透
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 使用互斥鎖解決緩存擊穿
        // Shop shop = queryWithMutex(id);

        // 使用邏輯過期解決緩存擊穿
        // Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店鋪不存在");
        }

        return Result.ok(shop);
    }

    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 從redis查詢店鋪緩存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判斷查詢到的是否是空字串
        if (shopJson != null) {
            return null;
        }

        // 緩存不存在, 根據id查詢mySQL
        Shop shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES);
            return null;
        }

        // mySQL存在, 寫入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 從redis查詢店鋪緩存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判斷查詢到的是否是空字串
        if (shopJson != null) {
            return null;
        }

        // 獲取互斥鎖
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = trylock(lockKey);

            // 判斷是否獲取成功
            if (!isLock) {
                // 失敗, 休眠並重試
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 成功, 查詢資料庫
            shop = getById(id);

            // 預防緩存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,
                        TimeUnit.MINUTES);
                return null;
            }

            // mySQL存在, 寫入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 釋放鎖
            unlock(lockKey);
        }

        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 從redis查詢店鋪緩存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // redis沒有緩存, 直接返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 命中, 先把json反序列化為對象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判斷是否過期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未過期, 直接返回店鋪資訊
            return shop;
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
                    // 重建緩存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 釋放鎖
                    unlock(lockKey);
                }
            });
        }

        // 失敗, 直接返回過期的店鋪資訊
        return shop;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查詢店鋪資料
        Shop shop = getById(id);

        // 封裝邏輯過期時間
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 寫入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店鋪id不能為空");
        }

        // 更新 mySQL 資料庫
        updateById(shop);

        // 刪除緩存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判斷是否需要查詢座標
        if (x == null || y == null) {
            // 不需要, 按照類型分頁查詢
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 計算分頁參數
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 查詢redis, 依照距離排序、分頁, result: shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        // 解析shopIds
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<GeoLocation<String>>> list = results.getContent();

        if (list.size() <= from) {
            // 沒有下一頁
            return Result.ok(Collections.emptyList());
        }

        // 截取 from ~ end 的資料
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result -> {
            // 獲取店鋪id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 取得距離
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 根據shopId查詢Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
