package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import jakarta.annotation.Resource;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;
import java.util.Collections;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hmdp.utils.RedisConstants.SECKILL_BEGINTIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ENDTIME_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

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

    public boolean isSeckillTimeValid(Long voucherId) {
        String beginTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_BEGINTIME_KEY + voucherId);
        String endTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_ENDTIME_KEY + voucherId);

        if (beginTimeStr == null || endTimeStr == null) {
            return false;
        }

        LocalDateTime beginTime = LocalDateTime.parse(beginTimeStr);
        LocalDateTime endTime = LocalDateTime.parse(endTimeStr);
        LocalDateTime now = LocalDateTime.now();

        return now.isAfter(beginTime) && now.isBefore(endTime);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (!isSeckillTimeValid(voucherId)) {
            return Result.fail("不在秒杀时间内!");
        }

        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足!" : "不允许重复下单!");
        }

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean getLock = lock.tryLock();
        if (!getLock) {
            return Result.fail("不允许重复下单!");
        }

        try {
            // 事务管理方法, 必须使用代理对象调用
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }        
    }

    /*
     * 可能有减少库存和存储秒杀优惠卷两个数据库操作, 采用事务管理
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已购买过一次!");
        }

        // 乐观锁解决库存超卖: 检查 stock > 0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足!");
        }

        VoucherOrder voucherOrder = new VoucherOrder();

        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(orderId);
    }
}
