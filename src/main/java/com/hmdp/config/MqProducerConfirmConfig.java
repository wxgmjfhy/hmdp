package com.hmdp.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@Configuration
public class MqProducerConfirmConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        rabbitTemplate.setReturnsCallback((returnedMessage) -> {
            log.error("触发 return callback,");
            log.info("exchange: {}", returnedMessage.getExchange());
            log.info("routingKey: {}", returnedMessage.getRoutingKey());
            log.info("message: {}", returnedMessage.getMessage());
            log.info("replyCode: {}", returnedMessage.getReplyCode());
            log.info("replyText: {}", returnedMessage.getReplyText());
        });

        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息发送成功, 消息 ID: {}", correlationData != null ? correlationData.getId() : "null");
            } else {
                log.error("消息发送失败, 原因: {}", cause);
            }
        });
    }
}
