package com.attribution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 事件类型枚举
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Getter
@AllArgsConstructor
public enum EventType {

    /**
     * 曝光事件
     */
    IMPRESSION("IMPRESSION", "曝光"),

    /**
     * 点击事件
     */
    CLICK("CLICK", "点击"),

    /**
     * 浏览事件
     */
    VIEW("VIEW", "浏览"),

    /**
     * 安装事件
     */
    INSTALL("INSTALL", "安装"),

    /**
     * 注册事件
     */
    REGISTER("REGISTER", "注册"),

    /**
     * 付费事件
     */
    PURCHASE("PURCHASE", "付费"),

    /**
     * 其他转化事件
     */
    OTHER("OTHER", "其他");

    /**
     * 事件类型代码
     */
    private final String code;

    /**
     * 事件类型名称
     */
    private final String name;

    /**
     * 根据代码获取枚举
     * 
     * @param code 事件类型代码
     * @return 事件类型枚举
     */
    public static EventType fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return OTHER;
        }
        
        for (EventType type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        
        return OTHER;
    }

    /**
     * 判断是否为点击类事件
     * 
     * @return true 如果是点击类事件
     */
    public boolean isClickType() {
        return this == IMPRESSION || this == CLICK || this == VIEW;
    }

    /**
     * 判断是否为转化类事件
     * 
     * @return true 如果是转化类事件
     */
    public boolean isConversionType() {
        return this == INSTALL || this == REGISTER || this == PURCHASE || this == OTHER;
    }
}
