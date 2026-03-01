-- ============================================================================
-- Fluss Schema DDL - Attribution System v2.0.0
-- ============================================================================
-- 用于创建 Fluss KV Store 中的表结构
-- 执行方式：通过 Fluss CLI 或 Flink SQL Client
-- ============================================================================

-- ============================================================================
-- 1. 创建数据库
-- ============================================================================

CREATE DATABASE IF NOT EXISTS attribution_db;

USE attribution_db;

-- ============================================================================
-- 2. 用户点击会话表（KV Table）
-- ============================================================================
-- 用途：存储用户点击会话数据，支持归因计算
-- Key: user_id (STRING)
-- Value: 用户点击会话（包含点击历史、时间戳、版本等）
-- TTL: 24 小时（可配置）
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_click_sessions (
    -- Primary Key (用于 KV 查找)
    user_id STRING PRIMARY KEY NOT ENFORCED,
    
    -- 点击事件列表（嵌套 Row 类型）
    clicks ARRAY<ROW<
        event_id STRING,
        user_id STRING,
        timestamp BIGINT,
        advertiser_id STRING,
        campaign_id STRING,
        creative_id STRING,
        placement_id STRING,
        media_id STRING,
        click_type STRING,
        ip_address STRING,
        user_agent STRING,
        device_type STRING,
        os STRING,
        app_version STRING,
        attributes STRING,
        create_time BIGINT,
        source STRING
    >>,
    
    -- 会话元数据
    session_start_time BIGINT,      -- 会话开始时间（毫秒）
    last_update_time BIGINT,        -- 最后更新时间（毫秒）
    click_count INT,                -- 点击数量
    version BIGINT                  -- 版本号（用于乐观锁）
    
    -- 注意：ttl_timestamp 字段已移除
    -- TTL 由 Fluss 表级别自动管理 (log.ttl.ms)
    
) WITH (
    'connector' = 'fluss',
    'table-type' = 'PRIMARY_KEY',
    'database-name' = 'attribution_db',
    'bucket.num' = '10',             -- Bucket 数量（根据并发度调整）
    'log.ttl.ms' = '86400000'        -- 表级别 TTL: 24 小时 (自动清理过期数据)
);

-- ============================================================================
-- 3. 索引和查询说明
-- ============================================================================

-- Primary Key 索引：
-- - user_id: 用于快速查找用户会话（KV Get/Put）
-- 
-- 查询模式：
-- 1. 单用户查询：SELECT * FROM user_click_sessions WHERE user_id = 'user_123'
-- 2. 批量查询：  通过 Fluss KV Client 的 batchGet 方法
-- 3. 全表扫描：  不推荐（用于清理过期数据）

-- ============================================================================
-- 4. 数据生命周期管理
-- ============================================================================

-- TTL 策略：
-- - 每个会话设置 ttl_timestamp 字段
-- - 读取时检查是否过期，过期则删除
-- - 定期清理任务扫描并删除过期数据
-- 
-- 清理 SQL（可选，用于手动清理）：
-- DELETE FROM user_click_sessions 
-- WHERE ttl_timestamp < CURRENT_TIMESTAMP;

-- ============================================================================
-- 5. 性能优化建议
-- ============================================================================

-- Bucket 配置：
-- - 默认 10 个 Bucket
-- - 根据 Flink 并行度调整（建议：Bucket 数 = 并行度 * 2）
-- - 修改：ALTER TABLE user_click_sessions SET ('bucket.num' = '20');
--
-- 压缩策略：
-- - Fluss 自动压缩历史版本
-- - 定期 Compaction 优化存储
--
-- 缓存建议：
-- - 应用层实现 LRU 缓存（Caffeine）
-- - 缓存热点用户数据（5 分钟过期）

-- ============================================================================
-- 6. 监控指标
-- ============================================================================

-- 关键指标：
-- - 表大小：SELECT COUNT(*) FROM user_click_sessions
-- - 平均点击数：SELECT AVG(click_count) FROM user_click_sessions
-- - 过期数据比例：SELECT COUNT(*) FROM user_click_sessions WHERE ttl_timestamp < CURRENT_TIMESTAMP
--
-- Fluss 内置指标：
-- - fluss_tablet_server_tablet_count
-- - fluss_tablet_server_write_qps
-- - fluss_tablet_server_read_qps

-- ============================================================================
-- 7. 备份和恢复
-- ============================================================================

-- 备份（Fluss 原生支持）：
-- 1. 启用 Snapshot 功能
-- 2. 配置 S3/HDFS 存储路径
-- 3. 定期自动备份
--
-- 恢复：
-- 1. 从 Snapshot 恢复
-- 2. 验证数据完整性

-- ============================================================================
-- End of Schema DDL
-- ============================================================================
