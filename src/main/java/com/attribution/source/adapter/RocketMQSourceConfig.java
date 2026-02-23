package com.attribution.source.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * RocketMQ 数据源配置
 * 
 * 支持 RocketMQ 5.0+ 消费者配置
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
public class RocketMQSourceConfig implements SourceConfig {

    public static final String SOURCE_TYPE = "ROCKETMQ";

    /**
     * 数据源名称（自定义标识）
     */
    private String sourceName;

    /**
     * NameServer 地址
     * 示例：192.168.1.1:9876;192.168.1.2:9876
     */
    private String nameServer;

    /**
     * Topic 名称
     */
    private String topic;

    /**
     * 消费者组名称
     */
    private String consumerGroup;

    /**
     * 数据格式：JSON, PROTOBUF, AVRO
     */
    private String format;

    /**
     * 额外配置属性
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();

    // ========== 安全配置 ==========

    /**
     * 访问密钥 ID
     */
    private String accessKey;

    /**
     * 访问密钥 Secret
     */
    private String secretKey;

    /**
     * 是否启用 ACL
     */
    @Builder.Default
    private Boolean enableACL = false;

    // ========== 消费者配置 ==========

    /**
     * 消息模型：CLUSTERING, BROADCASTING
     */
    @Builder.Default
    private String messageModel = "CLUSTERING";

    /**
     * 消费起始位置：CONSUME_FROM_LAST_OFFSET, CONSUME_FROM_FIRST_OFFSET
     */
    @Builder.Default
    private String consumeFromOffset = "CONSUME_FROM_LAST_OFFSET";

    /**
     * 每次拉取的最大消息数
     */
    @Builder.Default
    private Integer pullBatchSize = 32;

    /**
     * 消费者超时时间（毫秒）
     */
    @Builder.Default
    private Long consumerTimeoutMs = 30000L;

    /**
     * 消息监听器线程数
     */
    @Builder.Default
    private Integer consumeThreadMin = 20;

    @Builder.Default
    private Integer consumeThreadMax = 64;

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public boolean validate() {
        if (nameServer == null || nameServer.isEmpty()) {
            return false;
        }
        if (topic == null || topic.isEmpty()) {
            return false;
        }
        if (consumerGroup == null || consumerGroup.isEmpty()) {
            return false;
        }
        if (format == null || format.isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public Properties toProperties() {
        Properties props = new Properties();
        
        // 基础配置
        props.put("nameserver.address", nameServer);
        props.put("consumer.group", consumerGroup);
        props.put("consumer.topic", topic);
        props.put("consumer.pullBatchSize", pullBatchSize.toString());
        props.put("consumer.timeout.ms", consumerTimeoutMs.toString());
        props.put("consumer.thread.min", consumeThreadMin.toString());
        props.put("consumer.thread.max", consumeThreadMax.toString());
        
        // 消费模式
        props.put("consumer.message.model", messageModel);
        props.put("consumer.from.offset", consumeFromOffset);
        
        // 安全配置
        if (enableACL) {
            props.put("acl.enable", "true");
            if (accessKey != null) {
                props.put("acl.access.key", accessKey);
            }
            if (secretKey != null) {
                props.put("acl.secret.key", secretKey);
            }
        }
        
        // 额外配置
        props.putAll(properties);
        
        return props;
    }
}
