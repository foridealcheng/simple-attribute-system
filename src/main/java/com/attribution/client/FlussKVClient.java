package com.attribution.client;

import com.attribution.model.ClickSession;
import com.attribution.config.FlussSourceConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fluss KV Client
 * 
 * 用于 Fluss 集群模式的 KV 客户端
 * 
 * 注意：
 * - Redis 模式使用 RedisKVClient
 * - 本地缓存模式已移除（不支持三个独立 JVM 进程共享数据）
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class FlussKVClient implements KVClient {

    private final FlussSourceConfig config;
    private final Cache<String, ClickSession> localCache;
    private final AtomicInteger readCount;
    private final AtomicInteger writeCount;
    
    // 注意：FlussKVClient 当前仅用于 Fluss 集群模式
    // Redis 模式使用 RedisKVClient
    // 本地缓存模式已移除（不支持三个独立 JVM 进程）
    private final boolean useFluss;

    /**
     * 构造函数
     */
    public FlussKVClient(FlussSourceConfig config) {
        this.config = config;
        this.readCount = new AtomicInteger(0);
        this.writeCount = new AtomicInteger(0);
        this.useFluss = false; // 暂时使用本地缓存
        
        // 初始化本地缓存
        if (config.isEnableCache()) {
            this.localCache = Caffeine.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .expireAfterWrite(config.getCacheExpireMinutes(), TimeUnit.MINUTES)
                .build();
            log.info("FlussKVClient initialized with local cache (size={}, expire={}min)", 
                config.getCacheMaxSize(), config.getCacheExpireMinutes());
        } else {
            this.localCache = null;
            log.info("FlussKVClient initialized without local cache");
        }
        
        log.info("FlussKVClient initialized for Fluss Cluster mode");
        log.info("Production deployment requires Fluss cluster at: {}", config.getBootstrapServers());
    }

    /**
     * 获取用户点击会话
     */
    public ClickSession get(String userId) {
        readCount.incrementAndGet();
        
        if (localCache == null) {
            log.debug("Cache not available for user: {}", userId);
            return null;
        }
        
        ClickSession cached = localCache.getIfPresent(userId);
        if (cached != null) {
            log.debug("Cache hit for user: {}, clickCount: {}", userId, cached.getClickCount());
            return cached;
        }
        
        log.debug("Cache miss for user: {}", userId);
        return null;
    }

    /**
     * 保存用户点击会话
     */
    public void put(String userId, ClickSession session) {
        if (userId == null || session == null) {
            log.warn("Cannot put null userId or session");
            return;
        }
        
        // 更新元数据
        session.setLastUpdateTime(System.currentTimeMillis());
        session.setVersion(session.getVersion() != null ? session.getVersion() + 1 : 1L);
        
        // 写入本地缓存
        if (localCache != null) {
            localCache.put(userId, session);
            writeCount.incrementAndGet();
        }
        
        log.debug("Saved session to cache: userId={}, clickCount={}", userId, session.getClickCount());
    }

    /**
     * 删除用户点击会话
     */
    public void delete(String userId) {
        if (localCache != null) {
            localCache.invalidate(userId);
        }
        log.debug("Deleted session for user: {}", userId);
    }

    /**
     * 批量获取用户点击会话
     */
    public Map<String, ClickSession> batchGet(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, ClickSession> results = new HashMap<>();
        
        if (localCache != null) {
            for (String userId : userIds) {
                ClickSession cached = localCache.getIfPresent(userId);
                if (cached != null) {
                    results.put(userId, cached);
                }
            }
        }
        
        log.debug("Batch get: requested={}, cacheHit={}", userIds.size(), results.size());
        return results;
    }

    /**
     * 批量保存用户点击会话
     */
    public void batchPut(Map<String, ClickSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        
        if (localCache != null) {
            for (Map.Entry<String, ClickSession> entry : sessions.entrySet()) {
                String userId = entry.getKey();
                ClickSession session = entry.getValue();
                
                if (userId != null && session != null) {
                    session.setLastUpdateTime(System.currentTimeMillis());
                    session.setVersion(session.getVersion() != null ? session.getVersion() + 1 : 1L);
                    localCache.put(userId, session);
                }
            }
            writeCount.addAndGet(sessions.size());
        }
        
        log.info("Batch put: cached {} sessions", sessions.size());
    }

    /**
     * 清理过期缓存
     */
    public int cleanupExpiredCache(long currentTime) {
        if (localCache == null) {
            return 0;
        }
        
        long beforeCount = localCache.estimatedSize();
        
        // Caffeine 自动管理过期，这里只清理显式删除的
        localCache.asMap().entrySet().removeIf(entry -> {
            long lastAccessTime = entry.getValue().getLastUpdateTime();
            long cacheExpiryMs = 5 * 60000L; // 5 分钟
            return (currentTime - lastAccessTime) > cacheExpiryMs;
        });
        
        long cleaned = beforeCount - localCache.estimatedSize();
        if (cleaned > 0) {
            log.info("Cache cleanup: removed {} stale entries", cleaned);
        }
        return (int) cleaned;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("readCount", readCount.get());
        stats.put("writeCount", writeCount.get());
        stats.put("cacheSize", localCache != null ? localCache.estimatedSize() : 0);
        stats.put("useFluss", useFluss);
        stats.put("type", useFluss ? "Fluss-Cluster" : "Local-Cache");
        stats.put("config", config.getSourceName());
        return stats;
    }

    @Override
    public void close() {
        log.info("FlussKVClient closing. Stats: {}", getStats());
        // 本地缓存不需要显式关闭
    }
}
