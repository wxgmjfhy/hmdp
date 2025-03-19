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

    public static final String QUEUE_PREFIX = "hmdp.seckill.voucherOrder.queue";
    public static final String EXCHANGE_NAME = "hmdp.seckill.topic";
    public static final String ROUTINGKEY_NAME = "seckill.order";

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_PREFIX + "1"),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenVoucherOrderQueue1(VoucherOrder voucherOrder) {
        log.info("队列 1 接收到秒杀优惠券订单信息: {}", voucherOrder);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_PREFIX + "2"),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenVoucherOrderQueue2(VoucherOrder voucherOrder) {
        log.info("队列 2 接收到秒杀优惠券订单信息: {}", voucherOrder);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }

    
}

