package com.hmdp.task;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hmdp.entity.LocalMessage;
import com.hmdp.mqSender.MessageSender;
import com.hmdp.service.ILocalMessageService;
import com.hmdp.utils.MessageConstants;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时扫描数据库
 */
@Slf4j
@Component
public class MessageTask {

    @Resource
    private ILocalMessageService localMessageService;

    @Resource
    private MessageSender messageSender;

    /**
     * TODO 选择合适的周期
     * TODO 优化扫库效率
     * TODO 批量发送消息
     */
    @Scheduled(fixedRate = 3600000)
    public void handleUnprocessedMessage() {
        List<LocalMessage> unprocessedMessages = localMessageService.lambdaQuery()
            .eq(LocalMessage::getStatus, MessageConstants.UNPROCESSED)
            .lt(LocalMessage::getCreateTime, LocalDateTime.now().minusMinutes(1))
            .list();

        if (unprocessedMessages == null || unprocessedMessages.isEmpty()) {
            return;
        }

        unprocessedMessages.forEach(localMessage -> {
            try {
                Integer sendTimes = localMessage.getSendTimes() + 1;
                if (sendTimes > MessageConstants.MAX_SEND_TIMES) {
                    localMessageService.lambdaUpdate()
                        .set(LocalMessage::getStatus, MessageConstants.DEAD)
                        .eq(LocalMessage::getId, localMessage.getId())
                        .update();
                    messageSender.sendDeadMessage(localMessage);
                } else {
                    localMessageService.lambdaUpdate()
                        .set(LocalMessage::getSendTimes, sendTimes)
                        .eq(LocalMessage::getId, localMessage.getId())
                        .update();
                    messageSender.sendMessage(localMessage);
                }
            } catch (Exception e) {
                log.error("定时任务中, 消息 {} 处理异常", localMessage.getId(), e);
            }
        });
    }

}
