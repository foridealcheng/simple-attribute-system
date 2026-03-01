package com.attribution.job;

import com.attribution.client.KVClient;
import com.attribution.client.KVClientFactory;
import com.attribution.config.KafkaSinkConfig;
import com.attribution.flink.RetryProcessFunction;
import com.attribution.model.AttributionResult;
import com.attribution.model.RetryMessage;
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
 * Retry Consumer Job - Flink 实现 (使用 Kafka)
 * 
 * 职责:
 * 1. 从 Kafka 消费重试消息
 * 2. 查询 KV Store 获取 Click 历史
 * 3. 重新执行归因计算
 * 4. 成功结果输出到 Kafka
 * 5. 失败结果重新发送到 Kafka Retry 或 DLQ
 * 
 * 架构说明:
 * - AttributionEngineJob: 发送失败结果到 Kafka retry topic
 * - RetryConsumerJob: 从 Kafka 消费重试消息（Flink 处理）
 * - 纯 Kafka 方案，架构简洁
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class RetryConsumerJob {

    public static void main(String[] args) throws Exception {
        log.info("Starting Retry Consumer Job...");

        // 1. 创建 Flink 执行环境
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        
        // 启用 Checkpoint
        env.enableCheckpointing(60000); // 60 秒

        // 2. 从 Kafka 消费重试消息
        String kafkaServers = getKafkaBootstrapServers();
        
        KafkaSource<String> retrySource = KafkaSource.<String>builder()
            .setBootstrapServers(kafkaServers)
            .setTopics("attribution-retry")
            .setGroupId("retry-consumer-job-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        // 3. 创建重试消息数据流
        ObjectMapper mapper = new ObjectMapper();
        
        DataStream<RetryMessage> retryStream = env.fromSource(
                retrySource, 
                WatermarkStrategy.noWatermarks(), 
                "Kafka Retry Source"
            )
            .map(json -> {
                try {
                    return mapper.readValue(json, RetryMessage.class);
                } catch (Exception e) {
                    log.error("Failed to parse RetryMessage JSON: {}", json, e);
                    return null;
                }
            })
            .filter(msg -> msg != null)
            .name("Retry Message Mapper")
            .uid("retry-mapper");

        // 4. 初始化 KV Client（在 ProcessFunction 的 open() 中创建）
        // 5. 重新处理归因
        DataStream<AttributionResult> resultStream = retryStream
            .keyBy(RetryMessage::getUserId)
            .process(new RetryProcessFunction(
                "LAST_CLICK",
                604800000L,  // 7 天窗口
                50,          // 最大 Click 数
                10           // 最大重试次数
            ))
            .name("Retry Processor")
            .uid("retry-processor");

        // 6. 分流：成功 vs 失败 vs 重试
        DataStream<AttributionResult> successResults = resultStream
            .filter(result -> "SUCCESS".equals(result.getStatus()))
            .name("Success Filter")
            .uid("success-filter");

        DataStream<AttributionResult> dlqResults = resultStream
            .filter(result -> "FAILED".equals(result.getStatus()))
            .name("DLQ Filter")
            .uid("dlq-filter");

        DataStream<AttributionResult> retryResults = resultStream
            .filter(result -> "RETRY".equals(result.getStatus()))
            .name("Retry Filter")
            .uid("retry-filter");

        // 7. 成功结果 → Kafka
        KafkaSinkConfig kafkaConfig = KafkaSinkConfig.createDefault();
        successResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                kafkaConfig.getSuccessTopic(),
                "attribution-retry-dlq"
            ))
            .name("Success Result Sink")
            .uid("success-sink");

        log.info("Success results will be written to Kafka: {}", kafkaConfig.getSuccessTopic());

        // 8. DLQ 结果 → Kafka DLQ Topic
        dlqResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                "attribution-retry-dlq",
                "attribution-retry-dlq"
            ))
            .name("DLQ Result Sink")
            .uid("dlq-sink");

        log.info("DLQ results will be written to Kafka: attribution-retry-dlq");

        // 9. 重试结果 → Kafka Retry Topic
        retryResults
            .addSink(new KafkaAttributionSink(
                kafkaConfig.getBootstrapServers(),
                "attribution-retry",
                "attribution-retry"
            ))
            .name("Retry Result Sink")
            .uid("retry-sink");

        log.info("Retry results will be written back to Kafka: attribution-retry");

        // 10. 执行作业
        env.execute("Retry Consumer Job");
        
        log.info("Retry Consumer Job submitted successfully");
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
