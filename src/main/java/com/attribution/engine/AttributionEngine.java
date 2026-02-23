package com.attribution.engine;

import com.attribution.function.AttributionFunction;
import com.attribution.function.AttributionFunctionFactory;
import com.attribution.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 归因引擎核心
 * 
 * 执行归因计算的主引擎
 * 支持多种归因模型
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionEngine {

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
     * @param clickSession 用户点击会话（从 Fluss KV 读取）
     * @return 归因结果
     */
    public AttributionResult attribute(ConversionEvent conversionEvent, FlussClickSession clickSession) {
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
                                       FlussClickSession clickSession,
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

        // 3. 获取归因函数
        AttributionFunction attributionFunction;
        try {
            attributionFunction = AttributionFunctionFactory.getFunction(attributionModel);
        } catch (IllegalArgumentException e) {
            log.error("Invalid attribution model: {}, using default", attributionModel, e);
            attributionFunction = AttributionFunctionFactory.getDefault();
        }

        // 4. 执行归因计算
        AttributionResult result = attributionFunction.attribute(conversionEvent, validClicks);

        log.info("Attribution completed: conversion={}, model={}, status={}, clicks={}",
            conversionEvent.getEventId(), attributionModel, result.getStatus(), 
            result.getAttributedClicks() != null ? result.getAttributedClicks().size() : 0);

        return result;
    }

    /**
     * 批量执行归因计算
     * 
     * @param conversionEvents 转化事件列表
     * @param clickSessions 点击会话 Map (userId -> session)
     * @return 归因结果列表
     */
    public List<AttributionResult> batchAttribute(
            List<ConversionEvent> conversionEvents,
            Map<String, FlussClickSession> clickSessions) {
        
        log.info("Batch attribution started: {} conversions", conversionEvents.size());
        
        return conversionEvents.stream()
            .map(conversion -> {
                FlussClickSession session = clickSessions.get(conversion.getUserId());
                return attribute(conversion, session);
            })
            .collect(java.util.stream.Collectors.toList());
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
            .attributionModel(defaultAttributionModel)
            .attributedClicks(Collections.emptyList())
            .creditDistribution(Collections.emptyMap())
            .totalConversionValue(conversion != null && conversion.getConversionValue() != null ? 
                conversion.getConversionValue() : 0.0)
            .currency(conversion != null ? conversion.getCurrency() : "CNY")
            .advertiserId(conversion != null ? conversion.getAdvertiserId() : null)
            .campaignId(conversion != null ? conversion.getCampaignId() : null)
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
