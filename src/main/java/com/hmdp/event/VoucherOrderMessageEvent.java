package com.hmdp.event;

import org.springframework.context.ApplicationEvent;

import com.hmdp.entity.VoucherOrder;

/**
 * 秒杀优惠券订单 - 消息 - 事件类
 */
public class VoucherOrderMessageEvent extends ApplicationEvent {

    private final VoucherOrder voucherOrder;

    public VoucherOrderMessageEvent(Object source, VoucherOrder voucherOrder) {
        super(source);
        this.voucherOrder = voucherOrder;
    }

    public VoucherOrder getVoucherOrder() {
        return voucherOrder;
    }

}
