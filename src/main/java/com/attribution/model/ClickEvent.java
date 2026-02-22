package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Click 事件模型
 * 
 * 用于记录用户广告点击行为，存储在 Fluss KV Store 中
 * Key: user_id, Value: List<ClickEvent>
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件 ID（唯一标识）
     */
    @JsonProperty("event_id")
    private String eventId;

    /**
     * 用户 ID（归因 Key）
     */
    @JsonProperty("user_id")
    private String userId;

    /**
     * 点击时间戳（毫秒）
     */
    @JsonProperty("timestamp")
    private Long timestamp;

    /**
     * 广告主 ID
     */
    @JsonProperty("advertiser_id")
    private String advertiserId;

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
     * 广告位 ID
     */
    @JsonProperty("placement_id")
    private String placementId;

    /**
     * 媒体渠道 ID
     */
    @JsonProperty("media_id")
    private String mediaId;

    /**
     * 点击类型（impression/click/view 等）
     */
    @JsonProperty("click_type")
    private String clickType;

    /**
     * IP 地址
     */
    @JsonProperty("ip_address")
    private String ipAddress;

    /**
     * 用户代理
     */
    @JsonProperty("user_agent")
    private String userAgent;

    /**
     * 设备类型（mobile/desktop/tablet）
     */
    @JsonProperty("device_type")
    private String deviceType;

    /**
     * 操作系统
     */
    @JsonProperty("os")
    private String os;

    /**
     * 应用版本
     */
    @JsonProperty("app_version")
    private String appVersion;

    /**
     * 额外属性（JSON 格式）
     */
    @JsonProperty("attributes")
    private String attributes;

    /**
     * 创建时间（系统时间）
     */
    @JsonProperty("create_time")
    private Long createTime;

    /**
     * 数据来源（kafka/rocketmq/fluss）
     */
    @JsonProperty("source")
    private String source;
}
