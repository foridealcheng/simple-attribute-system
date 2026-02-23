package com.attribution.source.adapter;

import com.attribution.model.RawEvent;

import java.util.Map;

/**
 * 数据源适配器接口
 * 
 * 统一不同数据源 (Kafka/RocketMQ/Fluss) 的接入方式
 * 将原始消息转换为 RawEvent 流
 * 
 * @param <T> 原始消息类型
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public interface SourceAdapter<T> {

    /**
     * 初始化适配器
     * 
     * @param config 数据源配置
     */
    void init(SourceConfig config);

    /**
     * 消费消息流
     * 
     * @return RawEvent 流
     */
    Iterable<RawEvent> consume();

    /**
     * 提交偏移量
     * 
     * @param offsets 消息偏移量
     */
    void commitOffset(Map<String, Long> offsets);

    /**
     * 获取数据源类型
     * 
     * @return 数据源类型 (KAFKA/ROCKETMQ/FLUSS)
     */
    String getSourceType();

    /**
     * 关闭连接，释放资源
     */
    void close();

    /**
     * 获取健康状态
     * 
     * @return 是否健康
     */
    default boolean isHealthy() {
        return true;
    }
}
