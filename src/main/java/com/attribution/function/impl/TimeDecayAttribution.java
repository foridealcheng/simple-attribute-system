package com.attribution.function.impl;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.function.AttributionFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Time Decay 归因模型
 * 
 * 越接近转化的点击获得越多的功劳
 * 
 * 规则：
 * - 使用指数衰减函数
 * - 时间越接近转化，权重越高
 * - 权重公式：weight = e^(-λ * timeDiff)
 * 
 * 参数：
 * - decayFactor (λ): 衰减因子，默认 0.1
 * - 半衰期：约 7 小时（当λ=0.1 时）
 * 
 * 适用场景：
 * - 近期触点对转化影响更大
 * - 考虑用户决策的时间衰减效应
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class TimeDecayAttribution implements AttributionFunction {

    private static final String MODEL_NAME = "TIME_DECAY";
    private static final String DESCRIPTION = "More credit to recent clicks using exponential decay";
    
    /**
     * 衰减因子（默认 0.1）
     * 可以通过构造函数或配置调整
     */
    private final double decayFactor;

    public TimeDecayAttribution() {
        this.decayFactor = 0.1;
    }

    public TimeDecayAttribution(double decayFactor) {
        this.decayFactor = decayFactor;
    }

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

        long conversionTime = conversionEvent.getTimestamp();

        // 计算每个点击的权重
        Map<ClickEvent, Double> clickWeights = new HashMap<>();
        double totalWeight = 0.0;

        for (ClickEvent click : validClicks) {
            long timeDiffMs = conversionTime - click.getTimestamp();
            double timeDiffHours = timeDiffMs / 3600000.0; // 转换为小时
            
            // 指数衰减：weight = e^(-λ * t)
            double weight = Math.exp(-decayFactor * timeDiffHours);
            
            clickWeights.put(click, weight);
            totalWeight += weight;
        }

        // 归一化权重，计算功劳分布
        Map<String, Double> creditDistribution = new HashMap<>();
        List<String> clickIds = new ArrayList<>();

        for (Map.Entry<ClickEvent, Double> entry : clickWeights.entrySet()) {
            ClickEvent click = entry.getKey();
            double weight = entry.getValue();
            double credit = weight / totalWeight; // 归一化

            clickIds.add(click.getEventId());
            
            String key = String.format("%s:%s:%s", 
                click.getAdvertiserId(), 
                click.getCampaignId(), 
                click.getEventId());
            creditDistribution.put(key, credit);
        }

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

        log.debug("Time Decay Attribution: conversion={}, clicks={}, decayFactor={}, totalWeight={}", 
            conversionEvent.getEventId(), validClicks.size(), decayFactor, totalWeight);

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
     * 获取衰减因子
     */
    public double getDecayFactor() {
        return decayFactor;
    }

    /**
     * 计算归因窗口（小时）
     */
    private int calculateLookbackWindow(List<ClickEvent> clicks, ConversionEvent conversion) {
        if (clicks.isEmpty()) {
            return 0;
        }
        
        ClickEvent earliestClick = clicks.get(0);
        long diffMs = conversion.getTimestamp() - earliestClick.getTimestamp();
        return (int) (diffMs / 3600000L) + 1;
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
