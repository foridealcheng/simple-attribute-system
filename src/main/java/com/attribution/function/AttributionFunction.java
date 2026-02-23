package com.attribution.function;

import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;

import java.util.List;

/**
 * 归因函数接口
 * 
 * 定义不同归因模型的统一接口
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public interface AttributionFunction {

    /**
     * 执行归因计算
     * 
     * @param conversionEvent 转化事件
     * @param validClicks 有效点击列表（已按时间排序）
     * @return 归因结果
     */
    AttributionResult attribute(ConversionEvent conversionEvent, List<ClickEvent> validClicks);

    /**
     * 获取归因模型名称
     * 
     * @return 模型名称（LAST_CLICK, LINEAR, TIME_DECAY, POSITION_BASED）
     */
    String getModelName();

    /**
     * 获取归因模型描述
     * 
     * @return 模型描述
     */
    String getDescription();

    /**
     * 检查是否支持该转化事件
     * 
     * @param conversionEvent 转化事件
     * @param validClicks 有效点击列表
     * @return 是否支持
     */
    default boolean supports(ConversionEvent conversionEvent, List<ClickEvent> validClicks) {
        return conversionEvent != null && 
               validClicks != null && 
               !validClicks.isEmpty();
    }
}
