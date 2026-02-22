package com.attribution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 归因模型枚举
 * 
 * 支持 4 种归因模型：
 * - LAST_CLICK: 最后点击归因（100% 归因给最后一次点击）
 * - LINEAR: 线性归因（平均分配给所有点击）
 * - TIME_DECAY: 时间衰减归因（越近的点击权重越高）
 * - POSITION_BASED: 基于位置归因（首次 40% + 最后 40% + 中间 20%）
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Getter
@AllArgsConstructor
public enum AttributionModel {

    /**
     * 最后点击归因
     * 100% 归因给转化前的最后一次点击
     */
    LAST_CLICK("LAST_CLICK", "最后点击归因"),

    /**
     * 线性归因
     * 平均分配给所有点击
     */
    LINEAR("LINEAR", "线性归因"),

    /**
     * 时间衰减归因
     * 越近的点击权重越高，使用指数衰减
     */
    TIME_DECAY("TIME_DECAY", "时间衰减归因"),

    /**
     * 基于位置归因
     * 首次点击 40% + 最后点击 40% + 中间点击 20%
     */
    POSITION_BASED("POSITION_BASED", "基于位置归因");

    /**
     * 模型代码
     */
    private final String code;

    /**
     * 模型名称
     */
    private final String name;

    /**
     * 根据代码获取枚举
     * 
     * @param code 模型代码
     * @return 归因模型枚举
     * @throws IllegalArgumentException 如果代码不匹配
     */
    public static AttributionModel fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return LAST_CLICK; // 默认使用最后点击
        }
        
        for (AttributionModel model : values()) {
            if (model.getCode().equalsIgnoreCase(code)) {
                return model;
            }
        }
        
        throw new IllegalArgumentException("Unknown attribution model: " + code);
    }
}
