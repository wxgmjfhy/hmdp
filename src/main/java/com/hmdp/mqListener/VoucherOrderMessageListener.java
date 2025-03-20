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
            log.error("消息不存在");
            channel.basicReject(deliverTag, false);
            return;
        }

        VoucherOrder voucherOrder = JSONUtil.toBean(localMessage.getMessageBody(), VoucherOrder.class);
        Integer status = localMessage.getStatus();

        // 消息为未处理状态
        if (status == 0) {
            try {
                handleVoucherOrder(voucherOrder, localMessageId);
            } catch (Exception e) {
                log.error("消息 {} 处理失败", localMessage.getId(), e);
                
                try {
                    Integer retryTimes = localMessage.getRetryTimes() + 1;
                    if (retryTimes >= MessageConstants.MAX_RESEND_TIMES) {
                        // 达到这次发送的最大重试次数, 拒绝消息不再入队, 设置为死信
                        channel.basicReject(deliverTag, false);
                        localMessageService.lambdaUpdate()
                            .set(LocalMessage::getStatus, -1)
                            .eq(LocalMessage::getId, localMessageId)
                            .update();
                    } else {
                        // 重新入队, 更新这次发送的重试次数
                        channel.basicNack(deliverTag, false, true);
                        localMessageService.lambdaUpdate()
                            .set(LocalMessage::getRetryTimes, retryTimes)
                            .eq(LocalMessage::getId, localMessageId)
                            .update();
                    }
                } catch (Exception exception) {
                    log.error("更新处理失败的消息 {} 的状态失败", localMessage.getId(), exception);
                }
                
                return;
            }
        }

        channel.basicAck(deliverTag, false);
    }

    @Transactional(rollbackFor = Exception.class)
    private void handleVoucherOrder(VoucherOrder voucherOrder, Long localMessageId) {
        voucherOrderService.createVoucherOrder(voucherOrder);
        // 更新消息状态为已处理
        localMessageService.lambdaUpdate()
            .set(LocalMessage::getStatus, 1)
            .eq(LocalMessage::getId, localMessageId)
            .update();
    }
    
}
