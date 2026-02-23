package com.attribution.source.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Fluss 数据源配置
 * 
 * 支持 Apache Fluss 流数据源配置
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
public class FlussSourceConfig implements SourceConfig {

    public static final String SOURCE_TYPE = "FLUSS";

    /**
     * 数据源名称（自定义标识）
     */
    private String sourceName;

    /**
     * Fluss Gateway 地址
     * 示例：localhost:9110
     */
    private String gatewayAddress;

    /**
     * 数据库名称
     */
    private String databaseName;

    /**
     * 表名/Stream 名
     */
    private String tableName;

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
     * 是否启用 SSL
     */
    @Builder.Default
    private Boolean enableSSL = false;

    /**
     * SSL 信任证书路径
     */
    private String sslTruststorePath;

    /**
     * SSL 信任证书密码
     */
    private String sslTruststorePassword;

    // ========== 消费者配置 ==========

    /**
     * 扫描起始偏移量
     */
    private Long scanStartOffset;

    /**
     * 是否从最新位置开始消费
     */
    @Builder.Default
    private Boolean scanLatest = false;

    /**
     * 批处理大小
     */
    @Builder.Default
    private Integer batchSize = 100;

    /**
     * 批处理超时（毫秒）
     */
    @Builder.Default
    private Long batchTimeoutMs = 5000L;

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public boolean validate() {
        if (gatewayAddress == null || gatewayAddress.isEmpty()) {
            return false;
        }
        if (databaseName == null || databaseName.isEmpty()) {
            return false;
        }
        if (tableName == null || tableName.isEmpty()) {
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
        props.put("fluss.gateway.address", gatewayAddress);
        props.put("fluss.database.name", databaseName);
        props.put("fluss.table.name", tableName);
        props.put("fluss.batch.size", batchSize.toString());
        props.put("fluss.batch.timeout.ms", batchTimeoutMs.toString());
        
        // 扫描配置
        if (scanStartOffset != null) {
            props.put("fluss.scan.start.offset", scanStartOffset.toString());
        }
        props.put("fluss.scan.latest", scanLatest.toString());
        
        // SSL 配置
        props.put("fluss.ssl.enable", enableSSL.toString());
        if (enableSSL && sslTruststorePath != null) {
            props.put("fluss.ssl.truststore.path", sslTruststorePath);
            if (sslTruststorePassword != null) {
                props.put("fluss.ssl.truststore.password", sslTruststorePassword);
            }
        }
        
        // 额外配置
        props.putAll(properties);
        
        return props;
    }
}
