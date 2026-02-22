package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 归因结果模型
 * 
 * 归因引擎计算结果，输出到 Fluss MQ
 * 分为成功结果（attribution-results-success）和失败结果（attribution-results-failed）
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 归因结果 ID（唯一标识）
     */
    @JsonProperty("attribution_id")
    private String attributionId;

    /**
     * 转化事件 ID
     */
    @JsonProperty("conversion_event_id")
    private String conversionEventId;

    /**
     * 用户 ID
     */
    @JsonProperty("user_id")
    private String userId;

    /**
     * 广告主 ID
     */
    @JsonProperty("advertiser_id")
    private String advertiserId;

    /**
     * 归因的点击事件列表
     */
    @JsonProperty("attributed_clicks")
    private List<AttributedClick> attributedClicks;

    /**
     * 总转化值
     */
    @JsonProperty("total_value")
    private Double totalValue;

    /**
     * 使用的归因模型
     */
    @JsonProperty("attribution_model")
    private String attributionModel;

    /**
     * 处理时间（毫秒）
     */
    @JsonProperty("processing_time")
    private Long processingTime;

    /**
     * 元数据（JSON 格式）
     */
    @JsonProperty("metadata")
    private String metadata;

    /**
     * 状态（SUCCESS/FAILED/RETRYING）
     */
    @JsonProperty("status")
    private String status;

    /**
     * 失败原因（如果失败）
     */
    @JsonProperty("failure_reason")
    private String failureReason;

    /**
     * 重试次数
     */
    @JsonProperty("retry_count")
    private Integer retryCount;

    /**
     * 创建时间
     */
    @JsonProperty("create_time")
    private Long createTime;

    /**
     * 归因的单个点击
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttributedClick implements Serializable {
        
        private static final long serialVersionUID = 1L;

        /**
         * 点击事件 ID
         */
        @JsonProperty("click_event_id")
        private String clickEventId;

        /**
         * 点击时间戳
         */
        @JsonProperty("click_timestamp")
        private Long clickTimestamp;

        /**
         * 广告系列 ID
         */
        @JsonProperty("campaign_id")
        private String campaignId;

        /**
         * 广告创意 ID
         */
        @JsonProperty("creative_id")
        private String creativeId;

        /**
         * 媒体渠道 ID
         */
        @JsonProperty("media_id")
        private String mediaId;

        /**
         * 点击类型
         */
        @JsonProperty("click_type")
        private String clickType;

        /**
         * 归因权重（0.0-1.0）
         */
        @JsonProperty("weight")
        private Double weight;

        /**
         * 归因值（weight * totalValue）
         */
        @JsonProperty("attributed_value")
        private Double attributedValue;
    }
}
