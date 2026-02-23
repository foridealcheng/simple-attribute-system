package com.attribution.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 原始事件模型
 * 
 * 数据接入层的统一数据格式
 * 由 SourceAdapter 从不同数据源转换而来
 * 后续会被转换为 ClickEvent 或 ConversionEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件 ID（唯一标识）
     */
    private String eventId;

    /**
     * 用户 ID（归因 Key）
     */
    private String userId;

    /**
     * 时间戳（毫秒）
     */
    private Long timestamp;

    /**
     * 事件类型（CLICK/CONVERSION/IMPRESSION 等）
     */
    private String eventType;

    /**
     * 广告主 ID
     */
    private String advertiserId;

    /**
     * 广告系列 ID
     */
    private String campaignId;

    /**
     * 所有原始数据字段
     * 包含未映射到标准字段的额外数据
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 元数据
     * 包含数据源信息、分区、偏移量等
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 获取元数据字段
     * 
     * @param key 字段名
     * @return 字段值
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * 设置元数据
     * 
     * @param metadata 元数据 Map
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * 获取数据源类型
     * 
     * @return KAFKA/ROCKETMQ/FLUSS
     */
    public String getSource() {
        Object source = metadata.get("source");
        return source != null ? source.toString() : "UNKNOWN";
    }

    /**
     * 是否为 Click 事件
     * 
     * @return 是否是 Click
     */
    public boolean isClick() {
        return "CLICK".equalsIgnoreCase(eventType) || 
               "click".equalsIgnoreCase(eventType) ||
               data.containsKey("click_type");
    }

    /**
     * 是否为 Conversion 事件
     * 
     * @return 是否是 Conversion
     */
    public boolean isConversion() {
        return "CONVERSION".equalsIgnoreCase(eventType) || 
               "conversion".equalsIgnoreCase(eventType) ||
               data.containsKey("conversion_type");
    }

    /**
     * 转换为 ClickEvent
     * 
     * @return ClickEvent 对象
     */
    public ClickEvent toClickEvent() {
        return ClickEvent.builder()
            .eventId(eventId)
            .userId(userId)
            .timestamp(timestamp)
            .advertiserId(advertiserId)
            .campaignId(campaignId)
            .clickType((String) data.get("click_type"))
            .ipAddress((String) data.get("ip_address"))
            .userAgent((String) data.get("user_agent"))
            .deviceType((String) data.get("device_type"))
            .os((String) data.get("os"))
            .appVersion((String) data.get("app_version"))
            .attributes(data.get("attributes") != null ? data.get("attributes").toString() : null)
            .createTime(System.currentTimeMillis())
            .source(getSource())
            .build();
    }

    /**
     * 转换为 ConversionEvent
     * 
     * @return ConversionEvent 对象
     */
    public ConversionEvent toConversionEvent() {
        // 提取 conversion_value，处理不同类型
        Object valueObj = data.get("conversion_value");
        Double conversionValue = null;
        if (valueObj instanceof Number) {
            conversionValue = ((Number) valueObj).doubleValue();
        } else if (valueObj instanceof String) {
            try {
                conversionValue = Double.parseDouble((String) valueObj);
            } catch (NumberFormatException e) {
                // 忽略，使用 null
            }
        }

        // 提取 quantity，处理不同类型
        Object qtyObj = data.get("quantity");
        Integer quantity = null;
        if (qtyObj instanceof Number) {
            quantity = ((Number) qtyObj).intValue();
        } else if (qtyObj instanceof String) {
            try {
                quantity = Integer.parseInt((String) qtyObj);
            } catch (NumberFormatException e) {
                // 忽略，使用 null
            }
        }

        return ConversionEvent.builder()
            .eventId(eventId)
            .userId(userId)
            .timestamp(timestamp)
            .advertiserId(advertiserId)
            .campaignId(campaignId)
            .conversionType((String) data.get("conversion_type"))
            .conversionValue(conversionValue)
            .currency((String) data.get("currency"))
            .transactionId((String) data.get("transaction_id"))
            .productId((String) data.get("product_id"))
            .quantity(quantity)
            .ipAddress((String) data.get("ip_address"))
            .userAgent((String) data.get("user_agent"))
            .deviceType((String) data.get("device_type"))
            .os((String) data.get("os"))
            .appVersion((String) data.get("app_version"))
            .attributes(data.get("attributes") != null ? data.get("attributes").toString() : null)
            .createTime(System.currentTimeMillis())
            .source(getSource())
            .build();
    }

    @Override
    public String toString() {
        return "RawEvent{" +
                "eventId='" + eventId + '\'' +
                ", userId='" + userId + '\'' +
                ", timestamp=" + timestamp +
                ", eventType='" + eventType + '\'' +
                ", source=" + getSource() +
                '}';
    }
}
