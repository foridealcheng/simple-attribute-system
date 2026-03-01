package com.attribution.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka Sink 配置
 * 
 * @author SimpleAttributeSystem
 * @version 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaSinkConfig {

    /**
     * Kafka Bootstrap Servers
     * Docker 环境：kafka:29092
     * 本地环境：localhost:9092
     */
    @Builder.Default
    private String bootstrapServers = "kafka:29092";

    /**
     * 成功结果 Topic
     */
    @Builder.Default
    private String successTopic = "attribution-results-success";

    /**
     * 失败结果 Topic
     */
    @Builder.Default
    private String failedTopic = "attribution-results-failed";

    /**
     * 是否启用幂等性
     */
    @Builder.Default
    private boolean enableIdempotence = false;

    /**
     * 重试次数
     */
    @Builder.Default
    private int retries = 3;

    /**
     * ACK 模式 (0, 1, all)
     */
    @Builder.Default
    private String acks = "all";

    /**
     * Batch Size (bytes)
     */
    @Builder.Default
    private int batchSize = 16384;

    /**
     * Linger MS (ms)
     */
    @Builder.Default
    private int lingerMs = 1;

    /**
     * Buffer Memory (bytes)
     */
    @Builder.Default
    private int bufferMemory = 33554432;

    /**
     * 创建默认配置（本地开发）
     */
    public static KafkaSinkConfig createDefault() {
        return KafkaSinkConfig.builder()
            .bootstrapServers("kafka:29092")  // Docker 环境
            .successTopic("attribution-results-success")
            .failedTopic("attribution-results-failed")
            .enableIdempotence(false)
            .retries(3)
            .acks("all")
            .batchSize(16384)
            .lingerMs(1)
            .bufferMemory(33554432)
            .build();
    }

    /**
     * 创建生产环境配置
     */
    public static KafkaSinkConfig createProduction(String bootstrapServers) {
        return KafkaSinkConfig.builder()
            .bootstrapServers(bootstrapServers)
            .successTopic("attribution-results-success")
            .failedTopic("attribution-results-failed")
            .enableIdempotence(true)
            .retries(5)
            .acks("all")
            .batchSize(32768)
            .lingerMs(5)
            .bufferMemory(67108864)
            .build();
    }
}
