package com.attribution.flink;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.model.ClickEvent;
import com.attribution.model.ClickSession;
import com.attribution.config.FlussSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.List;

/**
 * Click KV 批量写入处理器
 * 
 * 功能:
 * 1. 缓冲 Click 事件
 * 2. 按批量大小或时间间隔触发写入
 * 3. 按 userId 分组批量更新 KV Store (Redis/Fluss)
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class ClickKVBatchWriterProcessFunction 
    extends KeyedProcessFunction<String, ClickEvent, Void> {

    private transient KVClient kvClient;
    private final int batchSize;
    private final long batchIntervalMs;
    
    private transient List<ClickEvent> buffer;
    private transient long lastFlushTime;

    /**
     * 构造函数
     * 
     * @param batchSize 批量大小
     * @param batchIntervalMs 批量间隔（毫秒）
     */
    public ClickKVBatchWriterProcessFunction(
        int batchSize,
        long batchIntervalMs
    ) {
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // 在 open() 中创建 KVClient（避免序列化问题）
        FlussSourceConfig config = FlussSourceConfig.createDefault();
        this.kvClient = KVClientFactory.create(config);
        
        this.buffer = new ArrayList<>();
        this.lastFlushTime = System.currentTimeMillis();
        
        log.info("ClickKVBatchWriterProcessFunction opened. batchSize={}, interval={}ms, kvClient={}", 
            batchSize, batchIntervalMs, kvClient.getClass().getSimpleName());
    }

    @Override
    public void processElement(ClickEvent click, Context ctx, Collector<Void> out) throws Exception {
        // 添加到缓冲区
        buffer.add(click);
        
        // 检查是否需要刷新
        long currentTime = System.currentTimeMillis();
        if (buffer.size() >= batchSize || 
            (currentTime - lastFlushTime) >= batchIntervalMs) {
            flush(ctx);
        }
    }

    /**
     * 刷新缓冲区到 KV Store
     */
    private void flush(Context ctx) {
        if (buffer.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int clickCount = buffer.size();
        String userId = ctx.getCurrentKey();

        try {
            // 获取当前用户的现有会话
            ClickSession session = kvClient.get(userId);
            if (session == null) {
                session = new ClickSession();
            }

            // 批量添加点击（使用批量方法）
            session.addClicks(buffer, 50); // 默认最大 50 个点击

            // 写回 Fluss KV
            kvClient.put(userId, session);

            long duration = System.currentTimeMillis() - startTime;
            log.debug("Batch write completed: userId={}, clicks={}, duration={}ms", 
                userId, clickCount, duration);

        } catch (Exception e) {
            log.error("Failed to batch write to Fluss KV: userId={}, clicks={}", 
                userId, clickCount, e);
            // 这里可以选择重试或发送到错误队列
            // 当前实现：抛出异常，让 Flink 重启处理
            throw new RuntimeException("Failed to write to Fluss KV", e);
        } finally {
            // 清空缓冲区
            buffer.clear();
            lastFlushTime = System.currentTimeMillis();
        }
    }

    @Override
    public void close() throws Exception {
        // 刷新剩余的缓冲数据
        if (!buffer.isEmpty()) {
            log.info("Flushing remaining {} clicks before close", buffer.size());
            // close 时没有 ctx，使用 keyBy 的当前 key
            flush(null);
        }
        
        if (kvClient != null) {
            kvClient.close();
        }
        
        super.close();
        log.info("FlussKVBatchWriterProcessFunction closed");
    }

    /**
     * 获取当前缓冲区大小（用于监控）
     */
    public int getBufferSize() {
        return buffer != null ? buffer.size() : 0;
    }
}
