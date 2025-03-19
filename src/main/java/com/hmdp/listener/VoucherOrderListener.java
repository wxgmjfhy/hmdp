package com.hmdp.listener;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class VoucherOrderListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    public static final String QUEUE_NAME = "hmdp.seckill.voucherOrder.queue";
    public static final String EXCHANGE_NAME = "hmdp.seckill.topic";
    public static final String ROUTINGKEY_NAME = "seckill.order";

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_NAME),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenVoucherOrder(VoucherOrder voucherOrder) {
        log.info("接收到秒杀优惠券订单消息: {}", voucherOrder);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }
    
}

