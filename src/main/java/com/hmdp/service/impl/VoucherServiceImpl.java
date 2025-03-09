package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;

import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_BEGINTIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ENDTIME_KEY;

import java.time.Duration;
import java.time.LocalDateTime;
// import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);

        // 保存秒杀优惠券到数据库中
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        
        // 保存秒杀优惠券到 redis 中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        stringRedisTemplate.opsForValue().set(SECKILL_BEGINTIME_KEY + voucher.getId(), voucher.getBeginTime().toString());
        stringRedisTemplate.opsForValue().set(SECKILL_ENDTIME_KEY + voucher.getId(), voucher.getEndTime().toString());

        // 计算有效时间
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = voucher.getEndTime();
        long expireTime = Duration.between(now, endTime).getSeconds();

        // 设置过期时间
        stringRedisTemplate.expire(SECKILL_STOCK_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
        stringRedisTemplate.expire(SECKILL_BEGINTIME_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
        stringRedisTemplate.expire(SECKILL_ENDTIME_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
    }
}
