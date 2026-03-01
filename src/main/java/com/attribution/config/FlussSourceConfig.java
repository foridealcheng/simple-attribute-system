package com.attribution.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Fluss 数据源配置
 * 
 * 用于配置 Fluss KV Client 连接参数
 * 
 * @author SimpleAttributeSystem
 * @version 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlussSourceConfig {

    private static final long serialVersionUID = 1L;

    /**
     * 数据源类型
     */
    private static final String SOURCE_TYPE = "FLUSS";

    /**
     * Fluss 服务器地址列表（逗号分隔）
     * 示例："localhost:9092,localhost:9093,localhost:9094"
     */
    @Builder.Default
    private String bootstrapServers = "localhost:9092";

    /**
     * 数据库名称
     */
    @Builder.Default
    private String database = "attribution_db";

    /**
     * 表名称
     */
    @Builder.Default
    private String table = "user_click_sessions";

    /**
     * 连接超时（毫秒）
     */
    @Builder.Default
    private long connectionTimeoutMs = 30000L;

    /**
     * 请求超时（毫秒）
     */
    @Builder.Default
    private long requestTimeoutMs = 10000L;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxRetries = 3;

    /**
     * 重试间隔（毫秒）
     */
    @Builder.Default
    private long retryIntervalMs = 100L;

    /**
     * 批量操作最大数量
     */
    @Builder.Default
    private int batchSize = 100;

    /**
     * 是否启用本地缓存
     */
    @Builder.Default
    private boolean enableCache = true;

    /**
     * 缓存最大大小
     */
    @Builder.Default
    private int cacheMaxSize = 10000;

    /**
     * 缓存过期时间（分钟）
     */
    @Builder.Default
    private int cacheExpireMinutes = 5;

    /**
     * Click 存储后端类型
     * - redis: Redis（默认）
     * - fluss-cluster: Fluss 集群模式
     * 
     * 注意：不支持本地缓存模式，因为 Click Writer、Attribution Engine、
     * Retry Consumer 是三个独立的 JVM 进程，需要共享存储
     */
    @Builder.Default
    private String clickStoreType = "redis"; // 默认使用 Redis

    /**
     * Redis 主机（当 clickStoreType=redis 时）
     * 优先级：环境变量 REDIS_HOST > 默认值 "redis"
     */
    @Builder.Default
    private String clickRedisHost = getRedisHostFromEnv();

    /**
     * Redis 端口（当 clickStoreType=redis 时）
     */
    @Builder.Default
    private int clickRedisPort = 6379;

    /**
     * Redis TTL（秒，当 clickStoreType=redis 时）
     */
    @Builder.Default
    private int clickRedisTtlSeconds = 3600;

    /**
     * 额外配置属性
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();

    
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    
    public String getSourceName() {
        return "Fluss-KV-" + database + "-" + table;
    }

    
    public String getFormat() {
        return "FLUSS_NATIVE";
    }

    
    public String getTopic() {
        return table;
    }

    
    public Map<String, String> getProperties() {
        return properties;
    }

    
    public boolean validate() {
        if (bootstrapServers == null || bootstrapServers.trim().isEmpty()) {
            return false;
        }
        if (database == null || database.trim().isEmpty()) {
            return false;
        }
        if (table == null || table.trim().isEmpty()) {
            return false;
        }
        return true;
    }

    
    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", bootstrapServers);
        props.setProperty("database.name", database);
        props.setProperty("table.name", table);
        props.setProperty("connection.timeout.ms", String.valueOf(connectionTimeoutMs));
        props.setProperty("request.timeout.ms", String.valueOf(requestTimeoutMs));
        props.setProperty("max.retries", String.valueOf(maxRetries));
        props.setProperty("retry.interval.ms", String.valueOf(retryIntervalMs));
        props.setProperty("batch.size", String.valueOf(batchSize));
        props.setProperty("cache.enable", String.valueOf(enableCache));
        props.setProperty("cache.max.size", String.valueOf(cacheMaxSize));
        props.setProperty("cache.expire.minutes", String.valueOf(cacheExpireMinutes));
        
        // Click Store 配置
        props.setProperty("click.store.type", clickStoreType);
        if ("redis".equalsIgnoreCase(clickStoreType)) {
            props.setProperty("click.redis.host", clickRedisHost);
            props.setProperty("click.redis.port", String.valueOf(clickRedisPort));
            props.setProperty("click.redis.ttl.seconds", String.valueOf(clickRedisTtlSeconds));
        }
        
        // 添加额外属性
        if (properties != null) {
            properties.forEach(props::setProperty);
        }
        
        return props;
    }

    /**
     * 获取客户端 ID
     * 
     * @return 客户端标识
     */
    public String getClientId() {
        return "attribution-fluss-client-" + System.currentTimeMillis();
    }

    /**
     * 添加配置属性
     * 
     * @param key 属性名
     * @param value 属性值
     */
    public void addProperty(String key, String value) {
        if (this.properties == null) {
            this.properties = new HashMap<>();
        }
        this.properties.put(key, value);
    }

    /**
     * 从环境变量获取 Redis 主机名
     */
    private static String getRedisHostFromEnv() {
        String envHost = System.getenv("REDIS_HOST");
        return (envHost != null && !envHost.isEmpty()) ? envHost : "redis";
    }

    /**
     * 创建默认配置（本地开发环境，使用 Redis）
     * 
     * @return 默认配置
     */
    public static FlussSourceConfig createDefault() {
        return FlussSourceConfig.builder()
            .bootstrapServers("localhost:9092")
            .database("attribution_db")
            .table("user_click_sessions")
            .connectionTimeoutMs(30000L)
            .requestTimeoutMs(10000L)
            .maxRetries(3)
            .retryIntervalMs(100L)
            .batchSize(100)
            .enableCache(true)
            .cacheMaxSize(10000)
            .cacheExpireMinutes(5)
            .clickStoreType("redis")  // 默认使用 Redis
            .clickRedisHost(getRedisHostFromEnv())  // 从环境变量读取
            .clickRedisPort(6379)
            .clickRedisTtlSeconds(3600)
            .build();
    }

    /**
     * 创建生产环境配置
     * 
     * @param bootstrapServers 服务器地址
     * @return 生产配置
     */
    public static FlussSourceConfig createProduction(String bootstrapServers) {
        return FlussSourceConfig.builder()
            .bootstrapServers(bootstrapServers)
            .database("attribution_db")
            .table("user_click_sessions")
            .connectionTimeoutMs(60000L)
            .requestTimeoutMs(30000L)
            .maxRetries(5)
            .retryIntervalMs(200L)
            .batchSize(200)
            .enableCache(true)
            .cacheMaxSize(50000)
            .cacheExpireMinutes(10)
            .build();
    }
}
