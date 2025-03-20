package com.hmdp.mqSender;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.hmdp.entity.LocalMessage;
import com.hmdp.mqListener.ErrorMessageListener;

/**
 * 消息发送类
 */
@Slf4j
@Component
public class MessageSender {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    /**
     * 发送消息
     */
    public void send(LocalMessage localMessage) {
        try {
            log.info("发送消息: {}", localMessage);
            rabbitTemplate.convertAndSend(localMessage.getExchangeName(), localMessage.getRoutingKey(), localMessage.getId());
        } catch (Exception e) {
            log.error("发送消息 {} 异常", localMessage.getId(), e);
        }
    }

    /**
     * 发送死信
     */
    public void sendErrorMessage(LocalMessage localMessage) {
        try {
            log.info("发送死信: {}", localMessage);
            rabbitTemplate.convertAndSend(ErrorMessageListener.EXCHANGE_NAME, ErrorMessageListener.ROUTINGKEY_NAME, localMessage);
        } catch (Exception e) {
            log.error("发送死信 {} 异常", localMessage.getId(), e);
        }
    }
}
