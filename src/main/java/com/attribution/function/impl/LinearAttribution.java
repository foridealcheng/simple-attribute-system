package com.attribution.function.impl;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.function.AttributionFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Linear 归因模型
 * 
 * 将功劳平均分配给所有有效点击
 * 
 * 规则：
 * - 所有有效点击平均分配功劳
 * - 每个点击获得 1/n 的功劳（n = 点击数）
 * 
 * 适用场景：
 * - 所有触点都被认为同等重要
 * - 完整的用户旅程分析
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class LinearAttribution implements AttributionFunction {

    private static final String MODEL_NAME = "LINEAR";
    private static final String DESCRIPTION = "Equal credit distribution across all clicks";

    @Override
    public AttributionResult attribute(ConversionEvent conversionEvent, List<ClickEvent> validClicks) {
        if (!supports(conversionEvent, validClicks)) {
            return AttributionResult.builder()
                .resultId(generateResultId(conversionEvent))
                .conversionId(conversionEvent != null ? conversionEvent.getEventId() : null)
                .userId(conversionEvent != null ? conversionEvent.getUserId() : null)
                .attributionModel(MODEL_NAME)
                .status("FAILED")
                .errorMessage("Invalid input: conversion or clicks missing")
                .build();
        }

        int clickCount = validClicks.size();
        double creditPerClick = 1.0 / clickCount;

        // 收集所有点击 ID
        List<String> clickIds = new ArrayList<>();
        for (ClickEvent click : validClicks) {
            clickIds.add(click.getEventId());
        }

        // 创建功劳分布
        Map<String, Double> creditDistribution = createCreditDistribution(validClicks, creditPerClick);

        // 创建归因结果
        AttributionResult result = AttributionResult.builder()
            .resultId(generateResultId(conversionEvent))
            .conversionId(conversionEvent.getEventId())
            .userId(conversionEvent.getUserId())
            .attributionModel(MODEL_NAME)
            .attributedClicks(clickIds)
            .creditDistribution(creditDistribution)
            .totalConversionValue(conversionEvent.getConversionValue() != null ? 
                conversionEvent.getConversionValue() : 0.0)
            .currency(conversionEvent.getCurrency() != null ? conversionEvent.getCurrency() : "CNY")
            .advertiserId(conversionEvent.getAdvertiserId())
            .campaignId(conversionEvent.getCampaignId())
            .attributionTimestamp(System.currentTimeMillis())
            .lookbackWindowHours(calculateLookbackWindow(validClicks, conversionEvent))
            .status("SUCCESS")
            .retryCount(0)
            .createTime(System.currentTimeMillis())
            .build();

        log.debug("Linear Attribution: conversion={}, clicks={}, creditPerClick={}", 
            conversionEvent.getEventId(), clickCount, creditPerClick);

        return result;
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 创建功劳分布 Map
     */
    private Map<String, Double> createCreditDistribution(List<ClickEvent> clicks, double creditPerClick) {
        Map<String, Double> distribution = new HashMap<>();
        
        for (ClickEvent click : clicks) {
            String key = String.format("%s:%s:%s", 
                click.getAdvertiserId(), 
                click.getCampaignId(), 
                click.getEventId());
            distribution.put(key, creditPerClick);
        }
        
        return distribution;
    }

    /**
     * 计算归因窗口（小时）
     */
    private int calculateLookbackWindow(List<ClickEvent> clicks, ConversionEvent conversion) {
        if (clicks.isEmpty()) {
            return 0;
        }
        
        // 使用最早的点击计算窗口
        ClickEvent earliestClick = clicks.get(0);
        long diffMs = conversion.getTimestamp() - earliestClick.getTimestamp();
        return (int) (diffMs / 3600000L) + 1; // 转换为小时，向上取整
    }

    /**
     * 生成结果 ID
     */
    private String generateResultId(ConversionEvent conversion) {
        return String.format("result_%s_%d", 
            conversion.getEventId(), 
            System.currentTimeMillis());
    }
}
