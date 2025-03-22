package com.hmdp.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 消息实体类 
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_message")
public class LocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 id, 唯一
     */
    private Long id;

    /**
     * 消息体
     */
    private String messageBody;
    
    /**
     * 交换机
     */
    private String exchangeName;

    /**
     * 路由
     */
    private String routingKey;
    
    /**
     * 消息状态
     */
    private Integer status;

    /**
     * 发送次数
     */
    private Integer sendTimes;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
