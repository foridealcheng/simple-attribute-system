package com.attribution.client;

import com.attribution.config.FlussSourceConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * KV Client 工厂
 * 
 * 根据配置创建不同的 KV Client 实现：
 * - fluss: FlussKVClient (本地缓存模式)
 * - redis: RedisKVClient
 * - fluss-cluster: FlussKVClient (Fluss 集群模式，TODO)
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class KVClientFactory {

    /**
     * Click 存储后端类型
     * 
     * 注意：LOCAL_CACHE 已移除，因为三个独立 JVM 进程需要共享存储
     */
    public enum KVType {
        FLUSS_CLUSTER,    // Fluss 集群模式
        REDIS            // Redis（默认）
    }

    /**
     * 创建 KV Client
     * 
     * @param type KV 存储类型
     * @param config 配置
     * @return KV Client
     */
    public static KVClient create(KVType type, FlussSourceConfig config) {
        log.info("Creating KV Client: type={}", type);
        
        switch (type) {
            case FLUSS_CLUSTER:
                return createFlussCluster(config);
                
            case REDIS:
                return createRedis(config);
                
            default:
                throw new IllegalArgumentException("Unknown KV type: " + type);
        }
    }

    /**
     * 创建默认 KV Client（从环境变量读取配置）
     * 
     * @return KV Client
     */
    public static KVClient createDefault() {
        FlussSourceConfig config = FlussSourceConfig.createDefault();
        return create(config);
    }

    /**
     * 创建 KV Client（从配置读取类型）
     * 
     * @param config 配置
     * @return KV Client
     */
    public static KVClient create(FlussSourceConfig config) {
        String storeType = config.getClickStoreType();
        
        if (storeType == null || storeType.isEmpty()) {
            storeType = "redis"; // 默认使用 Redis
        }
        
        KVType type;
        switch (storeType.toLowerCase()) {
            case "fluss-cluster":
            case "fluss":
                type = KVType.FLUSS_CLUSTER;
                break;
                
            case "redis":
                type = KVType.REDIS;
                break;
                
            default:
                throw new IllegalArgumentException(
                    "Unsupported Click Store type: " + storeType + 
                    ". Supported types: redis, fluss-cluster"
                );
        }
        
        return create(type, config);
    }

    /**
     * 创建 Fluss 集群模式（TODO）
     */
    private static KVClient createFlussCluster(FlussSourceConfig config) {
        log.warn("Fluss Cluster mode is not implemented yet");
        // TODO: 实现真正的 Fluss 集群客户端
        throw new UnsupportedOperationException(
            "Fluss Cluster mode is not implemented yet. Please use 'redis' mode."
        );
    }

    /**
     * 创建 Redis 客户端
     */
    private static KVClient createRedis(FlussSourceConfig config) {
        String host = config.getClickRedisHost();
        int port = config.getClickRedisPort();
        int ttl = config.getClickRedisTtlSeconds();
        
        log.info("Creating RedisKVClient: host={}, port={}, ttl={}s", host, port, ttl);
        return new RedisKVClient(host, port, ttl);
    }
}
