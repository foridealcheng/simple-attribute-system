package com.attribution.source.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 数据源配置
 * 
 * 支持 Kafka 消费者配置，包括安全认证、Schema Registry 等
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
public class KafkaSourceConfig implements SourceConfig {

    public static final String SOURCE_TYPE = "KAFKA";

    /**
     * 数据源名称（自定义标识）
     */
    private String sourceName;

    /**
     * Kafka Bootstrap Servers
     * 示例：localhost:9092,localhost:9093
     */
    private String bootstrapServers;

    /**
     * Topic 名称
     */
    private String topic;

    /**
     * 消费者组 ID
     */
    private String groupId;

    /**
     * 数据格式：JSON, PROTOBUF, AVRO
     */
    private String format;

    /**
     * 额外配置属性
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();

    // ========== Kafka 安全配置 ==========

    /**
     * 安全协议：PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL
     */
    private String securityProtocol;

    /**
     * SASL 机制：PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER
     */
    private String saslMechanism;

    /**
     * SASL JAAS 配置
     */
    private String saslJaasConfig;

    // ========== Schema Registry 配置 ==========

    /**
     * Schema Registry URL
     */
    private String schemaRegistryUrl;

    /**
     * Schema Registry 用户名
     */
    private String schemaRegistryUsername;

    /**
     * Schema Registry 密码
     */
    private String schemaRegistryPassword;

    // ========== 消费者配置 ==========

    /**
     * 自动提交偏移量
     */
    @Builder.Default
    private Boolean enableAutoCommit = false;

    /**
     * 每次拉取的最大消息数
     */
    @Builder.Default
    private Integer maxPollRecords = 100;

    /**
     * 消费者超时时间（毫秒）
     */
    @Builder.Default
    private Long sessionTimeoutMs = 30000L;

    /**
     * 心跳间隔（毫秒）
     */
    @Builder.Default
    private Long heartbeatIntervalMs = 10000L;

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public boolean validate() {
        if (bootstrapServers == null || bootstrapServers.isEmpty()) {
            return false;
        }
        if (topic == null || topic.isEmpty()) {
            return false;
        }
        if (groupId == null || groupId.isEmpty()) {
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
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("enable.auto.commit", enableAutoCommit.toString());
        props.put("max.poll.records", maxPollRecords.toString());
        props.put("session.timeout.ms", sessionTimeoutMs.toString());
        props.put("heartbeat.interval.ms", heartbeatIntervalMs.toString());
        
        // 安全配置
        if (securityProtocol != null) {
            props.put("security.protocol", securityProtocol);
        }
        if (saslMechanism != null) {
            props.put("sasl.mechanism", saslMechanism);
        }
        if (saslJaasConfig != null) {
            props.put("sasl.jaas.config", saslJaasConfig);
        }
        
        // Schema Registry 配置
        if (schemaRegistryUrl != null) {
            props.put("schema.registry.url", schemaRegistryUrl);
        }
        if (schemaRegistryUsername != null) {
            props.put("basic.auth.username", schemaRegistryUsername);
        }
        if (schemaRegistryPassword != null) {
            props.put("basic.auth.password", schemaRegistryPassword);
        }
        
        // 额外配置
        props.putAll(properties);
        
        return props;
    }
}
