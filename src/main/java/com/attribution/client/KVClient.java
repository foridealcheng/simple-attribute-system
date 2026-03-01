package com.attribution.client;

import com.attribution.model.ClickSession;

import java.util.List;
import java.util.Map;

/**
 * KV 存储客户端接口
 * 
 * 支持多种实现：
 * - FlussKVClient: Apache Fluss KV Store
 * - RedisKVClient: Redis
 * - LocalKVClient: 本地缓存（仅测试用）
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
public interface KVClient extends AutoCloseable {

    /**
     * 获取用户点击会话
     * 
     * @param userId 用户 ID
     * @return 点击会话，不存在返回 null
     */
    ClickSession get(String userId);

    /**
     * 保存用户点击会话
     * 
     * @param userId 用户 ID
     * @param session 点击会话
     */
    void put(String userId, ClickSession session);

    /**
     * 删除用户点击会话
     * 
     * @param userId 用户 ID
     */
    void delete(String userId);

    /**
     * 批量获取用户点击会话
     * 
     * @param userIds 用户 ID 列表
     * @return 点击会话 Map
     */
    Map<String, ClickSession> batchGet(List<String> userIds);

    /**
     * 批量保存用户点击会话
     * 
     * @param sessions 点击会话 Map
     */
    void batchPut(Map<String, ClickSession> sessions);

    /**
     * 获取统计信息
     * 
     * @return 统计信息
     */
    Map<String, Object> getStats();
}
