package com.attribution.job;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.config.FlussSourceConfig;
import com.attribution.config.KafkaSinkConfig;
import com.attribution.flink.AttributionProcessFunction;
import com.attribution.model.AttributionResult;
import com.attribution.model.ConversionEvent;
import com.attribution.sink.KafkaAttributionSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Attribution Engine Job - 归因计算引擎
 * 
 * 职责:
 * 1. 从 Kafka 消费 Conversion 事件
 * 2. 查询 KV Store 获取用户 Click 历史
 * 3. 执行归因计算
 * 4. 输出归因结果到 Kafka
 * 5. 失败结果发送到 Kafka 重试队列
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class AttributionEngineJob {

    public static void main(String[] args) throws Exception {
        log.info("Starting Attribution Engine Job...");

        // 1. 创建 Flink 执行环境
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        
        // 启用 Checkpoint
        env.enableCheckpointing(60000);

        // 2. 创建 Kafka Source (Conversion 事件)
        KafkaSource<String> conversionSource = KafkaSource.<String>builder()
            .setBootstrapServers(getKafkaBootstrapServers())
            .setTopics("conversion-events")
            .setGroupId("attribution-engine-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        // 3. 创建 Conversion 数据流
        ObjectMapper mapper = new ObjectMapper();
        
        DataStream<ConversionEvent> conversionStream = env.fromSource(
                conversionSource, 
                WatermarkStrategy.noWatermarks(), 
                "Conversion Source"
            )
            .map(json -> {
                try {
                    return mapper.readValue(json, ConversionEvent.class);
                } catch (Exception e) {
                    log.error("Failed to parse ConversionEvent JSON: {}", json, e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Conversion Event Mapper")
            .uid("conversion-mapper");

        // 4. 归因处理
        DataStream<AttributionResult> resultStream = conversionStream
            .keyBy(ConversionEvent::getUserId)
            .process(new AttributionProcessFunction(
                604800000L,
                50,
                "LAST_CLICK"
            ))
            .name("Attribution Processor")
            .uid("attribution-processor");

        // 5. 分流：成功 vs 失败 vs 重试
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

        // 6. 成功结果 → Kafka
        KafkaSinkConfig kafkaConfig = KafkaSinkConfig.createDefault();
        successResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                kafkaConfig.getSuccessTopic(),
                kafkaConfig.getFailedTopic()
            ))
            .name("Success Result Sink")
            .uid("success-sink");

        log.info("Success results will be written to Kafka: {}", kafkaConfig.getSuccessTopic());

        // 7. 失败结果 → Kafka Failed Topic
        failedResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                kafkaConfig.getFailedTopic(),
                kafkaConfig.getFailedTopic()
            ))
            .name("Failed Result Sink")
            .uid("failed-sink");

        log.info("Failed results will be written to Kafka: {}", kafkaConfig.getFailedTopic());

        // 8. 重试结果 → Kafka Retry Topic
        retryResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                "attribution-retry",
                "attribution-retry"
            ))
            .name("Retry Result Sink")
            .uid("retry-sink");

        log.info("Retry results will be written to Kafka: attribution-retry");

        // 9. 执行作业
        env.execute("Attribution Engine Job");
        
        log.info("Attribution Engine Job submitted successfully");
    }

    private static String getKafkaBootstrapServers() {
        String servers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (servers == null || servers.isEmpty()) {
            servers = "kafka:29092";
        }
        return servers;
    }
}
