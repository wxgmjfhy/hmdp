package com.hmdp.task;

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
 * 定时扫描数据库, 处理死信
 */
@Slf4j
@Component
public class MessageTask {

    @Resource
    private ILocalMessageService localMessageService;

    @Resource
    private MessageSender messageSender;

    @Scheduled(fixedRate = 3600000)
    public void handleErrorMessage() {
        List<LocalMessage> errorMessages = localMessageService.lambdaQuery()
            .eq(LocalMessage::getStatus, -1)
            .list();

        if (errorMessages == null || errorMessages.isEmpty()) {
            return;
        }

        errorMessages.forEach(localMessage -> {
            try {
                Integer resendTimes = localMessage.getResendTimes();
                if (resendTimes >= MessageConstants.MAX_RESEND_TIMES) {
                    // 达到最大重发次数, 删除记录, 发送到死信队列人工处理
                    localMessageService.removeById(localMessage.getId());
                    messageSender.sendErrorMessage(localMessage);
                } else {
                    // 更新重发次数, 重置其余数据, 重新发送
                    localMessageService.lambdaUpdate()
                        .set(LocalMessage::getStatus, 0)
                        .set(LocalMessage::getRetryTimes, 0)
                        .set(LocalMessage::getResendTimes, resendTimes + 1)
                        .eq(LocalMessage::getId, localMessage.getId())
                        .update();
                    messageSender.send(localMessage);
                }
            } catch (Exception e) {
                log.error("死信 {} 处理异常", localMessage.getId(), e);
            }
        });
    }
}
