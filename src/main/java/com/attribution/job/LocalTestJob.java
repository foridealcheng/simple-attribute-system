package com.attribution.job;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.config.FlussSourceConfig;
import com.attribution.config.KafkaSinkConfig;
import com.attribution.flink.AttributionProcessFunction;
import com.attribution.flink.ClickKVBatchWriterProcessFunction;
import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.sink.KafkaAttributionSink;
import com.attribution.validator.ClickValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * 本地测试 Job - 单 JVM 模式
 * 
 * 用于本地测试，将 Click Writer 和 Attribution Engine 合并到一个 Job 中
 * 这样它们可以共享同一个 KV Store（Redis/Fluss）
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class LocalTestJob {

    public static void main(String[] args) throws Exception {
        log.info("Starting Local Test Job...");

        // 1. 创建 Flink 执行环境
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        
        // 启用 Checkpoint
        env.enableCheckpointing(60000);

        // 2. 创建 Kafka Source (Click 事件)
        KafkaSource<String> clickSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("click-events")
            .setGroupId("local-test-click-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        KafkaSource<String> conversionSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("conversion-events")
            .setGroupId("local-test-conversion-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        // 3. 创建数据流
        ObjectMapper mapper = new ObjectMapper();
        
        DataStream<ClickEvent> clickStream = env.fromSource(clickSource, WatermarkStrategy.noWatermarks(), "Click Source")
            .map(json -> {
                try {
                    return mapper.readValue(json, ClickEvent.class);
                } catch (Exception e) {
                    log.error("Failed to parse ClickEvent JSON", e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Click Event Mapper")
            .uid("click-mapper");

        DataStream<ConversionEvent> conversionStream = env.fromSource(conversionSource, WatermarkStrategy.noWatermarks(), "Conversion Source")
            .map(json -> {
                try {
                    return mapper.readValue(json, ConversionEvent.class);
                } catch (Exception e) {
                    log.error("Failed to parse ConversionEvent JSON", e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Conversion Event Mapper")
            .uid("conversion-mapper");

        // 4. 验证 Click 数据
        DataStream<ClickEvent> validClicks = clickStream
            .filter(ClickValidator::isValid)
            .name("Click Validator")
            .uid("click-validator");

        // 5. 有效 Click 批量写入 KV Store
        validClicks
            .keyBy(ClickEvent::getUserId)
            .process(new ClickKVBatchWriterProcessFunction(
                100,
                1000L
            ))
            .name("Click KV Writer");

        log.info("Click data will be written to shared KV store");

        // 6. 归因处理
        DataStream<AttributionResult> resultStream = conversionStream
            .keyBy(ConversionEvent::getUserId)
            .process(new AttributionProcessFunction(
                604800000L,
                50,
                "LAST_CLICK"
            ))
            .name("Attribution Processor")
            .uid("attribution-processor");

        // 7. 分流：成功 vs 失败 vs 重试
        DataStream<AttributionResult> successResults = resultStream
            .filter(result -> "SUCCESS".equals(result.getStatus()))
            .name("Success Filter")
            .uid("success-filter");

        DataStream<AttributionResult> failedResults = resultStream
            .filter(result -> "FAILED".equals(result.getStatus()))
            .name("Failed Filter")
            .uid("failed-filter");

        DataStream<AttributionResult> retryResults = resultStream
            .filter(result -> "RETRY".equals(result.getStatus()))
            .name("Retry Filter")
            .uid("retry-filter");

        // 8. 写入结果（Kafka）
        KafkaSinkConfig kafkaConfig = KafkaSinkConfig.createDefault();
        successResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                kafkaConfig.getSuccessTopic(),
                kafkaConfig.getFailedTopic()
            ))
            .name("Kafka Success Result Sink")
            .uid("kafka-success-sink");
        
        // 9. 失败结果 → Kafka Failed Topic
        failedResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                kafkaConfig.getFailedTopic(),
                kafkaConfig.getFailedTopic()
            ))
            .name("Kafka Failed Result Sink")
            .uid("kafka-failed-sink");
        
        // 10. 重试结果 → Kafka Retry Topic
        retryResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                "attribution-retry",
                "attribution-retry"
            ))
            .name("Kafka Retry Result Sink")
            .uid("kafka-retry-sink");
        
        // 控制台输出（调试用）
        resultStream
            .addSink(new com.attribution.sink.AttributionResultSink("CONSOLE", null, null, null))
            .name("Console Debug Sink")
            .uid("console-debug-sink");

        // 11. 执行作业
        env.execute("Local Test Job");
        
        log.info("Local Test Job submitted successfully");
    }
}
