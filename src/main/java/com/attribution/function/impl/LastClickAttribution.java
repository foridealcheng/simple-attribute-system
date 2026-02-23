package com.attribution.function.impl;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.function.AttributionFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Last Click 归因模型
 * 
 * 将 100% 的功劳归因给最后一次点击
 * 
 * 规则：
 * - 选择时间上最接近转化的点击
 * - 该点击获得 100% 的功劳
 * 
 * 适用场景：
 * - 简单直接的用户旅程
 * - 最后一次触点对转化影响最大
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class LastClickAttribution implements AttributionFunction {

    private static final String MODEL_NAME = "LAST_CLICK";
    private static final String DESCRIPTION = "100% credit to the last click before conversion";

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

        // 按时间戳排序（确保升序）
        List<ClickEvent> sortedClicks = new ArrayList<>(validClicks);
        sortedClicks.sort(Comparator.comparingLong(ClickEvent::getTimestamp));

        // 获取最后一次点击
        ClickEvent lastClick = sortedClicks.get(sortedClicks.size() - 1);

        // 创建归因结果
        AttributionResult result = AttributionResult.builder()
            .resultId(generateResultId(conversionEvent))
            .conversionId(conversionEvent.getEventId())
            .userId(conversionEvent.getUserId())
            .attributionModel(MODEL_NAME)
            .attributedClicks(Collections.singletonList(lastClick.getEventId()))
            .creditDistribution(createCreditDistribution(lastClick, 1.0))
            .totalConversionValue(conversionEvent.getConversionValue() != null ? 
                conversionEvent.getConversionValue() : 0.0)
            .currency(conversionEvent.getCurrency() != null ? conversionEvent.getCurrency() : "CNY")
            .advertiserId(conversionEvent.getAdvertiserId())
            .campaignId(conversionEvent.getCampaignId())
            .attributionTimestamp(System.currentTimeMillis())
            .lookbackWindowHours(calculateLookbackWindow(lastClick, conversionEvent))
            .status("SUCCESS")
            .retryCount(0)
            .createTime(System.currentTimeMillis())
            .build();

        log.debug("Last Click Attribution: conversion={}, lastClick={}", 
            conversionEvent.getEventId(), lastClick.getEventId());

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
    private Map<String, Double> createCreditDistribution(ClickEvent click, double credit) {
        Map<String, Double> distribution = new HashMap<>();
        String key = String.format("%s:%s:%s", 
            click.getAdvertiserId(), 
            click.getCampaignId(), 
            click.getEventId());
        distribution.put(key, credit);
        return distribution;
    }

    /**
     * 计算归因窗口（小时）
     */
    private int calculateLookbackWindow(ClickEvent click, ConversionEvent conversion) {
        long diffMs = conversion.getTimestamp() - click.getTimestamp();
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
