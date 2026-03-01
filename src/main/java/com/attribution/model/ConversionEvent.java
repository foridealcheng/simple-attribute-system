package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Conversion 事件模型
 * 
 * 用于记录用户转化行为（如下载、注册、付费等）
 * 触发归因计算
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversionEvent implements Serializable {

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
     * 转化时间戳（毫秒）
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
     * 转化类型（install/register/purchase 等）
     */
    @JsonProperty("conversion_type")
    private String conversionType;

    /**
     * 转化值（如付费金额）
     */
    @JsonProperty("conversion_value")
    private Double conversionValue;

    /**
     * 货币单位（USD/CNY 等）
     */
    @JsonProperty("currency")
    private String currency;

    /**
     * 商品 ID（电商场景）
     */
    @JsonProperty("product_id")
    private String productId;

    /**
     * 商品数量
     */
    @JsonProperty("quantity")
    private Integer quantity;

    /**
     * 交易 ID
     */
    @JsonProperty("transaction_id")
    private String transactionId;

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
     * 设备类型
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
     * 数据来源
     */
    @JsonProperty("source")
    private String source;
}
