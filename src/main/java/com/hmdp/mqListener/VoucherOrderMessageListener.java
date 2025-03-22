package com.hmdp.mqListener;

import java.io.IOException;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hmdp.entity.LocalMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ILocalMessageService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MessageConstants;
import com.rabbitmq.client.Channel;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 秒杀优惠券订单 队列监听器
 */
@Slf4j
@Component
public class VoucherOrderMessageListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ILocalMessageService localMessageService;

    @Resource
    private RedissonClient redissonClient;

    public static final String QUEUE_NAME = "hmdp.voucherOrder.queue";
    public static final String EXCHANGE_NAME = "hmdp.voucher.topic";
    public static final String ROUTINGKEY_NAME = "voucher.order";

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_NAME),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenVoucherOrderMessage(Long localMessageId, Channel channel, Message message) throws IOException {
        long deliverTag = message.getMessageProperties().getDeliveryTag();

        RLock lock = redissonClient.getLock("message:" + localMessageId);
        try {
            boolean success = lock.tryLock();
            if (!success) {
                log.info("已有消费者接收该消息 {}", localMessageId);
                channel.basicAck(deliverTag, false);
                return;
            }

            LocalMessage localMessage = localMessageService.getById(localMessageId);
            if (localMessage == null) {
                log.error("消息 {} 不存在", localMessageId);
                channel.basicAck(deliverTag, false);
                return;
            }
            if (localMessage.getStatus() != MessageConstants.UNPROCESSED) {
                log.error("消息 {} {}", localMessageId, (localMessage.getStatus() == MessageConstants.DEAD ? "已死亡" : "已处理"));
                channel.basicAck(deliverTag, false);
                return;
            }

            VoucherOrder voucherOrder = JSONUtil.toBean(localMessage.getMessageBody(), VoucherOrder.class);
            VoucherOrderMessageListener proxy = (VoucherOrderMessageListener) AopContext.currentProxy();
            proxy.handleVoucherOrder(voucherOrder, localMessageId);
            channel.basicAck(deliverTag, false);
        } catch (Exception e) {
            log.error("消息 {} 处理异常", localMessageId, e);
            channel.basicNack(deliverTag, false, true);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleVoucherOrder(VoucherOrder voucherOrder, Long localMessageId) {
        try {
            // 创建订单
            voucherOrderService.createVoucherOrder(voucherOrder);
            // 更新消息状态为已处理
            localMessageService.lambdaUpdate()
                .set(LocalMessage::getStatus, MessageConstants.PROCESSED)
                .eq(LocalMessage::getId, localMessageId)
                .update();
        } catch (Exception e) {
            log.error("处理消息 {} 时数据库操作失败", localMessageId, e);
            throw e;
        }
    }
    
}
