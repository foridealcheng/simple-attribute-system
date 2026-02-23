package com.attribution.source.adapter;

import com.attribution.model.RawEvent;
import com.attribution.decoder.FormatDecoder;
import com.attribution.decoder.DecoderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;

/**
 * Kafka 数据源适配器实现
 * 
 * 从 Kafka Topic 消费消息，转换为 RawEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class KafkaSourceAdapter implements SourceAdapter<ConsumerRecord<String, byte[]>> {

    private KafkaConsumer<String, byte[]> consumer;
    private KafkaSourceConfig config;
    private FormatDecoder decoder;
    private volatile boolean running = false;

    @Override
    public void init(SourceConfig sourceConfig) {
        log.info("Initializing Kafka Source Adapter...");
        
        this.config = (KafkaSourceConfig) sourceConfig;
        
        if (!config.validate()) {
            throw new IllegalArgumentException("Invalid Kafka source configuration");
        }
        
        // 初始化 Kafka 消费者
        Properties props = config.toProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(config.getTopic()));
        
        // 初始化格式解码器
        this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
        
        log.info("Kafka Source Adapter initialized successfully");
        log.info("  Bootstrap Servers: {}", config.getBootstrapServers());
        log.info("  Topic: {}", config.getTopic());
        log.info("  Consumer Group: {}", config.getGroupId());
        log.info("  Format: {}", config.getFormat());
    }

    @Override
    public Iterable<RawEvent> consume() {
        if (!running) {
            running = true;
        }
        
        return () -> {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
            
            return StreamSupport.stream(records.spliterator(), false)
                .map(record -> {
                    try {
                        RawEvent event = decoder.decode(record.value());
                        event.setMetadata(extractMetadata(record));
                        return event;
                    } catch (Exception e) {
                        log.error("Failed to decode message from partition {} offset {}", 
                            record.partition(), record.offset(), e);
                        return null;
                    }
                })
                .filter(event -> event != null)
                .iterator();
        };
    }

    @Override
    public void commitOffset(Map<String, Long> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        
        log.debug("Committing offsets: {}", offsets);
        // Kafka 可以自动提交，也可以手动提交
        // 这里使用手动提交
        consumer.commitSync();
    }

    @Override
    public String getSourceType() {
        return KafkaSourceConfig.SOURCE_TYPE;
    }

    @Override
    public void close() {
        log.info("Closing Kafka Source Adapter...");
        running = false;
        
        if (consumer != null) {
            try {
                consumer.commitSync();
                consumer.close();
            } catch (Exception e) {
                log.error("Error closing Kafka consumer", e);
            }
        }
        
        log.info("Kafka Source Adapter closed");
    }

    @Override
    public boolean isHealthy() {
        if (consumer == null) {
            return false;
        }
        
        try {
            consumer.listTopics();
            return true;
        } catch (Exception e) {
            log.warn("Kafka consumer health check failed", e);
            return false;
        }
    }

    /**
     * 提取消息元数据
     */
    private Map<String, Object> extractMetadata(ConsumerRecord<String, byte[]> record) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "KAFKA");
        metadata.put("topic", record.topic());
        metadata.put("partition", record.partition());
        metadata.put("offset", record.offset());
        metadata.put("key", record.key());
        metadata.put("timestamp", record.timestamp());
        return metadata;
    }
}
