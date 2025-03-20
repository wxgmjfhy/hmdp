package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.event.VoucherOrderMessageEvent;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.util.BooleanUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hmdp.utils.RedisConstants.SECKILL_BEGINTIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ENDTIME_KEY;


@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /*
     * 检查秒杀时间
     */
    private boolean isSeckillTimeValid(Long voucherId) {
        String beginTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_BEGINTIME_KEY + voucherId);
        String endTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_ENDTIME_KEY + voucherId);

        // 如果是 null, 说明已经过期
        if (beginTimeStr == null || endTimeStr == null) {
            return false;
        }

        LocalDateTime beginTime = LocalDateTime.parse(beginTimeStr);
        LocalDateTime endTime = LocalDateTime.parse(endTimeStr);
        LocalDateTime now = LocalDateTime.now();

        return now.isAfter(beginTime) && now.isBefore(endTime);
    }

    /*
     * 检查秒杀资格, 有资格则会原子性地减少库存, 记录下单
     * 然后发布事件
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 检查秒杀时间
        if (BooleanUtil.isFalse(isSeckillTimeValid(voucherId))) {
            return Result.fail("不在秒杀时间内!");
        }

        Long userId = UserHolder.getUser().getId();

        // 检查秒杀资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足!" : "不允许重复下单!");
        }

        // 封装订单
        Long orderId = redisIdWorker.nextId("order:seckillVoucher");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);

        // 发布事件
        eventPublisher.publishEvent(new VoucherOrderMessageEvent(this, voucherOrder));

        return Result.ok(orderId);
    }

    /*
     * 创建订单相关数据库操作
     * 可能有减少库存和保存订单两个数据库操作, 采用事务管理
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 查询符合 userId和 voucherId 的订单数量, 保证一人一单
        Long count = query()
            .eq("user_id", voucherOrder.getUserId())
            .eq("voucher_id", voucherOrder.getVoucherId())
            .count();
        if (count > 0) {
            log.error("用户已购买过一次!");
            return;
        }

        // 乐观锁解决库存超卖: 检查 stock > 0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足!");
            return;
        }

        // 保存订单到数据库
        save(voucherOrder);
    }
}
