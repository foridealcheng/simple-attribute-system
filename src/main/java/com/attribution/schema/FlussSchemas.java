package com.attribution.schema;

import java.util.Arrays;
import java.util.List;

/**
 * Fluss Schema 定义
 * 
 * 定义 Fluss KV Store 中的表结构和字段类型
 * 
 * @author SimpleAttributeSystem
 * @version 2.0.0
 */
public class FlussSchemas {

    // ==================== 数据库和表名 ====================

    /**
     * 数据库名称
     */
    public static final String DATABASE_NAME = "attribution_db";

    /**
     * 用户点击会话表（KV Table）
     * Key: user_id (STRING)
     * Value: 用户点击会话数据
     */
    public static final String TABLE_USER_CLICK_SESSIONS = "user_click_sessions";

    // ==================== Schema 字段定义 ====================

    /**
     * user_click_sessions 表的字段名
     * 注意：TTL 由 Fluss 表级别配置自动管理 (log.ttl.ms)
     */
    public static final List<String> USER_CLICK_SESSION_FIELDS = Arrays.asList(
        "user_id",              // 用户 ID（Primary Key）
        "clicks",               // 点击事件列表（ARRAY<ROW>）
        "session_start_time",   // 会话开始时间（BIGINT）
        "last_update_time",     // 最后更新时间（BIGINT）
        "click_count",          // 点击数量（INT）
        "version"               // 版本号（BIGINT，用于乐观锁）
        // 注意：ttl_timestamp 字段已移除，使用表级别 TTL
    );

    /**
     * Click 事件 Row 的字段名
     */
    public static final List<String> CLICK_EVENT_FIELDS = Arrays.asList(
        "event_id",
        "user_id",
        "timestamp",
        "advertiser_id",
        "campaign_id",
        "creative_id",
        "placement_id",
        "media_id",
        "click_type",
        "ip_address",
        "user_agent",
        "device_type",
        "os",
        "app_version",
        "attributes",
        "create_time",
        "source"
    );

    // ==================== Flink TypeInformation ====================
    // 注意：Fluss KV Client 直接使用 Java 对象序列化，不需要 Flink TypeInformation
    // 这些字段定义用于文档和验证目的

    // ==================== 配置常量 ====================

    /**
     * 表级别 TTL（毫秒）- 24 小时
     * 由 Fluss 自动管理，不需要应用层处理
     */
    public static final long TABLE_LOG_TTL_MS = 24L * 3600000L; // 86400000ms

    /**
     * 默认归因窗口（小时）
     */
    public static final long DEFAULT_ATTRIBUTION_WINDOW_HOURS = 24L;

    /**
     * 最大点击存储数
     */
    public static final int MAX_CLICKS = 50;

    /**
     * 默认 Bucket 数量
     */
    public static final int DEFAULT_BUCKET_NUM = 10;

    // ==================== DDL 生成 ====================

    /**
     * 生成 user_click_sessions 表的 DDL 语句
     * 
     * @return DDL 语句
     */
    public static String generateUserClickSessionsDDL() {
        return String.format(
            "-- User Click Sessions Table (KV Table)\n" +
            "-- Stores user click session data for attribution\n" +
            "-- Key: user_id, Value: Click session with click history\n" +
            "-- TTL: 24 hours (automatically managed by Fluss)\n\n" +
            "CREATE TABLE IF NOT EXISTS %s.%s (\n" +
            "    user_id STRING PRIMARY KEY NOT ENFORCED,\n" +
            "    clicks ARRAY<ROW<\n" +
            "        event_id STRING,\n" +
            "        user_id STRING,\n" +
            "        timestamp BIGINT,\n" +
            "        advertiser_id STRING,\n" +
            "        campaign_id STRING,\n" +
            "        creative_id STRING,\n" +
            "        placement_id STRING,\n" +
            "        media_id STRING,\n" +
            "        click_type STRING,\n" +
            "        ip_address STRING,\n" +
            "        user_agent STRING,\n" +
            "        device_type STRING,\n" +
            "        os STRING,\n" +
            "        app_version STRING,\n" +
            "        attributes STRING,\n" +
            "        create_time BIGINT,\n" +
            "        source STRING\n" +
            "    >>,\n" +
            "    session_start_time BIGINT,\n" +
            "    last_update_time BIGINT,\n" +
            "    click_count INT,\n" +
            "    version BIGINT\n" +
            ") WITH (\n" +
            "    'connector' = 'fluss',\n" +
            "    'table-type' = 'PRIMARY_KEY',\n" +
            "    'database-name' = '%s',\n" +
            "    'bucket.num' = '%d',\n" +
            "    'log.ttl.ms' = '86400000'\n" +  // 24 hours TTL
            ");\n",
            DATABASE_NAME,
            TABLE_USER_CLICK_SESSIONS,
            DATABASE_NAME,
            DEFAULT_BUCKET_NUM
        );
    }

    /**
     * 生成数据库创建语句
     * 
     * @return DDL 语句
     */
    public static String generateDatabaseDDL() {
        return String.format(
            "-- Create Attribution Database\n" +
            "CREATE DATABASE IF NOT EXISTS %s;\n",
            DATABASE_NAME
        );
    }

    // ==================== Schema 验证 ====================
    // Row 验证方法已移除，直接使用 Java 对象进行验证

    // ==================== 工具方法 ====================

    /**
     * 获取字段在 Row 中的索引
     * 
     * @param fieldName 字段名
     * @return 索引位置，找不到返回 -1
     */
    public static int getFieldIndex(String fieldName) {
        int index = USER_CLICK_SESSION_FIELDS.indexOf(fieldName);
        if (index >= 0) {
            return index;
        }
        index = CLICK_EVENT_FIELDS.indexOf(fieldName);
        return index;
    }

    // 注意：TTL 相关方法已移除
    // Fluss 表级别自动管理 TTL ('log.ttl.ms' = '86400000')
}
