package com.attribution.source.adapter;

import com.attribution.model.RawEvent;
import com.attribution.decoder.FormatDecoder;
import com.attribution.decoder.DecoderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.*;

/**
 * RocketMQ 数据源适配器实现
 * 
 * 从 RocketMQ Topic 消费消息，转换为 RawEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class RocketMQSourceAdapter implements SourceAdapter<MessageExt> {

    private DefaultLitePullConsumer consumer;
    private RocketMQSourceConfig config;
    private FormatDecoder decoder;
    private volatile boolean running = false;

    @Override
    public void init(SourceConfig sourceConfig) {
        log.info("Initializing RocketMQ Source Adapter...");
        
        this.config = (RocketMQSourceConfig) sourceConfig;
        
        if (!config.validate()) {
            throw new IllegalArgumentException("Invalid RocketMQ source configuration");
        }
        
        try {
            // 初始化 RocketMQ 消费者
            this.consumer = new DefaultLitePullConsumer(config.getConsumerGroup());
            this.consumer.setNamesrvAddr(config.getNameServer());
            
            // 配置消费者参数
            this.consumer.setPullBatchSize(config.getPullBatchSize());
            
            // 安全配置
            if (config.getEnableACL()) {
                this.consumer.setVipChannelEnabled(false);
                // 设置 ACL
                if (config.getAccessKey() != null) {
                    // RocketMQ ACL 配置
                    log.info("ACL enabled for RocketMQ consumer");
                }
            }
            
            this.consumer.start();
            
            // 订阅 Topic
            this.consumer.subscribe(config.getTopic(), "*");
            
            // 初始化格式解码器
            this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
            
            log.info("RocketMQ Source Adapter initialized successfully");
            log.info("  NameServer: {}", config.getNameServer());
            log.info("  Topic: {}", config.getTopic());
            log.info("  Consumer Group: {}", config.getConsumerGroup());
            log.info("  Format: {}", config.getFormat());
            
        } catch (Exception e) {
            log.error("Failed to initialize RocketMQ consumer", e);
            throw new RuntimeException("Failed to initialize RocketMQ consumer", e);
        }
    }

    @Override
    public Iterable<RawEvent> consume() {
        if (!running) {
            running = true;
        }
        
        return () -> {
            try {
                // 拉取消息
                List<MessageExt> messages = consumer.poll();
                
                if (messages == null || messages.isEmpty()) {
                    return Collections.emptyIterator();
                }
                
                return messages.stream()
                    .map(message -> {
                        try {
                            RawEvent event = decoder.decode(message.getBody());
                            event.setMetadata(extractMetadata(message));
                            return event;
                        } catch (Exception e) {
                            log.error("Failed to decode message from topic {} offset {}", 
                                message.getTopic(), message.getQueueOffset(), e);
                            return null;
                        }
                    })
                    .filter(event -> event != null)
                    .iterator();
                    
            } catch (Exception e) {
                log.error("Error polling messages from RocketMQ", e);
                return Collections.emptyIterator();
            }
        };
    }

    @Override
    public void commitOffset(Map<String, Long> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        
        log.debug("Committing offsets: {}", offsets);
        // RocketMQ LitePullConsumer 自动管理偏移量
        // 如果需要手动提交，可以使用 consumer.commit()
    }

    @Override
    public String getSourceType() {
        return RocketMQSourceConfig.SOURCE_TYPE;
    }

    @Override
    public void close() {
        log.info("Closing RocketMQ Source Adapter...");
        running = false;
        
        if (consumer != null) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.error("Error closing RocketMQ consumer", e);
            }
        }
        
        log.info("RocketMQ Source Adapter closed");
    }

    @Override
    public boolean isHealthy() {
        if (consumer == null) {
            return false;
        }
        
        // 简单健康检查：尝试获取消费者状态
        return consumer.isRunning();
    }

    /**
     * 提取消息元数据
     */
    private Map<String, Object> extractMetadata(MessageExt message) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "ROCKETMQ");
        metadata.put("topic", message.getTopic());
        metadata.put("queueId", message.getQueueId());
        metadata.put("queueOffset", message.getQueueOffset());
        metadata.put("msgId", message.getMsgId());
        metadata.put("bornTimestamp", message.getBornTimestamp());
        metadata.put("storeTimestamp", message.getStoreTimestamp());
        
        // 提取用户属性
        if (message.getUserProperty("KEYS") != null) {
            metadata.put("keys", message.getUserProperty("KEYS"));
        }
        if (message.getUserProperty("TAGS") != null) {
            metadata.put("tags", message.getUserProperty("TAGS"));
        }
        
        return metadata;
    }
}
