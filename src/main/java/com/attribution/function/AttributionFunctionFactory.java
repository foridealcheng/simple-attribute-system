package com.attribution.function;

import com.attribution.function.impl.*;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 归因函数工厂
 * 
 * 创建和管理不同归因模型的实例
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionFunctionFactory {

    private static final Map<String, AttributionFunction> FUNCTION_REGISTRY = new HashMap<>();

    static {
        // 注册所有归因函数
        register(new LastClickAttribution());
        register(new LinearAttribution());
        register(new TimeDecayAttribution());
        register(new PositionBasedAttribution());
        
        log.info("Attribution functions registered: {}", FUNCTION_REGISTRY.keySet());
    }

    private AttributionFunctionFactory() {
        // 工具类，防止实例化
    }

    /**
     * 注册归因函数
     * 
     * @param function 归因函数实例
     */
    public static void register(AttributionFunction function) {
        FUNCTION_REGISTRY.put(function.getModelName(), function);
        log.debug("Registered attribution function: {}", function.getModelName());
    }

    /**
     * 获取归因函数实例
     * 
     * @param modelName 模型名称（LAST_CLICK, LINEAR, TIME_DECAY, POSITION_BASED）
     * @return 归因函数实例
     * @throws IllegalArgumentException 不支持的模型
     */
    public static AttributionFunction getFunction(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be null or empty");
        }

        String normalizedModel = modelName.toUpperCase().trim();
        AttributionFunction function = FUNCTION_REGISTRY.get(normalizedModel);
        
        if (function == null) {
            throw new IllegalArgumentException(
                "Unsupported attribution model: " + modelName + 
                ". Supported models: " + FUNCTION_REGISTRY.keySet()
            );
        }

        return function;
    }

    /**
     * 获取所有支持的归因模型
     * 
     * @return 模型名称列表
     */
    public static String[] getSupportedModels() {
        return FUNCTION_REGISTRY.keySet().toArray(new String[0]);
    }

    /**
     * 检查模型是否支持
     * 
     * @param modelName 模型名称
     * @return 是否支持
     */
    public static boolean isSupported(String modelName) {
        if (modelName == null) {
            return false;
        }
        return FUNCTION_REGISTRY.containsKey(modelName.toUpperCase().trim());
    }

    /**
     * 获取默认归因函数（Last Click）
     * 
     * @return 默认归因函数
     */
    public static AttributionFunction getDefault() {
        return FUNCTION_REGISTRY.get("LAST_CLICK");
    }

    /**
     * 清除所有注册的函数（用于测试）
     */
    public static void clear() {
        FUNCTION_REGISTRY.clear();
        log.warn("All attribution functions cleared");
    }
}
