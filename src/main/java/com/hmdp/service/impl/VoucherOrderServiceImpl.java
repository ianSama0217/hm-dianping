package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;
import java.util.Collections;

import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
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

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 獲取userId
        Long userId = UserHolder.getUser().getId();

        // 執行 lua 腳本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        // 判斷結果是否為0
        int r = result.intValue();
        if (r != 0) {
            // 不等於 0 ，代表沒有購買資格
            return Result.fail(r == 1 ? "庫存不足" : "不能重複下單");
        }

        // 為0，有購買資格
        long orderId = redisIdWorker.nextId("order");

        // TODO 保存阻塞隊列

        // 返回訂單id
        return Result.ok(orderId);
    }

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
    public Result createVoucherOrder(Long voucherId) {
        // 一人一單
        Long userId = UserHolder.getUser().getId();

        // 查詢訂單
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判斷是否存在
        if (count > 0) {
            // 已存在，不能重複購買
            return Result.fail("用戶已購買過一次");
        }

        // 下單
        // 檢查庫存是否充足，減庫存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                // 樂觀鎖 (CAS)
                .gt("stock", 0).update();

        if (!success) {
            return Result.fail("庫存不足");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);

        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }

}
