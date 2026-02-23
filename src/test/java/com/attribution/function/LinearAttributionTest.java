package com.attribution.function.impl;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linear 归因模型测试
 */
@DisplayName("Linear Attribution Tests")
class LinearAttributionTest {

    private final LinearAttribution attribution = new LinearAttribution();

    @Test
    @DisplayName("Should distribute credit equally among all clicks")
    void testLinearAttribution() {
        ConversionEvent conversion = createConversion(100.0);
        List<ClickEvent> clicks = createClicks(4);

        AttributionResult result = attribution.attribute(conversion, clicks);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals("LINEAR", result.getAttributionModel());
        assertEquals(4, result.getAttributedClicks().size());
        
        // 验证平均分配（每个 25%）
        Map<String, Double> distribution = result.getCreditDistribution();
        assertNotNull(distribution);
        assertEquals(4, distribution.size());
        
        for (Double credit : distribution.values()) {
            assertEquals(0.25, credit, 0.01); // 允许小误差
        }
    }

    @Test
    @DisplayName("Should handle single click (100% credit)")
    void testSingleClick() {
        ConversionEvent conversion = createConversion(50.0);
        List<ClickEvent> clicks = Arrays.asList(createClick("click-1", 1000L));

        AttributionResult result = attribution.attribute(conversion, clicks);

        assertEquals("SUCCESS", result.getStatus());
        Map<String, Double> distribution = result.getCreditDistribution();
        assertEquals(1.0, distribution.values().iterator().next(), 0.01);
    }

    @Test
    @DisplayName("Should handle two clicks (50% each)")
    void testTwoClicks() {
        ConversionEvent conversion = createConversion(100.0);
        List<ClickEvent> clicks = Arrays.asList(
            createClick("click-1", 1000L),
            createClick("click-2", 2000L)
        );

        AttributionResult result = attribution.attribute(conversion, clicks);

        assertEquals("SUCCESS", result.getStatus());
        Map<String, Double> distribution = result.getCreditDistribution();
        assertEquals(2, distribution.size());
        
        for (Double credit : distribution.values()) {
            assertEquals(0.5, credit, 0.01);
        }
    }

    // ========== 辅助方法 ==========

    private ConversionEvent createConversion(Double value) {
        return ConversionEvent.builder()
            .eventId("conv-1")
            .userId("user-1")
            .advertiserId("adv-1")
            .campaignId("camp-1")
            .conversionValue(value)
            .currency("CNY")
            .timestamp(10000L)
            .build();
    }

    private List<ClickEvent> createClicks(int count) {
        ClickEvent[] clicks = new ClickEvent[count];
        for (int i = 0; i < count; i++) {
            clicks[i] = createClick("click-" + (i + 1), (i + 1) * 1000L);
        }
        return Arrays.asList(clicks);
    }

    private ClickEvent createClick(String eventId, Long timestamp) {
        return ClickEvent.builder()
            .eventId(eventId)
            .userId("user-1")
            .advertiserId("adv-1")
            .campaignId("camp-1")
            .timestamp(timestamp)
            .build();
    }
}
