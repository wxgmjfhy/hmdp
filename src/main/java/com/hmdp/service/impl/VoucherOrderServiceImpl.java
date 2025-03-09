package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hmdp.utils.RedisConstants.SECKILL_BEGINTIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ENDTIME_KEY;


/*
 * 在 lua 脚本中, 就检查了库存和一人一单, 
 * 保证了一个用户对一个秒杀券的订单只会在库存充足的情况下创建, 并且只发送给消息队列一次
 * 
 * 所以, 在进行创建订单相关数据库操作前使用 Redisson 分布式锁保证一人一单, 
 * 以及创建订单时进行数据库查询保证一人一单和使用乐观锁保证库存的这几个操作可能不太必要, 只是兜底操作
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

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

    /*
     * 独立线程, 监听消息队列, 异步完成创建订单相关数据库操作
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*
     * 基于 redis stream 的消息队列
     * 非异常情况下, 一直处理最新的消息
     * 异常情况下, 从最早的消息开始处理, 确保处理到已消费但未确认的消息
     */
    private class VoucherOrderHandler implements Runnable {
        private final String QUEUE_NAME = "stream.orders";

        public void initStream() {
            Boolean exists = stringRedisTemplate.hasKey(QUEUE_NAME);
            if (BooleanUtil.isFalse(exists)) {
                log.info("stream 不存在, 开始创建 stream");
                stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.latest(), "g1");
                log.info("stream 和 group 创建完毕");
                return;
            }

            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(QUEUE_NAME);
            if (groups.isEmpty()) {
                log.info("group 不存在, 开始创建 group");
                stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.latest(), "g1");
                log.info("group 创建完毕");
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    // 初始化 stream
                    initStream();

                    // 获取消息队列中的订单信息
                    @SuppressWarnings("unchecked")
                    List<MapRecord<String, Object, Object> > list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );

                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    
                    // 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());                    
                } catch (Exception e) {
                    log.error("创建订单异常", e);
                    handlePendingList();
                }
            }
        }

        public void handlePendingList() {
            while (true) {
                try {
                    @SuppressWarnings("unchecked")
                    List<MapRecord<String, Object, Object> > list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                    );

                    if (list == null || list.isEmpty()) {
                        break;
                    }

                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    
                    // 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());       
                } catch (Exception e) {
                    log.error("创建订单异常", e);
                }
            }
        }
    }

    /*
     * 创建订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        
        RLock lock = redissonClient.getLock("lock:order:" + voucherId + ":" + userId);
        boolean getLock = lock.tryLock();
        if (!getLock) {
            log.error("不允许重复下单!");
            return;
        }

        try {
            // 事务管理方法, 必须使用代理对象调用
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

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
     * 检查秒杀资格
     * 有资格则会减少库存, 记录下单, 并发送消息到消息队列 (lua 脚本中)
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 检查秒杀时间
        if (!isSeckillTimeValid(voucherId)) {
            return Result.fail("不在秒杀时间内!");
        }

        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        // 检查秒杀资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足!" : "不允许重复下单!");
        }

        return Result.ok(orderId);
    }

    /*
     * 创建订单相关数据库操作
     * 可能有减少库存和保存订单两个数据库操作, 采用事务管理
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 查询符合 userId和 voucherId 的订单数量, 保证一人一单
        Long count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已购买过一次!");
            return;
        }

        // 乐观锁解决库存超卖: 检查 stock > 0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足!");
            return;
        }

        // 保存订单到数据库
        save(voucherOrder);
    }
}
