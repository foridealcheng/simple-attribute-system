package com.attribution.job;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.config.FlussSourceConfig;
import com.attribution.flink.ClickKVBatchWriterProcessFunction;
import com.attribution.model.ClickEvent;
import com.attribution.validator.ClickValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Click Writer Job - 将 Click 事件写入 Fluss KV
 *
 * 职责:
 * 1. 从 Kafka 消费 Click 事件
 * 2. 验证 Click 数据格式
 * 3. 批量写入 Fluss KV Store
 * 4. 无效 Click 发送到 DLQ
 *
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class ClickWriterJob {

    public static void main(String[] args) throws Exception {
        log.info("Starting Click Writer Job...");

        // 1. 创建 Flink 执行环境
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // 本地测试设置为 1

        // 启用 Checkpoint
        env.enableCheckpointing(60000); // 60 秒

        // 2. 创建 Kafka Source (Click 事件)
        KafkaSource<String> clickSource = KafkaSource.<String>builder()
            .setBootstrapServers(getKafkaBootstrapServers())
            .setTopics("click-events")
            .setGroupId("click-writer-group")
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
                    log.error("Failed to parse ClickEvent JSON: {}", json, e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Click Event Mapper")
            .uid("click-mapper");

        // 4. 分流：有效 Click vs 无效 Click
        DataStream<ClickEvent> validClicks = clickStream
            .filter(ClickValidator::isValid)
            .name("Click Validator")
            .uid("click-validator");

        DataStream<ClickEvent> invalidClicks = clickStream
            .filter(click -> !ClickValidator.isValid(click))
            .name("Invalid Click Filter")
            .uid("invalid-click-filter");

        // 5. 无效 Click 发送到 DLQ（暂时打印日志，生产环境使用 Kafka Sink）
        invalidClicks
            .map(mapper::writeValueAsString)
            .addSink(new org.apache.flink.streaming.api.functions.sink.PrintSinkFunction<>(
                "Click DLQ",
                true
            ))
            .name("Click DLQ Sink")
            .uid("click-dlq-sink");
        
        log.info("Invalid clicks will be logged to console (DLQ integration TODO)");

        // 6. 有效 Click 批量写入 KV Store
        validClicks
            .keyBy(ClickEvent::getUserId)
            .process(new ClickKVBatchWriterProcessFunction(
                100,     // batch size
                1000L    // batch interval ms
            ))
            .name("Click KV Writer")
            .uid("click-kv-writer");

        log.info("Valid clicks will be written to KV Store in batches (size=100, interval=1s)");

        log.info("Valid clicks will be written to Fluss KV in batches (size=100, interval=1s)");

        // 7. 执行作业
        env.execute("Click Writer Job");

        log.info("Click Writer Job submitted successfully");
    }

    /**
     * 获取 Kafka Bootstrap Servers
     */
    private static String getKafkaBootstrapServers() {
        String servers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (servers == null || servers.isEmpty()) {
            servers = "kafka:29092"; // 默认值
        }
        return servers;
    }
}
