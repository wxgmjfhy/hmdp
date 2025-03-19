package com.hmdp.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.hmdp.config.MqErrorMessageConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ErrorMessageListener {

    @RabbitListener(queues = MqErrorMessageConfig.QUEUE_NAME)
    public void listenErrorMessage(Object msg) {
        log.error("消息处理错误: {}", msg.toString());
    }
}
