package com.attribution.engine;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.model.ClickSession;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 归因引擎核心
 * 
 * 执行归因计算的主引擎
 * 支持 LAST_CLICK 归因模型
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class AttributionEngine implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认的归因窗口（小时）
     */
    private final int defaultAttributionWindowHours;

    /**
     * 默认的归因模型
     */
    private final String defaultAttributionModel;

    /**
     * 是否启用去重
     */
    private final boolean enableDeduplication;

    public AttributionEngine() {
        this(24, "LAST_CLICK", true);
    }

    public AttributionEngine(int defaultAttributionWindowHours, 
                            String defaultAttributionModel,
                            boolean enableDeduplication) {
        this.defaultAttributionWindowHours = defaultAttributionWindowHours;
        this.defaultAttributionModel = defaultAttributionModel;
        this.enableDeduplication = enableDeduplication;
        
        log.info("AttributionEngine initialized: window={}h, model={}, dedup={}",
            defaultAttributionWindowHours, defaultAttributionModel, enableDeduplication);
    }

    /**
     * 执行归因计算
     * 
     * @param conversionEvent 转化事件
     * @param clickSession 用户点击会话（从 KV Store 读取）
     * @return 归因结果
     */
    public AttributionResult attribute(ConversionEvent conversionEvent, ClickSession clickSession) {
        return attribute(conversionEvent, clickSession, defaultAttributionModel);
    }

    /**
     * 执行归因计算（指定模型）
     * 
     * @param conversionEvent 转化事件
     * @param clickSession 用户点击会话
     * @param attributionModel 归因模型名称
     * @return 归因结果
     */
    public AttributionResult attribute(ConversionEvent conversionEvent, 
                                       ClickSession clickSession,
                                       String attributionModel) {
        
        log.debug("Starting attribution: conversion={}, model={}", 
            conversionEvent.getEventId(), attributionModel);

        // 1. 验证输入
        if (conversionEvent == null) {
            log.warn("Conversion event is null");
            return createFailedResult(conversionEvent, "Conversion event is null");
        }

        if (clickSession == null || clickSession.getClicks() == null || clickSession.getClicks().isEmpty()) {
            log.warn("No clicks found for user: {}", conversionEvent.getUserId());
            return createFailedResult(conversionEvent, "No clicks found in session");
        }

        // 2. 获取有效点击（在归因窗口内）
        List<ClickEvent> validClicks = clickSession.getValidClicks(
            conversionEvent.getTimestamp(), 
            defaultAttributionWindowHours
        );

        if (validClicks.isEmpty()) {
            log.warn("No valid clicks within attribution window for user: {}", 
                conversionEvent.getUserId());
            return createFailedResult(conversionEvent, 
                "No clicks within " + defaultAttributionWindowHours + "h window");
        }

        log.debug("Found {} valid clicks for conversion: {}", 
            validClicks.size(), conversionEvent.getEventId());

        // 3. 执行归因计算（LAST_CLICK 模型）
        AttributionResult result = executeLastClickAttribution(conversionEvent, validClicks);

        log.info("Attribution completed: conversion={}, model={}, status={}, clicks={}",
            conversionEvent.getEventId(), attributionModel, result.getStatus(), 
            result.getAttributedClicks() != null ? result.getAttributedClicks().size() : 0);

        return result;
    }

    /**
     * LAST_CLICK 归因模型实现
     * 
     * @param conversionEvent 转化事件
     * @param validClicks 有效点击列表
     * @return 归因结果
     */
    private AttributionResult executeLastClickAttribution(ConversionEvent conversionEvent, List<ClickEvent> validClicks) {
        // LAST_CLICK: 选择最后一次点击
        ClickEvent lastClick = validClicks.get(validClicks.size() - 1);
        
        String resultId = String.format("result_%s_%d", conversionEvent.getEventId(), System.currentTimeMillis());
        
        Map<String, Double> creditDistribution = new HashMap<>();
        String creditKey = String.format("%s:%s:%s", 
            lastClick.getAdvertiserId(),
            lastClick.getCampaignId() != null ? lastClick.getCampaignId() : "null",
            lastClick.getEventId());
        creditDistribution.put(creditKey, 1.0);
        
        List<String> attributedClicks = Collections.singletonList(lastClick.getEventId());
        
        return AttributionResult.builder()
            .resultId(resultId)
            .conversionId(conversionEvent.getEventId())
            .userId(conversionEvent.getUserId())
            .advertiserId(conversionEvent.getAdvertiserId())
            .campaignId(conversionEvent.getCampaignId())
            .attributionModel("LAST_CLICK")
            .attributedClicks(attributedClicks)
            .creditDistribution(creditDistribution)
            .totalConversionValue(conversionEvent.getConversionValue() != null ? conversionEvent.getConversionValue() : 0.0)
            .currency(conversionEvent.getCurrency() != null ? conversionEvent.getCurrency() : "CNY")
            .attributionTimestamp(System.currentTimeMillis())
            .lookbackWindowHours(defaultAttributionWindowHours)
            .status("SUCCESS")
            .retryCount(0)
            .createTime(System.currentTimeMillis())
            .build();
    }

    /**
     * 创建失败的归因结果
     */
    private AttributionResult createFailedResult(ConversionEvent conversion, String errorMessage) {
        return AttributionResult.builder()
            .resultId(String.format("result_%s_%d", 
                conversion != null ? conversion.getEventId() : "unknown",
                System.currentTimeMillis()))
            .conversionId(conversion != null ? conversion.getEventId() : null)
            .userId(conversion != null ? conversion.getUserId() : null)
            .advertiserId(conversion != null ? conversion.getAdvertiserId() : null)
            .campaignId(conversion != null ? conversion.getCampaignId() : null)
            .attributionModel(defaultAttributionModel)
            .attributedClicks(Collections.emptyList())
            .creditDistribution(Collections.emptyMap())
            .totalConversionValue(conversion != null && conversion.getConversionValue() != null ? 
                conversion.getConversionValue() : 0.0)
            .currency(conversion != null ? conversion.getCurrency() : "CNY")
            .attributionTimestamp(System.currentTimeMillis())
            .lookbackWindowHours(defaultAttributionWindowHours)
            .status("FAILED")
            .errorMessage(errorMessage)
            .retryCount(0)
            .createTime(System.currentTimeMillis())
            .build();
    }

    /**
     * 获取默认归因窗口（小时）
     */
    public int getDefaultAttributionWindowHours() {
        return defaultAttributionWindowHours;
    }

    /**
     * 获取默认归因模型
     */
    public String getDefaultAttributionModel() {
        return defaultAttributionModel;
    }

    /**
     * 是否启用去重
     */
    public boolean isEnableDeduplication() {
        return enableDeduplication;
    }
}
