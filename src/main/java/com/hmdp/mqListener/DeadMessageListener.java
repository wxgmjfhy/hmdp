package com.hmdp.mqListener;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.hmdp.entity.LocalMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * 死信队列监听器
 */
@Slf4j
@Component
public class DeadMessageListener {

    public static final String EXCHANGE_NAME = "hmdp.dead.direct";
    public static final String QUEUE_NAME = "hmdp.dead.queue";
    public static final String ROUTINGKEY_NAME = "dead";

    @RabbitListener(bindings = @QueueBinding(
        value = @Queue(name = QUEUE_NAME),
        exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = ROUTINGKEY_NAME
    ))
    public void listenDeadMessage(LocalMessage localMessage) {
        // TODO 人工处理
        log.error("需要人工处理死信: {}", localMessage);
    }
}
