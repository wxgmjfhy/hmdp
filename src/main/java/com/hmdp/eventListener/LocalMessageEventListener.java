package com.hmdp.eventListener;

import java.time.LocalDateTime;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.hmdp.entity.LocalMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.event.VoucherOrderMessageEvent;
import com.hmdp.mqListener.VoucherOrderMessageListener;
import com.hmdp.mqSender.MessageSender;
import com.hmdp.service.ILocalMessageService;
import com.hmdp.utils.MessageConstants;
import com.hmdp.utils.RedisIdWorker;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件监听器
 */
@Slf4j
@Component
public class LocalMessageEventListener {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ILocalMessageService messageService;

    @Resource
    private MessageSender messageSender;

    /**
     * 监听 秒杀优惠券订单 事件
     */
    @Async
    @EventListener
    public void handleVoucherOrderMessageEvent(VoucherOrderMessageEvent event) {
        VoucherOrder voucherOrder = event.getVoucherOrder();

        LocalMessage localMessage = new LocalMessage();
        localMessage.setId(redisIdWorker.nextId("message"));
        localMessage.setMessageBody(JSONUtil.toJsonStr(voucherOrder));
        localMessage.setExchangeName(VoucherOrderMessageListener.QUEUE_NAME);
        localMessage.setRoutingKey(VoucherOrderMessageListener.ROUTINGKEY_NAME);
        localMessage.setStatus(MessageConstants.UNPROCESSED);
        localMessage.setRetryTimes(0);
        localMessage.setResendTimes(0);
        localMessage.setCreateTime(LocalDateTime.now());

        try {
            // 保存消息到数据库
            messageService.save(localMessage);
            // 发送消息
            messageSender.send(localMessage);
        } catch (Exception e) {
            log.error("保存消息到本地消息表异常", e);
        }        
    }
}
