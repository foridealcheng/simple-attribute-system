# Fluss KV Schema 设计文档

**版本**: 2.0.0  
**日期**: 2026-02-23  
**状态**: ✅ 完成  
**作者**: SimpleAttributeSystem

---

## 📋 概述

本文档描述 v2.0.0 版本中 Fluss KV Store 的 Schema 设计，用于替代 v1.0.0 的 Flink MapState。

### 设计目标

1. **高性能**: KV 读写延迟 < 10ms (P99)
2. **可扩展**: 支持百万级用户会话
3. **自动清理**: TTL 机制自动过期
4. **易维护**: 清晰的 Schema 和文档

---

## 🗄️ 数据库设计

### 数据库名称

```
attribution_db
```

### 表结构

| 表名 | 类型 | 用途 | Key |
|------|------|------|-----|
| `user_click_sessions` | KV Table | 存储用户点击会话 | `user_id` |

---

## 📊 user_click_sessions 表

### Schema 定义

```sql
CREATE TABLE user_click_sessions (
    user_id STRING PRIMARY KEY NOT ENFORCED,
    clicks ARRAY<ROW<...>>,
    session_start_time BIGINT,
    last_update_time BIGINT,
    click_count INT,
    version BIGINT,
    ttl_timestamp BIGINT
) WITH (
    'connector' = 'fluss',
    'table-type' = 'PRIMARY_KEY',
    'database-name' = 'attribution_db',
    'bucket.num' = '10'
);
```

### 字段说明

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `user_id` | STRING | ✅ | **Primary Key**, 用户唯一标识 |
| `clicks` | ARRAY<ROW> | ❌ | 点击事件列表（最多 50 条） |
| `session_start_time` | BIGINT | ❌ | 会话开始时间（毫秒） |
| `last_update_time` | BIGINT | ❌ | 最后更新时间（毫秒） |
| `click_count` | INT | ❌ | 点击数量（冗余字段，方便查询） |
| `version` | BIGINT | ❌ | 版本号（乐观锁，用于并发控制） |
| `ttl_timestamp` | BIGINT | ❌ | TTL 过期时间（毫秒） |

### Click Event Row 结构

```sql
clicks ARRAY<ROW<
    event_id STRING,          -- 事件 ID（唯一）
    user_id STRING,           -- 用户 ID
    timestamp BIGINT,         -- 点击时间（毫秒）
    advertiser_id STRING,     -- 广告主 ID
    campaign_id STRING,       -- 广告系列 ID
    creative_id STRING,       -- 广告创意 ID
    placement_id STRING,      -- 广告位 ID
    media_id STRING,          -- 媒体渠道 ID
    click_type STRING,        -- 点击类型（click/impression/view）
    ip_address STRING,        -- IP 地址
    user_agent STRING,        -- 用户代理
    device_type STRING,       -- 设备类型
    os STRING,                -- 操作系统
    app_version STRING,       -- 应用版本
    attributes STRING,        -- 额外属性（JSON）
    create_time BIGINT,       -- 创建时间
    source STRING             -- 数据来源
>>
```

---

## 🔑 Key-Value 设计

### Key 设计

```
Key: user_id (STRING)
```

**设计理由**:
- 归因计算基于用户维度
- 支持快速 KV 查找（Get/Put）
- 天然分区（不同用户数据独立）

### Value 设计

```
Value: FlussClickSession (包含点击列表 + 元数据)
```

**包含内容**:
- 点击历史（最多 50 条，按时间排序）
- 会话元数据（开始时间、更新时间）
- 版本控制（乐观锁）
- TTL 信息（自动过期）

---

## ⏰ TTL 策略

### 默认配置

| 参数 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_TTL_HOURS` | 24 | 会话存活 24 小时 |
| `CLEANUP_INTERVAL_MINUTES` | 60 | 每小时清理一次 |

### TTL 计算

```java
// 设置 TTL 时间戳
long ttlTimestamp = currentTime + (ttlHours * 3600000L);

// 检查是否过期
boolean isExpired = currentTime > ttlTimestamp;
```

### 清理策略

1. **读取时清理**: Get 操作检查 TTL，过期则删除
2. **定期清理**: 后台任务扫描并删除过期数据
3. **写入时刷新**: Put 操作更新 TTL 时间戳

---

## 🚀 性能优化

### Bucket 配置

```
bucket.num = 10  (默认)
```

**建议**:
- Bucket 数 = Flink 并行度 × 2
- 根据数据量动态调整
- 避免热点 Bucket

### 本地缓存

```java
// Caffeine 缓存配置
Cache<String, FlussClickSession> cache = Caffeine.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build();
```

**优势**:
- 减少 Fluss RPC 调用
- 降低 P99 延迟
- 提高吞吐量

### 批量操作

```java
// 批量 Get
Map<String, FlussClickSession> sessions = client.batchGet(userIds);

// 批量 Put
client.batchPut(sessions);
```

**优势**:
- 减少网络往返
- 提高吞吐量
- 降低延迟

---

## 📈 容量规划

### 单用户数据量

| 字段 | 大小估算 |
|------|----------|
| user_id | 50 bytes |
| clicks (50 条) | 50 × 500 = 25KB |
| 元数据 | 100 bytes |
| **总计** | **~25KB** |

### 百万用户容量

```
1,000,000 users × 25KB = 25GB
```

### 吞吐量估算

```
假设：1000 QPS (点击 + 转化)
单次操作：~5ms
所需并发：1000 × 0.005 = 5 并发
```

**建议配置**:
- Flink 并行度：4-8
- Fluss Tablet Server: 3 节点
- 内存：每节点 4GB+

---

## 🔒 并发控制

### 乐观锁机制

```java
// 读取时获取版本号
FlussClickSession session = client.get(userId);
Long version = session.getVersion();

// 写入时检查版本号
session.setVersion(version + 1);
client.put(userId, session);
```

### 冲突处理

1. **重试机制**: 版本冲突时重试（最多 3 次）
2. **退避策略**: 指数退避（100ms, 200ms, 400ms）
3. **失败降级**: 重试失败后使用 Flink State

---

## 📝 使用示例

### Java Client 使用

```java
// 初始化 Client
FlussSourceConfig config = new FlussSourceConfig();
config.setBootstrapServers("localhost:9092");
config.setDatabase("attribution_db");

FlussKVClient client = new FlussKVClient(config);

// 写入会话
FlussClickSession session = new FlussClickSession();
session.setUserId("user_123");
session.addClick(clickEvent, 50, 24L);
client.put("user_123", session);

// 读取会话
FlussClickSession session = client.get("user_123");

// 批量操作
Map<String, FlussClickSession> sessions = client.batchGet(userIds);

// 关闭连接
client.close();
```

### Flink SQL 查询

```sql
-- 单用户查询
SELECT * FROM attribution_db.user_click_sessions 
WHERE user_id = 'user_123';

-- 统计点击数
SELECT user_id, click_count 
FROM attribution_db.user_click_sessions 
WHERE click_count > 5;

-- 查询过期数据
SELECT COUNT(*) FROM attribution_db.user_click_sessions 
WHERE ttl_timestamp < CURRENT_TIMESTAMP;
```

---

## 🛠️ 运维指南

### 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| `fluss_read_qps` | 读 QPS | > 10000 |
| `fluss_write_qps` | 写 QPS | > 5000 |
| `fluss_tablet_count` | Tablet 数量 | - |
| `fluss_storage_size` | 存储大小 | > 100GB |
| `expired_session_ratio` | 过期数据比例 | > 30% |

### 备份策略

1. **启用 Snapshot**: 每小时自动快照
2. **存储路径**: S3/HDFS
3. **保留策略**: 保留 7 天快照

### 恢复流程

1. 从 Snapshot 恢复
2. 验证数据完整性
3. 重启 Flink Job

---

## 📚 相关文件

| 文件 | 说明 |
|------|------|
| `docs/schema/user-click-sessions.ddl` | DDL 建表语句 |
| `src/main/java/com/attribution/schema/FlussSchemas.java` | Schema 定义类 |
| `src/main/java/com/attribution/model/FlussClickSession.java` | 会话模型类 |
| `src/main/java/com/attribution/source/adapter/FlussSourceConfig.java` | 配置类 |

---

## ✅ 验收标准

- [x] Schema 设计文档完成
- [x] DDL 脚本编写完成
- [x] Java 类实现完成（FlussSchemas.java）
- [x] 模型类更新完成（FlussClickSession.java）
- [ ] Fluss KV Client 实现（Task 2.2）
- [ ] 集成测试通过

---

## 🔄 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2026-02-23 | 2.0.0 | 初始版本 | SimpleAttributeSystem |

---

**下一步**: [Task 2.2: 实现 Fluss KV Client](./PLAN-v2.0.0-FLUSS-FIRST.md#task-22-实现-fluss-kv-client-)
