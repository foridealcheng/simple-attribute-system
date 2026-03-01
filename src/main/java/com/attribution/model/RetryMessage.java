package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 重试消息
 * 
 * 用于在重试队列中传递归因失败的结果
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 重试消息 ID
     */
    private String retryId;

    /**
     * 原始归因结果 ID
     */
    private String resultId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 转化事件 ID
     */
    private String conversionId;

    /**
     * 广告主 ID
     */
    private String advertiserId;

    /**
     * 失败原因
     */
    private String failureReason;

    /**
     * 当前重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 下一次延迟级别（用于 Kafka 延迟消息）
     */
    private Integer nextDelayLevel;

    /**
     * 原始归因结果（JSON 字符串）
     */
    private String originalResultJson;

    /**
     * 创建时间戳
     */
    private Long createTime;

    /**
     * 下一次重试时间
     */
    private Long nextRetryTime;
}
