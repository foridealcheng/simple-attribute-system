package com.attribution.function.impl;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.function.AttributionFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Position Based 归因模型（U 型分布）
 * 
 * 首次和最后一次点击获得更多功劳，中间点击平均分配剩余功劳
 * 
 * 规则：
 * - 首次点击：40% 功劳
 * - 最后一次点击：40% 功劳
 * - 中间点击：20% 平均分配
 * 
 * 参数可配置：
 * - firstClickCredit: 首次点击功劳（默认 0.4）
 * - lastClickCredit: 最后一次点击功劳（默认 0.4）
 * - middleClickCredit: 中间点击总功劳（默认 0.2）
 * 
 * 适用场景：
 * - 重视用户获取和转化闭环
 * - 平衡品牌曝光和最终转化
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class PositionBasedAttribution implements AttributionFunction {

    private static final String MODEL_NAME = "POSITION_BASED";
    private static final String DESCRIPTION = "40% to first click, 40% to last click, 20% distributed to middle clicks";
    
    /**
     * 首次点击功劳比例（默认 40%）
     */
    private final double firstClickCredit;

    /**
     * 最后一次点击功劳比例（默认 40%）
     */
    private final double lastClickCredit;

    /**
     * 中间点击总功劳比例（默认 20%）
     */
    private final double middleClickCredit;

    public PositionBasedAttribution() {
        this(0.4, 0.4, 0.2);
    }

    public PositionBasedAttribution(double firstClickCredit, double lastClickCredit, double middleClickCredit) {
        double total = firstClickCredit + lastClickCredit + middleClickCredit;
        if (Math.abs(total - 1.0) > 0.001) {
            throw new IllegalArgumentException("Credit proportions must sum to 1.0");
        }
        this.firstClickCredit = firstClickCredit;
        this.lastClickCredit = lastClickCredit;
        this.middleClickCredit = middleClickCredit;
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

        // 按时间排序
        List<ClickEvent> sortedClicks = new ArrayList<>(validClicks);
        sortedClicks.sort(Comparator.comparingLong(ClickEvent::getTimestamp));

        int clickCount = sortedClicks.size();
        Map<String, Double> creditDistribution = new HashMap<>();
        List<String> clickIds = new ArrayList<>();

        if (clickCount == 1) {
            // 只有一个点击：获得 100% 功劳
            ClickEvent click = sortedClicks.get(0);
            clickIds.add(click.getEventId());
            
            String key = createKey(click);
            creditDistribution.put(key, 1.0);
            
        } else {
            // 多个点击：按位置分配
            ClickEvent firstClick = sortedClicks.get(0);
            ClickEvent lastClick = sortedClicks.get(clickCount - 1);

            // 首次点击
            clickIds.add(firstClick.getEventId());
            String firstKey = createKey(firstClick);
            creditDistribution.put(firstKey, firstClickCredit);

            // 最后一次点击
            if (!lastClick.getEventId().equals(firstClick.getEventId())) {
                clickIds.add(lastClick.getEventId());
                String lastKey = createKey(lastClick);
                creditDistribution.put(lastKey, lastClickCredit);
            }

            // 中间点击（如果有）
            if (clickCount > 2) {
                double creditPerMiddleClick = middleClickCredit / (clickCount - 2);
                
                for (int i = 1; i < clickCount - 1; i++) {
                    ClickEvent middleClick = sortedClicks.get(i);
                    clickIds.add(middleClick.getEventId());
                    
                    String middleKey = createKey(middleClick);
                    creditDistribution.put(middleKey, 
                        creditDistribution.getOrDefault(middleKey, 0.0) + creditPerMiddleClick);
                }
            }
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
            .lookbackWindowHours(calculateLookbackWindow(sortedClicks, conversionEvent))
            .status("SUCCESS")
            .retryCount(0)
            .createTime(System.currentTimeMillis())
            .build();

        log.debug("Position Based Attribution: conversion={}, clicks={}, first={}, last={}, middle={}", 
            conversionEvent.getEventId(), clickCount, firstClickCredit, lastClickCredit, middleClickCredit);

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
     * 获取首次点击功劳比例
     */
    public double getFirstClickCredit() {
        return firstClickCredit;
    }

    /**
     * 获取最后一次点击功劳比例
     */
    public double getLastClickCredit() {
        return lastClickCredit;
    }

    /**
     * 获取中间点击总功劳比例
     */
    public double getMiddleClickCredit() {
        return middleClickCredit;
    }

    /**
     * 创建功劳分布的 Key
     */
    private String createKey(ClickEvent click) {
        return String.format("%s:%s:%s", 
            click.getAdvertiserId(), 
            click.getCampaignId(), 
            click.getEventId());
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
