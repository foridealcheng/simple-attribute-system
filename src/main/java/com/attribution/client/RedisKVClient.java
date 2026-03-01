package com.attribution.client;

import com.attribution.model.ClickSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis KV Client
 * 
 * 使用 Redis 作为共享 KV 存储
 * 
 * Key 格式：click:{user_id}
 * Value: ClickSession (JSON)
 * TTL: 1 小时（可配置）
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class RedisKVClient implements KVClient {

    private final Jedis jedis;
    private final String keyPrefix;
    private final int ttlSeconds;
    private final ObjectMapper objectMapper;
    
    private final AtomicInteger readCount;
    private final AtomicInteger writeCount;
    private final AtomicInteger deleteCount;

    /**
     * 构造函数
     * 
     * @param host Redis 主机
     * @param port Redis 端口
     * @param ttlSeconds TTL（秒）
     */
    public RedisKVClient(String host, int port, int ttlSeconds) {
        this.keyPrefix = "click:";
        this.ttlSeconds = ttlSeconds;
        this.objectMapper = new ObjectMapper();
        this.readCount = new AtomicInteger(0);
        this.writeCount = new AtomicInteger(0);
        this.deleteCount = new AtomicInteger(0);
        
        // 创建 Jedis 实例并测试连接
        this.jedis = new Jedis(host, port);
        try {
            this.jedis.connect();
            String pong = this.jedis.ping();
            log.info("RedisKVClient initialized and connected: host={}, port={}, ttl={}s, ping={}", 
                host, port, ttlSeconds, pong);
        } catch (Exception e) {
            log.error("Failed to connect to Redis: host={}, port={}", host, port, e);
            throw e;
        }
    }

    /**
     * 构造函数（使用默认配置）
     * 
     * @param host Redis 主机
     * @param port Redis 端口
     */
    public RedisKVClient(String host, int port) {
        this(host, port, 3600); // 默认 1 小时
    }

    @Override
    public ClickSession get(String userId) {
        if (userId == null) {
            return null;
        }
        
        readCount.incrementAndGet();
        
        try {
            String key = keyPrefix + userId;
            String json = jedis.get(key);
            
            if (json == null || json.isEmpty()) {
                log.debug("Cache miss for user: {}", userId);
                return null;
            }
            
            ClickSession session = objectMapper.readValue(json, ClickSession.class);
            log.debug("Cache hit for user: {}, clickCount: {}", userId, session.getClickCount());
            
            return session;
            
        } catch (Exception e) {
            log.error("Failed to get from Redis: userId={}", userId, e);
            return null;
        }
    }

    @Override
    public void put(String userId, ClickSession session) {
        if (userId == null || session == null) {
            log.warn("Cannot put null userId or session");
            return;
        }
        
        try {
            // 更新元数据
            session.setLastUpdateTime(System.currentTimeMillis());
            session.setVersion(session.getVersion() != null ? session.getVersion() + 1 : 1L);
            
            // 序列化为 JSON
            String json = objectMapper.writeValueAsString(session);
            
            // 写入 Redis
            String key = keyPrefix + userId;
            jedis.setex(key, ttlSeconds, json);
            
            writeCount.incrementAndGet();
            log.debug("Saved session to Redis: userId={}, clickCount={}, ttl={}s", 
                userId, session.getClickCount(), ttlSeconds);
            
        } catch (Exception e) {
            log.error("Failed to put to Redis: userId={}", userId, e);
        }
    }

    @Override
    public void delete(String userId) {
        if (userId == null) {
            return;
        }
        
        try {
            String key = keyPrefix + userId;
            jedis.del(key);
            deleteCount.incrementAndGet();
            log.debug("Deleted session for user: {}", userId);
            
        } catch (Exception e) {
            log.error("Failed to delete from Redis: userId={}", userId, e);
        }
    }

    @Override
    public Map<String, ClickSession> batchGet(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, ClickSession> results = new HashMap<>();
        
        try {
            // 使用 pipeline 批量获取
            List<String> keys = new ArrayList<>();
            for (String userId : userIds) {
                keys.add(keyPrefix + userId);
            }
            
            List<String> jsonList = jedis.mget(keys.toArray(new String[0]));
            
            for (int i = 0; i < userIds.size(); i++) {
                String userId = userIds.get(i);
                String json = jsonList.get(i);
                
                if (json != null && !json.isEmpty()) {
                    try {
                        ClickSession session = objectMapper.readValue(json, ClickSession.class);
                        results.put(userId, session);
                    } catch (Exception e) {
                        log.warn("Failed to parse session for user: {}", userId, e);
                    }
                }
            }
            
            log.debug("Batch get: requested={}, cacheHit={}", userIds.size(), results.size());
            
        } catch (Exception e) {
            log.error("Failed to batch get from Redis", e);
        }
        
        return results;
    }

    @Override
    public void batchPut(Map<String, ClickSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        
        try {
            // 使用 pipeline 批量写入
            Pipeline pipeline = jedis.pipelined();
            
            for (Map.Entry<String, ClickSession> entry : sessions.entrySet()) {
                String userId = entry.getKey();
                ClickSession session = entry.getValue();
                
                if (userId != null && session != null) {
                    // 更新元数据
                    session.setLastUpdateTime(System.currentTimeMillis());
                    session.setVersion(session.getVersion() != null ? session.getVersion() + 1 : 1L);
                    
                    try {
                        String json = objectMapper.writeValueAsString(session);
                        String key = keyPrefix + userId;
                        pipeline.setex(key, ttlSeconds, json);
                    } catch (Exception e) {
                        log.warn("Failed to serialize session for user: {}", userId, e);
                    }
                }
            }
            
            pipeline.sync();
            writeCount.addAndGet(sessions.size());
            log.info("Batch put: saved {} sessions", sessions.size());
            
        } catch (Exception e) {
            log.error("Failed to batch put to Redis", e);
        }
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("readCount", readCount.get());
        stats.put("writeCount", writeCount.get());
        stats.put("deleteCount", deleteCount.get());
        stats.put("type", "Redis");
        stats.put("storeType", "redis");
        stats.put("keyPrefix", keyPrefix);
        stats.put("ttlSeconds", ttlSeconds);
        
        // 获取 Redis 信息
        try {
            String info = jedis.info("stats");
            stats.put("redis.info", info.substring(0, Math.min(200, info.length())) + "...");
        } catch (Exception e) {
            // 忽略
        }
        
        return stats;
    }

    @Override
    public void close() {
        log.info("RedisKVClient closing. Stats: {}", getStats());
        if (jedis != null) {
            jedis.close();
        }
    }
}
