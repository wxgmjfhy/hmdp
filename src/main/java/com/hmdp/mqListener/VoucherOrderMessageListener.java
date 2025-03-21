package com.hmdp.mqListener;

import java.io.IOException;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hmdp.entity.LocalMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ILocalMessageService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MessageConstants;
import com.rabbitmq.client.Channel;

import cn.hutool.core.util.BooleanUtil;
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

    public static final String QUEUE_NAME = "hmdp.seckill.voucherOrder.queue";
    public static final String EXCHANGE_NAME = "hmdp.seckill.topic";
    public static final String ROUTINGKEY_NAME = "seckill.order";

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_NAME),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenVoucherOrder(Long localMessageId, Channel channel, Message message) throws IOException {
        long deliverTag = message.getMessageProperties().getDeliveryTag();

        LocalMessage localMessage = localMessageService.getById(localMessageId);
        if (localMessage == null) {
            log.error("消息 {} 不存在", localMessageId);
            channel.basicAck(deliverTag, false);
            return;
        }

        Integer status = localMessage.getStatus();
        if (status != MessageConstants.UNPROCESSED) {
            log.info("消息 {} {}", localMessageId, (status == MessageConstants.DEAD ? "已死亡" : "已处理"));
            channel.basicAck(deliverTag, false);
            return;
        }

        boolean returnToMQ = false;
        try {
            VoucherOrder voucherOrder = JSONUtil.toBean(localMessage.getMessageBody(), VoucherOrder.class);
            handleVoucherOrder(voucherOrder, localMessageId);
        } catch (Exception e) {
            log.error("消息 {} 处理失败", localMessageId, e);
            Integer retryTimes = localMessage.getRetryTimes() + 1;
            returnToMQ = updateRetryTimes(localMessageId, retryTimes, channel, deliverTag);
        }

        if (BooleanUtil.isTrue(returnToMQ)) {
            // 重新入队
            channel.basicNack(deliverTag, false, true);
        } else {
            // 不入队
            channel.basicAck(deliverTag, false);
        }
    }

    private boolean updateRetryTimes(Long localMessageId, Integer retryTimes, Channel channel, long deliverTag) throws IOException {
        try {
            localMessageService.lambdaUpdate()
                .set(LocalMessage::getRetryTimes, retryTimes)
                .eq(LocalMessage::getId, localMessageId)
                .update();

            if (retryTimes >= MessageConstants.MAX_RETRY_TIMES) {
                // 更新消息状态为已死亡
                localMessageService.lambdaUpdate()
                    .set(LocalMessage::getStatus, MessageConstants.DEAD)
                    .eq(LocalMessage::getId, localMessageId)
                    .update();
                return false;
            }
        } catch (Exception e) {
            log.error("更新消息 {} 重试次数失败", localMessageId, e);
            return false;
        }

        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    private void handleVoucherOrder(VoucherOrder voucherOrder, Long localMessageId) {
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
