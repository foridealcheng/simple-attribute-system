package com.attribution.flink;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.engine.AttributionEngine;
import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.model.ClickSession;
import com.attribution.model.RetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 重试处理函数
 * 
 * 从重试消息中重新执行归因计算
 * 
 * Key: user_id
 * Input: RetryMessage
 * Output: AttributionResult (带状态：SUCCESS, RETRY, DLQ)
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class RetryProcessFunction 
    extends KeyedProcessFunction<String, RetryMessage, AttributionResult> {

    private static final long serialVersionUID = 1L;

    /**
     * KV Client（在 open() 中创建）
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
     * 最大重试次数
     */
    private final int maxRetries;

    /**
     * ObjectMapper
     */
    private transient ObjectMapper objectMapper;

    /**
     * 构造函数
     * 
     * @param attributionModel 归因模型
     * @param attributionWindowMs 归因窗口（毫秒）
     * @param maxClicks 最大 Click 数
     * @param maxRetries 最大重试次数
     */
    public RetryProcessFunction(
        String attributionModel,
        long attributionWindowMs,
        int maxClicks,
        int maxRetries
    ) {
        this.attributionEngine = new AttributionEngine(24, attributionModel, true);
        this.attributionWindowMs = attributionWindowMs;
        this.maxClicks = maxClicks;
        this.maxRetries = maxRetries;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // 在 open() 中创建 KVClient（避免序列化问题）
        this.kvClient = KVClientFactory.createDefault();
        this.objectMapper = new ObjectMapper();
        
        log.info("RetryProcessFunction opened. kvClient={}, maxRetries={}", 
            kvClient.getClass().getSimpleName(), maxRetries);
    }

    @Override
    public void processElement(RetryMessage retryMsg, Context ctx, Collector<AttributionResult> out) throws Exception {
        String userId = retryMsg.getUserId();
        String conversionId = retryMsg.getConversionId();
        int retryCount = retryMsg.getRetryCount() != null ? retryMsg.getRetryCount() : 0;
        
        log.info("Processing retry message: userId={}, convId={}, retryCount={}", 
            userId, conversionId, retryCount);

        try {
            // 1. 检查重试次数
            if (retryCount >= maxRetries) {
                log.warn("Max retries reached, sending to DLQ: userId={}, convId={}, retries={}", 
                    userId, conversionId, retryCount);
                
                AttributionResult dlqResult = buildDLQResult(retryMsg, retryCount);
                out.collect(dlqResult);
                return;
            }

            // 2. 从原始 JSON 恢复归因结果
            AttributionResult originalResult = objectMapper.readValue(
                retryMsg.getOriginalResultJson(), 
                AttributionResult.class
            );

            // 3. 从 Redis 获取 Click 历史
            ClickSession session = kvClient.get(userId);
            
            if (session == null || session.getClicks() == null || session.getClicks().isEmpty()) {
                log.warn("No clicks found for user in retry: userId={}, convId={}", userId, conversionId);
                
                // 仍然重试，因为可能是暂时的 Redis 问题
                AttributionResult retryResult = buildRetryResult(retryMsg, retryCount + 1, "NO_CLICKS_IN_RETRY");
                out.collect(retryResult);
                return;
            }

            // 4. 从原始结果中恢复 ConversionEvent
            ConversionEvent conversion = ConversionEvent.builder()
                .eventId(originalResult.getConversionId())
                .userId(userId)
                .timestamp(System.currentTimeMillis()) // 使用当前时间
                .advertiserId(originalResult.getAdvertiserId())
                .conversionType("PURCHASE") // 默认类型
                .conversionValue(originalResult.getTotalConversionValue())
                .currency(originalResult.getCurrency() != null ? originalResult.getCurrency() : "CNY")
                .build();

            // 5. 过滤有效 Click（在归因窗口内）
            ClickSession validSession = new ClickSession();
            validSession.setClicks(session.getClicks());
            
            // 6. 重新执行归因计算
            AttributionEngine engine = new AttributionEngine(24, "LAST_CLICK", true);
            AttributionResult newResult = engine.attribute(conversion, validSession);
            
            // 7. 补充元数据
            newResult.setResultId(originalResult.getResultId());
            newResult.setAttributionTimestamp(System.currentTimeMillis());
            newResult.setRetryCount(retryCount + 1);

            // 8. 检查归因是否成功
            if ("SUCCESS".equals(newResult.getStatus()) || 
                (newResult.getAttributedClicks() != null && !newResult.getAttributedClicks().isEmpty())) {
                
                log.info("Retry success: userId={}, convId={}, clicks={}, retryCount={}", 
                    userId, conversionId, newResult.getAttributedClicks().size(), retryCount + 1);
                
                newResult.setStatus("SUCCESS");
                out.collect(newResult);
                
            } else {
                // 仍然失败，继续重试
                log.warn("Retry failed again: userId={}, convId={}, retryCount={}", 
                    userId, conversionId, retryCount + 1);
                
                AttributionResult retryResult = buildRetryResult(retryMsg, retryCount + 1, "ATTRIBUTION_FAILED_AGAIN");
                out.collect(retryResult);
            }

        } catch (Exception e) {
            log.error("Failed to process retry message: userId={}, convId={}, retryCount={}", 
                userId, conversionId, retryCount, e);
            
            // 构建重试结果
            AttributionResult retryResult = buildRetryResult(retryMsg, retryCount + 1, e.getMessage());
            out.collect(retryResult);
        }
    }

    @Override
    public void close() throws Exception {
        if (kvClient != null) {
            kvClient.close();
        }
        super.close();
        log.info("RetryProcessFunction closed");
    }

    /**
     * 构建 DLQ 结果
     */
    private AttributionResult buildDLQResult(RetryMessage retryMsg, int retryCount) {
        return AttributionResult.builder()
            .resultId(retryMsg.getResultId())
            .conversionId(retryMsg.getConversionId())
            .userId(retryMsg.getUserId())
            .advertiserId(retryMsg.getAdvertiserId())
            .attributedClicks(null)
            .totalConversionValue(0.0)
            .attributionModel("DLQ")
            .status("DLQ")
            .errorMessage("MAX_RETRIES_EXCEEDED: retryCount=" + retryCount)
            .attributionTimestamp(System.currentTimeMillis())
            .retryCount(retryCount)
            .build();
    }

    /**
     * 构建重试结果
     */
    private AttributionResult buildRetryResult(RetryMessage retryMsg, int newRetryCount, String reason) {
        return AttributionResult.builder()
            .resultId(retryMsg.getResultId())
            .conversionId(retryMsg.getConversionId())
            .userId(retryMsg.getUserId())
            .advertiserId(retryMsg.getAdvertiserId())
            .attributedClicks(null)
            .totalConversionValue(0.0)
            .attributionModel("RETRY")
            .status("RETRY")
            .errorMessage(reason)
            .attributionTimestamp(System.currentTimeMillis())
            .retryCount(newRetryCount)
            .build();
    }
}
