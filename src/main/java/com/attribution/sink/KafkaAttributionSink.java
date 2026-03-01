package com.attribution.sink;

import com.attribution.model.AttributionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Kafka Sink - 将归因结果写入 Kafka
 * 
 * 支持两个 Topic：
 * - attribution-results-success: 成功的归因结果
 * - attribution-results-failed: 失败的归因结果
 * 
 * @author SimpleAttributeSystem
 * @version 2.0.0
 */
@Slf4j
public class KafkaAttributionSink extends RichSinkFunction<AttributionResult> {

    private static final long serialVersionUID = 1L;

    /**
     * 成功结果 Topic
     */
    private final String successTopic;

    /**
     * 失败结果 Topic
     */
    private final String failedTopic;

    /**
     * Kafka Bootstrap Servers
     */
    private final String bootstrapServers;

    /**
     * JSON 序列化器
     */
    private transient ObjectMapper objectMapper;

    /**
     * Kafka Producer
     */
    private transient KafkaProducer<String, String> producer;

    /**
     * 构造函数（默认 Topic）
     */
    public KafkaAttributionSink(String bootstrapServers) {
        this(
            bootstrapServers,
            "attribution-results-success",
            "attribution-results-failed"
        );
    }

    /**
     * 构造函数（自定义 Topic）
     */
    public KafkaAttributionSink(
        String bootstrapServers,
        String successTopic,
        String failedTopic
    ) {
        this.bootstrapServers = bootstrapServers;
        this.successTopic = successTopic;
        this.failedTopic = failedTopic;
        
        log.info("KafkaAttributionSink initialized: bootstrapServers={}, successTopic={}, failedTopic={}",
            bootstrapServers, successTopic, failedTopic);
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        log.info("Opening KafkaAttributionSink...");
        
        // 初始化 JSON 序列化器
        objectMapper = new ObjectMapper();
        
        // 配置 Kafka Producer
        Properties props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // 可靠性配置
        props.setProperty(ProducerConfig.ACKS_CONFIG, "all");  // 等待所有副本确认
        props.setProperty(ProducerConfig.RETRIES_CONFIG, "3");  // 重试次数
        props.setProperty(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "100");  // 重试间隔
        props.setProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000");  // 超时时间
        
        // 性能优化
        props.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "16384");  // 16KB batch
        props.setProperty(ProducerConfig.LINGER_MS_CONFIG, "1");  // 等待 1ms 凑 batch
        props.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");  // 32MB buffer
        
        // 幂等性（可选，需要 broker 支持）
        // props.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        
        producer = new KafkaProducer<>(props);
        
        log.info("Kafka Producer initialized successfully");
    }

    @Override
    public void invoke(AttributionResult result, Context context) throws Exception {
        if (result == null) {
            log.warn("Skipping null attribution result");
            return;
        }
        
        // 根据状态选择 Topic
        String topic = "SUCCESS".equals(result.getStatus()) ? successTopic : failedTopic;
        
        // 序列化结果
        String json = objectMapper.writeValueAsString(result);
        
        // 构建 Key（使用 userId 保证同一用户的消息有序）
        String key = result.getUserId() != null ? result.getUserId() : result.getResultId();
        
        // 发送消息
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        
        try {
            // 异步发送，获取 Future
            Future<RecordMetadata> future = producer.send(record);
            
            // 可选：等待确认（会影响吞吐量）
            // RecordMetadata metadata = future.get();
            // log.debug("Message sent: topic={}, partition={}, offset={}", 
            //     metadata.topic(), metadata.partition(), metadata.offset());
            
            log.debug("Sent attribution result to Kafka: topic={}, key={}, status={}",
                topic, key, result.getStatus());
            
        } catch (Exception e) {
            log.error("Failed to send message to Kafka: topic={}, key={}", topic, key, e);
            throw e;
        }
    }

    /**
     * 手动刷新（可选调用）
     */
    public void flush() {
        if (producer != null) {
            producer.flush();
            log.debug("Kafka producer flushed");
        }
    }

    @Override
    public void close() throws Exception {
        if (producer != null) {
            try {
                producer.flush();
                producer.close();
                log.info("Kafka Producer closed");
            } catch (Exception e) {
                log.error("Error closing Kafka Producer", e);
            }
        }
        super.close();
    }
}
