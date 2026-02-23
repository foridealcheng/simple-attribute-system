package com.attribution.flink;

import com.attribution.engine.AttributionEngine;
import com.attribution.model.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;

import java.util.*;

/**
 * 归因处理函数 (Flink 1.17 简化版)
 * 
 * 使用 Flink KeyedCoProcessFunction 实现
 * 处理 Click 和 Conversion 事件流，执行归因计算
 * 
 * Key: user_id
 * State: 使用 Flink MapState 存储用户点击会话
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionProcessFunction 
    extends KeyedCoProcessFunction<String, ClickEvent, ConversionEvent, AttributionResult> {

    private static final long serialVersionUID = 1L;

    /**
     * 存储用户点击会话 (userId -> FlussClickSession)
     * 简化版：使用 Flink State 而非 Fluss KV
     */
    private transient MapState<String, FlussClickSession> clickSessionState;

    /**
     * 去重状态（记录已处理的事件 ID）
     */
    private transient ValueState<String> lastProcessedEventId;

    /**
     * 归因引擎
     */
    private final AttributionEngine attributionEngine;

    public AttributionProcessFunction(AttributionEngine attributionEngine) {
        this.attributionEngine = attributionEngine;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        log.info("Opening AttributionProcessFunction");
        
        // 初始化点击会话状态
        MapStateDescriptor<String, FlussClickSession> sessionDescriptor = new MapStateDescriptor<>(
            "clickSessions",
            String.class,
            FlussClickSession.class
        );
        clickSessionState = getRuntimeContext().getMapState(sessionDescriptor);
        
        // 初始化去重状态
        ValueStateDescriptor<String> dedupDescriptor = new ValueStateDescriptor<>(
            "lastProcessedEventId",
            String.class
        );
        lastProcessedEventId = getRuntimeContext().getState(dedupDescriptor);
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

        // 2. 读取当前用户的点击会话
        FlussClickSession session = clickSessionState.get(click.getUserId());
        
        if (session == null) {
            session = new FlussClickSession();
            session.setUserId(click.getUserId());
        }

        // 3. 添加新的点击
        session.addClick(click, 50); // 最多保留 50 个点击

        // 4. 保存回 State
        clickSessionState.put(click.getUserId(), session);

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

        // 2. 读取当前用户的点击会话
        FlussClickSession session = clickSessionState.get(conversion.getUserId());

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
}
