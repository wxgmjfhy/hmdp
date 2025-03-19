package com.hmdp.sender;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hmdp.listener.VoucherOrderListener;

import lombok.extern.slf4j.Slf4j;

import com.hmdp.entity.VoucherOrder;

@Slf4j
@Component
public class SeckillMessageSender {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendVoucherOrder(VoucherOrder voucherOrder) {
        log.info("发送订单消息: {}", voucherOrder);
        rabbitTemplate.convertAndSend(VoucherOrderListener.EXCHANGE_NAME, VoucherOrderListener.ROUTINGKEY_NAME, voucherOrder, new CorrelationData());
    }
}
