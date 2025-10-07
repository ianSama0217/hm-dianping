package com.hmdp.service.impl;

import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 從消息Queue中獲取訂單訊息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 stream.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));

                    // 判斷是否有訊息
                    if (list == null || list.isEmpty()) {
                        // 無訊息，繼續下一次循環
                        continue;
                    }

                    // 有訊息，解析訊息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 創建訂單
                    handleVoucherOrder(voucherOrder);

                    // ACK確認
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("處理訂單異常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 從pending list中獲取訂單訊息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 stream.orders
                    // 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));

                    // 判斷是否有訊息
                    if (list == null || list.isEmpty()) {
                        // 無訊息，結束循環
                        break;
                    }

                    // 有訊息，解析訊息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 創建訂單
                    handleVoucherOrder(voucherOrder);

                    // ACK確認
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("處理pending list訂單異常", e);
                    handlePendingList();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException InterruptedException) {
                        InterruptedException.printStackTrace();
                    }
                }
            }
        }
    }

    /*
     * private BlockingQueue<VoucherOrder> orderTasks = new
     * ArrayBlockingQueue<>(1024 * 1024);
     * 
     * private class VoucherOrderHandler implements Runnable {
     * 
     * @Override
     * public void run() {
     * while (true) {
     * try {
     * // 從隊列中獲取訂單訊息
     * VoucherOrder voucherOrder = orderTasks.take();
     * // 創建訂單
     * handleVoucherOrder(voucherOrder);
     * } catch (Exception e) {
     * log.error("處理訂單異常", e);
     * }
     * }
     * }
     * }
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 獲取userid
        Long userId = voucherOrder.getUserId();

        // 獲取分布式鎖物件
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 獲取分布式鎖
        boolean isLock = lock.tryLock();

        if (!isLock) {
            // 獲取鎖失敗，返回錯誤或重試
            log.error("不允許重複下單");
            return;
        }

        try {
            // 返回訂單id
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 釋放鎖
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 獲取userId
        Long userId = UserHolder.getUser().getId();
        // 獲取orderId
        long orderId = redisIdWorker.nextId("order");

        // 執行 lua 腳本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));

        // 判斷結果是否為0
        int r = result.intValue();
        if (r != 0) {
            // 不等於 0 ，代表沒有購買資格
            return Result.fail(r == 1 ? "庫存不足" : "不能重複下單");
        }

        // 獲取代理對象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回訂單id
        return Result.ok(orderId);
    }

    // @Override
    // public Result seckillVoucher(Long voucherId) {
    // // 獲取userId
    // Long userId = UserHolder.getUser().getId();

    // // 執行 lua 腳本
    // Long result = stringRedisTemplate.execute(
    // SECKILL_SCRIPT,
    // Collections.emptyList(),
    // voucherId.toString(), userId.toString());

    // // 判斷結果是否為0
    // int r = result.intValue();
    // if (r != 0) {
    // // 不等於 0 ，代表沒有購買資格
    // return Result.fail(r == 1 ? "庫存不足" : "不能重複下單");
    // }

    // // 為0，有購買資格，將下單訊息保存到阻塞隊列
    // VoucherOrder voucherOrder = new VoucherOrder();
    // long orderId = redisIdWorker.nextId("order");
    // voucherOrder.setId(orderId);
    // voucherOrder.setUserId(userId);
    // voucherOrder.setVoucherId(voucherId);

    // // 將訂單訊息放入阻塞隊列
    // orderTasks.add(voucherOrder);

    // // 獲取代理對象
    // proxy = (IVoucherOrderService) AopContext.currentProxy();

    // // 返回訂單id
    // return Result.ok(orderId);
    // }

    // @Override
    // public Result seckillVoucher(Long voucherId) {

    // // 查詢優惠券
    // SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

    // // 判斷秒殺是否開始
    // if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    // return Result.fail("秒殺尚未開始");
    // }

    // // 判斷秒殺是否結束
    // if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    // return Result.fail("秒殺已經結束");
    // }

    // // 判斷庫存是否充足
    // if (voucher.getStock() < 1) {
    // return Result.fail("庫存不足");
    // }

    // Long userId = UserHolder.getUser().getId();

    // // 獲取分布式鎖物件
    // // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,
    // // stringRedisTemplate);
    // RLock lock = redissonClient.getLock("lock:order:" + userId);

    // // 獲取分布式鎖
    // boolean isLock = lock.tryLock();

    // if (!isLock) {
    // // 獲取鎖失敗，返回錯誤或重試
    // return Result.fail("不允許重複下單");
    // }

    // try {// 獲取代理對象
    // IVoucherOrderService proxy = (IVoucherOrderService)
    // AopContext.currentProxy();
    // // 返回訂單id
    // return proxy.createVoucherOrder(voucherId);
    // } finally {
    // // 釋放鎖
    // lock.unlock();
    // }

    // }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一單
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 查詢訂單
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判斷是否存在
        if (count > 0) {
            // 已存在，不能重複購買
            log.error("用戶已購買過一次");
            return;
        }

        // 下單
        // 檢查庫存是否充足，減庫存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                // 樂觀鎖 (CAS)
                .gt("stock", 0).update();

        if (!success) {
            log.error("庫存不足");
            return;
        }

        // 儲存訂單
        save(voucherOrder);
    }

}
