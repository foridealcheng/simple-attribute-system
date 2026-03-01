package com.attribution.flink;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.engine.AttributionEngine;
import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.model.ClickSession;
import com.attribution.config.FlussSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 归因处理函数
 * 
 * 设计原则:
 * 1. 无状态：不维护 Flink State，所有状态在 KV Store (Redis/Fluss)
 * 2. 异步查询：从 KV Store 获取用户 Click 历史
 * 3. 实时归因：基于 Click 历史执行归因计算
 * 
 * Key: user_id
 * Input: ConversionEvent
 * Output: AttributionResult
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class AttributionProcessFunction 
    extends KeyedProcessFunction<String, ConversionEvent, AttributionResult> {

    private static final long serialVersionUID = 1L;

    /**
     * KV Client
     */
    private transient KVClient kvClient;

    /**
     * 归因引擎
     */
    private final AttributionEngine attributionEngine;

    /**
     * 归因窗口（毫秒）
     */
    private final long attributionWindowMs;

    /**
     * 最大 Click 数
     */
    private final int maxClicks;

    /**
     * 构造函数
     * 
     * @param attributionWindowMs 归因窗口（毫秒）
     * @param maxClicks 最大 Click 数
     * @param attributionModel 归因模型（LAST_CLICK, LINEAR, TIME_DECAY, POSITION_BASED）
     */
    public AttributionProcessFunction(
        long attributionWindowMs,
        int maxClicks,
        String attributionModel
    ) {
        this.attributionWindowMs = attributionWindowMs;
        this.maxClicks = maxClicks;
        this.attributionEngine = new AttributionEngine(24, attributionModel, true);
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        try {
            // 在 open() 中创建 KVClient（避免序列化问题）
            FlussSourceConfig config = FlussSourceConfig.createDefault();
            this.kvClient = KVClientFactory.create(config);
            
            log.info("AttributionProcessFunction opened. Window={}ms, kvClient={}", 
                attributionWindowMs, kvClient.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Failed to initialize KVClient", e);
            throw e; // 重新抛出异常，让 Flink 重启任务
        }
    }

    @Override
    public void processElement(ConversionEvent conversion, Context ctx, Collector<AttributionResult> out) throws Exception {
        String userId = conversion.getUserId();
        long conversionTime = conversion.getTimestamp();
        
        log.debug("Processing Conversion: userId={}, convId={}", userId, conversion.getEventId());

        try {
            // 检查 kvClient 是否已初始化
            if (kvClient == null) {
                throw new IllegalStateException("KVClient not initialized. open() method may have failed.");
            }
            
            // 1. 从 Fluss KV 查询用户 Click 历史
            ClickSession session = kvClient.get(userId);
            
            if (session == null || session.getClicks() == null || session.getClicks().isEmpty()) {
                log.info("No clicks found for user: userId={}, convId={}", userId, conversion.getEventId());
                
                // 输出未匹配结果
                AttributionResult unmatchedResult = AttributionResult.builder()
                    .resultId(generateAttributionId())
                    .conversionId(conversion.getEventId())
                    .userId(userId)
                    .advertiserId(conversion.getAdvertiserId())
                    .attributedClicks(Collections.emptyList())
                    .totalConversionValue(0.0)
                    .attributionModel("UNMATCHED")
                    .status("UNMATCHED")
                    .attributionTimestamp(System.currentTimeMillis())
                    .build();
                
                out.collect(unmatchedResult);
                return;
            }

            // 2. 过滤有效 Click（在归因窗口内）
            List<ClickEvent> validClicks = session.getValidClicks(
                conversionTime, 
                (int) (attributionWindowMs / 3600000L) // 毫秒转小时
            );

            if (validClicks.isEmpty()) {
                log.info("No valid clicks within attribution window: userId={}, convId={}", 
                    userId, conversion.getEventId());
                
                // 输出未匹配结果
                AttributionResult unmatchedResult = AttributionResult.builder()
                    .resultId(generateAttributionId())
                    .conversionId(conversion.getEventId())
                    .userId(userId)
                    .advertiserId(conversion.getAdvertiserId())
                    .attributedClicks(Collections.emptyList())
                    .totalConversionValue(0.0)
                    .attributionModel("UNMATCHED")
                    .status("UNMATCHED")
                    .attributionTimestamp(System.currentTimeMillis())
                    .build();
                
                out.collect(unmatchedResult);
                return;
            }

            log.debug("Found {} valid clicks for user: userId={}, convId={}", 
                validClicks.size(), userId, conversion.getEventId());

            // 3. 执行归因计算（使用 session，包含 validClicks）
            // 需要创建一个新的 session 只包含 validClicks
            ClickSession validSession = ClickSession.builder()
                .clicks(validClicks)
                .build();
            
            AttributionResult result = attributionEngine.attribute(conversion, validSession);
            
            // 4. 补充字段
            result.setAttributionTimestamp(System.currentTimeMillis());

            // 5. 输出结果
            out.collect(result);
            
            log.info("Attribution completed: userId={}, convId={}, clicks={}, value={}", 
                userId, conversion.getEventId(), validClicks.size(), result.getTotalConversionValue());

        } catch (Exception e) {
            log.error("Failed to process conversion: userId={}, convId={}", 
                userId, conversion.getEventId(), e);
            
            // 输出失败结果
            AttributionResult failedResult = AttributionResult.builder()
                .resultId(generateAttributionId())
                .conversionId(conversion.getEventId())
                .userId(userId)
                .advertiserId(conversion.getAdvertiserId())
                .attributedClicks(Collections.emptyList())
                .totalConversionValue(0.0)
                .attributionModel("ERROR")
                .status("FAILED")
                .errorMessage(e.getMessage())
                .attributionTimestamp(System.currentTimeMillis())
                .build();
            
            out.collect(failedResult);
        }
    }

    @Override
    public void close() throws Exception {
        if (kvClient != null) {
            kvClient.close();
        }
        super.close();
        log.info("AttributionProcessFunction closed");
    }

    /**
     * 生成归因 ID
     */
    private String generateAttributionId() {
        return String.format("attr_%d_%s", 
            System.currentTimeMillis(),
            UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }
}
