package com.attribution.flink;

import com.attribution.engine.AttributionEngine;
import com.attribution.model.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 归因处理函数
 * 
 * Flink KeyedCoProcessFunction 实现
 * 处理 Click 和 Conversion 事件流，执行归因计算
 * 
 * Key: user_id
 * State: FlussClickSession (存储在 Fluss KV，Flink 仅用于去重)
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionProcessFunction 
    extends KeyedCoProcessFunction<String, ClickEvent, ConversionEvent, AttributionResult> {

    private static final long serialVersionUID = 1L;

    /**
     * 去重状态（记录已处理的事件 ID）
     */
    private transient ValueState<String> lastProcessedEventId;

    /**
     * 归因引擎
     */
    private final AttributionEngine attributionEngine;

    /**
     * Fluss KV 客户端（用于读写用户点击会话）
     * 注意：实际实现中需要通过 Fluss Client 访问
     */
    private final transient FlussKVClient flussKVClient;

    public AttributionProcessFunction(AttributionEngine attributionEngine) {
        this.attributionEngine = attributionEngine;
        this.flussKVClient = null; // 实际使用时需要初始化
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        log.info("Opening AttributionProcessFunction");
        
        // 初始化去重状态
        ValueStateDescriptor<String> descriptor = new ValueStateDescriptor<>(
            "lastProcessedEventId",
            String.class
        );
        lastProcessedEventId = getRuntimeContext().getState(descriptor);
        
        // 初始化 Fluss KV 客户端（伪代码）
        // this.flussKVClient = new FlussKVClient(config);
    }

    @Override
    public void processElement1(ClickEvent click, 
                                Context ctx, 
                                Collector<AttributionResult> out) throws Exception {
        
        log.debug("Processing Click: userId={}, eventId={}", 
            click.getUserId(), click.getEventId());

        // 1. 去重检查
        if (isDuplicate(click.getEventId())) {
            log.debug("Duplicate click detected: {}", click.getEventId());
            return;
        }

        // 2. 读取当前用户的点击会话（从 Fluss KV）
        FlussClickSession session = readClickSession(click.getUserId());
        
        if (session == null) {
            session = new FlussClickSession();
            session.setUserId(click.getUserId());
        }

        // 3. 添加新的点击
        session.addClick(click, 50); // 最多保留 50 个点击

        // 4. 写回 Fluss KV
        writeClickSession(click.getUserId(), session);

        // 5. 更新去重状态
        lastProcessedEventId.update(click.getEventId());

        log.debug("Click session updated: userId={}, clickCount={}", 
            click.getUserId(), session.getClickCount());
    }

    @Override
    public void processElement2(ConversionEvent conversion, 
                                Context ctx, 
                                Collector<AttributionResult> out) throws Exception {
        
        log.debug("Processing Conversion: userId={}, eventId={}", 
            conversion.getUserId(), conversion.getEventId());

        // 1. 去重检查
        if (isDuplicate(conversion.getEventId())) {
            log.debug("Duplicate conversion detected: {}", conversion.getEventId());
            return;
        }

        // 2. 读取当前用户的点击会话（从 Fluss KV）
        FlussClickSession session = readClickSession(conversion.getUserId());

        // 3. 执行归因计算
        AttributionResult result = attributionEngine.attribute(conversion, session);

        // 4. 输出归因结果
        out.collect(result);

        // 5. 更新去重状态
        lastProcessedEventId.update(conversion.getEventId());

        log.info("Attribution result emitted: conversion={}, status={}", 
            conversion.getEventId(), result.getStatus());
    }

    /**
     * 检查是否重复
     */
    private boolean isDuplicate(String eventId) throws Exception {
        String lastEventId = lastProcessedEventId.value();
        return eventId != null && eventId.equals(lastEventId);
    }

    /**
     * 从 Fluss KV 读取点击会话
     */
    private FlussClickSession readClickSession(String userId) {
        // TODO: 实际实现需要从 Fluss KV 读取
        // return flussKVClient.get(userId);
        return null;
    }

    /**
     * 写入点击会话到 Fluss KV
     */
    private void writeClickSession(String userId, FlussClickSession session) {
        // TODO: 实际实现需要写入 Fluss KV
        // flussKVClient.put(userId, session);
    }
}
