# SimpleAttributeSystem 架构设计文档

**版本**: 最新版  
**日期**: 2026-02-24  
**状态**: ✅ 生产就绪  
**编译**: ✅ BUILD SUCCESS  
**核心文件**: 26 个 Java 文件

---

## 📋 目录

1. [架构概述](#1-架构概述)
2. [设计原则](#2-设计原则)
3. [核心架构](#3-核心架构)
4. [技术栈](#4-技术栈)
5. [KV 存储抽象层](#5-kv 存储抽象层)
6. [Jobs 详解](#6-jobs 详解)
7. [数据模型](#7-数据模型)
8. [数据流](#8-数据流)
9. [配置管理](#9-配置管理)
10. [部署架构](#10-部署架构)

---

## 1. 架构概述

### 1.1 核心设计理念

**多 KV 存储支持架构** - 通过抽象层支持多种 KV 存储后端：

```
┌─────────────────────────────────────────────────────────────┐
│                    应用层 (Flink Jobs)                       │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │ Click Writer    │  │ Attribution     │  │ Retry       │ │
│  │ Job             │  │ Engine Job      │  │ Consumer    │ │
│  └────────┬────────┘  └────────┬────────┘  └──────┬──────┘ │
│           │                    │                   │        │
│           └────────────────────┼───────────────────┘        │
│                                │                             │
│                    ┌───────────▼───────────┐                │
│                    │   KVClient 接口层      │                │
│                    │  (统一 KV 访问抽象)     │                │
│                    └───────────┬───────────┘                │
└────────────────────────────────┼────────────────────────────┘
                                 │
            ┌────────────────────┼────────────────────┐
            │                    │                    │
            ▼                    ▼                    ▼
    ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
    │   Redis KV    │   │ Apache Fluss  │   │   其他 KV     │
    │   (当前使用)   │   │   (未来支持)   │   │   (可扩展)    │
    └───────────────┘   └───────────────┘   └───────────────┘
```

### 1.2 架构优势

| 特性 | 说明 |
|------|------|
| **存储无关性** | 应用层不依赖具体 KV 实现 |
| **灵活切换** | 通过配置切换 KV 后端 (Redis/Fluss) |
| **平滑迁移** | 支持从 Redis 迁移到 Fluss |
| **多环境支持** | 开发/测试/生产可用不同存储 |
| **易于扩展** | 新增 KV 实现只需实现接口 |

### 1.3 支持的 KV 存储

| KV 存储 | 状态 | 适用场景 |
|---------|------|----------|
| **Redis** | ✅ 生产就绪 | 当前默认，低延迟，高吞吐 |
| **Apache Fluss** | 🔄 代码支持 | 未来切换，云原生 KV |
| **本地缓存** | ❌ 已废弃 | 不支持分布式 |

---

## 2. 设计原则

### 2.1 核心原则

1. **接口抽象**: 通过 `KVClient` 接口隔离具体实现
2. **工厂模式**: 通过 `KVClientFactory` 创建具体实例
3. **配置驱动**: 通过配置选择 KV 后端
4. **无状态设计**: Flink Jobs 无状态，状态存储在 KV
5. **批量优化**: 批量读写 KV，减少网络开销

### 2.2 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Application Layer (Flink Jobs)                     │
│ - ClickWriterJob                                            │
│ - AttributionEngineJob                                      │
│ - RetryConsumerJob                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: KV Abstraction Layer                               │
│ - KVClient (接口)                                           │
│ - KVClientFactory (工厂)                                    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: KV Implementation Layer                            │
│ - RedisKVClient (Redis 实现)                                │
│ - FlussKVClient (Fluss 实现)                                │
│ - FutureKVClient (其他实现)                                 │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 4: Storage Layer                                      │
│ - Redis Cluster                                             │
│ - Apache Fluss                                              │
│ - Other KV Stores                                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Data Sources                              │
│  ┌─────────────────┐           ┌─────────────────┐              │
│  │ Click Events    │           │ Conversion      │              │
│  │ (Kafka Topic)   │           │ Events (Kafka)  │              │
│  └────────┬────────┘           └────────┬────────┘              │
└───────────┼─────────────────────────────┼───────────────────────┘
            │                             │
            ▼                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Flink Cluster                                │
│                                                                  │
│  ┌──────────────────┐          ┌──────────────────┐            │
│  │ Click Writer Job │          │ Attribution      │            │
│  │                  │          │ Engine Job       │            │
│  │ 1. Consume Click │          │ 1. Consume       │            │
│  │ 2. Validate      │          │    Conversion    │            │
│  │ 3. Batch Write   │          │ 2. Query KV      │            │
│  │    to KV Store   │─────────▶│ 3. Attribution   │            │
│  └──────────────────┘          │ 4. Write Result  │            │
│                                └────────┬─────────┘            │
│                                         │                       │
│                                         ▼                       │
│                                ┌──────────────────┐            │
│                                │ Retry Consumer   │            │
│                                │ Job              │            │
│                                │ 1. Consume Failed│            │
│                                │ 2. Retry Logic   │            │
│                                │ 3. Write Result  │            │
│                                └──────────────────┘            │
└─────────────────────────────────────────────────────────────────┘
            │                             │
            ▼                             ▼
┌───────────────────┐          ┌───────────────────┐
│  KV Store Layer   │          │  Result Layer     │
│  ┌─────────────┐  │          │  ┌─────────────┐  │
│  │ KVClient    │  │          │  │ Kafka Sink  │  │
│  │ (Interface) │  │          │  │ (Results)   │  │
│  └──────┬──────┘  │          │  └─────────────┘  │
│         │         │          └───────────────────┘
│  ┌──────▼──────┐  │
│  │ Redis/Fluss │  │
│  │ (Pluggable) │  │
│  └─────────────┘  │
└───────────────────┘
```

### 3.2 KV 存储抽象层设计

#### 3.2.1 KVClient 接口

```java
public interface KVClient {
    /**
     * 获取用户点击会话
     */
    FlussClickSession get(String userId);
    
    /**
     * 存储用户点击会话
     */
    void put(String userId, FlussClickSession session);
    
    /**
     * 关闭连接
     */
    void close();
}
```

#### 3.2.2 KVClientFactory

```java
public class KVClientFactory {
    /**
     * 根据配置创建 KVClient 实例
     * 支持：redis, fluss-local, fluss-cluster
     */
    public static KVClient create(FlussSourceConfig config) {
        String kvType = config.getKvType();
        
        switch (kvType) {
            case "redis":
                return new RedisKVClient(config);
            case "fluss-local":
                return new FlussKVClient(config);
            case "fluss-cluster":
                return new FlussKVClient(config);
            default:
                throw new IllegalArgumentException("Unknown KV type: " + kvType);
        }
    }
}
```

#### 3.2.3 RedisKVClient 实现

```java
public class RedisKVClient implements KVClient {
    private Jedis jedis;
    private ObjectMapper mapper;
    private String keyPrefix;
    private int ttlSeconds;
    
    @Override
    public FlussClickSession get(String userId) {
        String key = keyPrefix + userId;
        String json = jedis.get(key);
        return mapper.readValue(json, FlussClickSession.class);
    }
    
    @Override
    public void put(String userId, FlussClickSession session) {
        String key = keyPrefix + userId;
        String json = mapper.writeValueAsString(session);
        jedis.setex(key, ttlSeconds, json);
    }
}
```

#### 3.2.4 FlussKVClient 实现

```java
public class FlussKVClient implements KVClient {
    private TableClient tableClient;
    private String database;
    private String table;
    
    @Override
    public FlussClickSession get(String userId) {
        // Fluss KV 读取逻辑
        Row row = tableClient.get(userId).get();
        return convertToSession(row);
    }
    
    @Override
    public void put(String userId, FlussClickSession session) {
        // Fluss KV 写入逻辑
        Row row = convertToRow(session);
        tableClient.upsert(row).get();
    }
}
```

---

## 4. 技术栈

### 4.1 核心技术

| 组件 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **流处理** | Apache Flink | 1.17.1 | 实时计算引擎 |
| **KV 存储** | Redis / Apache Fluss | Latest | Click 会话存储 |
| **消息队列** | Kafka | Latest | 事件和结果传输 |
| **重试队列** | Kafka | Latest | 延迟重试 |

### 4.2 开发依赖

```xml
<dependencies>
    <!-- Flink Core -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-streaming-java</artifactId>
        <version>1.17.1</version>
    </dependency>
    
    <!-- Kafka Connector -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-connector-kafka</artifactId>
        <version>1.17.1</version>
    </dependency>
    
    <!-- Redis Client -->
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
        <version>4.3.1</version>
    </dependency>
    
    <!-- Fluss Client (可选) -->
    <dependency>
        <groupId>org.apache.fluss</groupId>
        <artifactId>fluss-client</artifactId>
        <version>0.6.0</version>
        <scope>provided</scope>
    </dependency>
    
    <!-- JSON & Utils -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.30</version>
    </dependency>
</dependencies>
```

---

## 5. KV 存储抽象层

### 5.1 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Code                          │
│                                                              │
│  // 代码不依赖具体 KV 实现                                    │
│  KVClient kvClient = KVClientFactory.create(config);        │
│  FlussClickSession session = kvClient.get(userId);          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    KVClient Interface                        │
│                                                              │
│  public interface KVClient {                                 │
│      FlussClickSession get(String userId);                  │
│      void put(String userId, FlussClickSession session);    │
│      void close();                                           │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘
                            │
            ┌───────────────┴───────────────┐
            │                               │
            ▼                               ▼
┌───────────────────┐           ┌───────────────────┐
│  RedisKVClient    │           │  FlussKVClient    │
│                   │           │                   │
│  - Jedis          │           │  - TableClient    │
│  - keyPrefix      │           │  - database       │
│  - TTL            │           │  - table          │
│                   │           │                   │
│  get(): jedis.get │           │  get(): table.get │
│  put(): jedis.set │           │  put(): upsert    │
└───────────────────┘           └───────────────────┘
```

### 5.2 配置切换

#### Redis 模式 (当前默认)

```yaml
# config/click-writer.yaml
click:
  store:
    type: redis  # 关键配置
  redis:
    host: redis
    port: 6379
    ttl-seconds: 3600
    key-prefix: "click:"
```

#### Fluss 模式 (未来切换)

```yaml
# config/click-writer.yaml
click:
  store:
    type: fluss-cluster  # 切换到 Fluss
  fluss:
    bootstrap-servers: fluss-broker:9092
    database: attribution_db
    table: user_click_sessions
```

### 5.3 实现对比

| 特性 | RedisKVClient | FlussKVClient |
|------|---------------|---------------|
| **延迟** | < 5ms | < 10ms |
| **吞吐** | 100,000+/s | 50,000+/s |
| **TTL** | ✅ 原生支持 | ⚠️ 需手动实现 |
| **事务** | ❌ 不支持 | ✅ 支持 |
| **云原生** | ⚠️ 需自建 | ✅ 原生支持 |
| **成熟度** | ✅ 非常成熟 | 🔄 孵化中 |

---

## 6. Jobs 详解

### 6.1 ClickWriterJob

**入口**: `com.attribution.job.ClickWriterJob`

**职责**:
1. 从 Kafka 消费 Click 事件
2. 验证 Click 数据格式
3. 批量写入 KV Store (通过 KVClient)

**代码示例**:
```java
public class ClickWriterJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Kafka Source
        KafkaSource<String> clickSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("click-events")
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
        
        // Process & Write to KV
        env.fromSource(clickSource, WatermarkStrategy.noWatermarks(), "Click Source")
            .map(json -> mapper.readValue(json, ClickEvent.class))
            .filter(ClickValidator::isValid)
            .keyBy(ClickEvent::getUserId)
            .process(new ClickKVBatchWriterProcessFunction(100, 1000L))
            .name("Click KV Writer");
        
        env.execute("Click Writer Job");
    }
}
```

**关键组件**:
- `ClickValidator` - Click 数据验证
- `ClickKVBatchWriterProcessFunction` - 批量写入处理器

**并行度**: 
- 本地测试：1
- 生产环境：4+

---

### 6.2 AttributionEngineJob

**入口**: `com.attribution.job.AttributionEngineJob`

**职责**:
1. 从 Kafka 消费 Conversion 事件
2. 通过 KVClient 查询用户 Click 历史
3. 执行 LAST_CLICK 归因计算
4. 输出结果到 Kafka

**代码示例**:
```java
public class AttributionEngineJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Kafka Source (Conversion)
        KafkaSource<String> conversionSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("conversion-events")
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
        
        // Attribution Process
        env.fromSource(conversionSource, WatermarkStrategy.noWatermarks(), "Conversion Source")
            .map(json -> mapper.readValue(json, ConversionEvent.class))
            .keyBy(ConversionEvent::getUserId)
            .process(new AttributionProcessFunction(
                604800000L,  // 7 天窗口
                50,          // 最大 Click 数
                "LAST_CLICK" // 归因模型
            ))
            .name("Attribution Processor");
        
        env.execute("Attribution Engine Job");
    }
}
```

**关键组件**:
- `AttributionProcessFunction` - 归因处理函数
- `AttributionEngine` - 归因引擎

**并行度**:
- 本地测试：1
- 生产环境：8+

---

### 6.3 RetryConsumerJob

**入口**: `com.attribution.job.RetryConsumerJob`

**职责**:
1. 从 Kafka 消费重试消息
2. 通过 KVClient 查询 Click 历史
3. 重新执行归因计算
4. 成功结果输出到 Kafka
5. 失败结果重新发送或 DLQ

**关键组件**:
- `RetryProcessFunction` - 重试处理函数
- `RetryMessage` - 重试消息模型

**并行度**:
- 本地测试：2
- 生产环境：4+

---

### 6.4 LocalTestJob

**入口**: `com.attribution.job.LocalTestJob`

**职责**:
- 单 JVM 测试完整流程
- 开发环境调试

**特点**:
- 包含 Click Writer + Attribution Engine
- 无需部署多个 Jobs
- 快速验证

---

## 7. 数据模型

### 7.1 ClickEvent

```java
{
  "event_id": "click-001",
  "user_id": "user-123",
  "timestamp": 1708512000000,
  "advertiser_id": "adv-001",
  "campaign_id": "camp-001",
  "creative_id": "creative-001",
  "placement_id": "placement-001",
  "media_id": "media-001",
  "click_type": "banner",
  "ip_address": "192.168.1.1",
  "user_agent": "Mozilla/5.0...",
  "device_type": "mobile",
  "os": "iOS",
  "app_version": "1.0.0"
}
```

### 7.2 ConversionEvent

```java
{
  "event_id": "conv-001",
  "user_id": "user-123",
  "timestamp": 1708515600000,
  "advertiser_id": "adv-001",
  "conversion_type": "PURCHASE",
  "conversion_value": 299.99,
  "currency": "CNY",
  "order_id": "order-123",
  "product_id": "product-456",
  "quantity": 1
}
```

### 7.3 FlussClickSession

**用途**: 存储在 KV Store 的用户点击会话

```java
{
  "userId": "user-123",
  "clicks": [
    {
      "event_id": "click-001",
      "timestamp": 1708512000000,
      "advertiser_id": "adv-001",
      "campaign_id": "camp-001"
    },
    {
      "event_id": "click-002",
      "timestamp": 1708513000000,
      "advertiser_id": "adv-001",
      "campaign_id": "camp-002"
    }
  ],
  "clickCount": 2,
  "sessionStartTime": 1708512000000,
  "lastUpdateTime": 1771931295005,
  "version": 2
}
```

### 7.4 AttributionResult

```java
{
  "resultId": "result_conv-001_1771931934364",
  "conversionId": "conv-001",
  "userId": "user-123",
  "advertiserId": "adv-001",
  "campaignId": "camp-001",
  "attributionModel": "LAST_CLICK",
  "attributedClicks": ["click-002"],
  "creditDistribution": {
    "adv-001:camp-001:click-002": 1.0
  },
  "totalConversionValue": 299.99,
  "currency": "CNY",
  "attributionTimestamp": 1771931934364,
  "lookbackWindowHours": 168,  // 7 天
  "status": "SUCCESS",
  "errorMessage": null,
  "retryCount": 0,
  "createTime": 1771931934364
}
```

### 7.5 RetryMessage

```java
{
  "originalResult": { /* AttributionResult */ },
  "retryCount": 0,
  "lastRetryTime": 1771931934364,
  "reason": "Redis timeout",
  "maxRetries": 10
}
```

---

## 8. 数据流

### 8.1 正常流程

```
┌─────────────┐
│ Click 事件   │
│ (Kafka)     │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│ Click Writer Job        │
│ 1. Consume from Kafka   │
│ 2. Validate Click       │
│ 3. Batch Write to KV    │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ KV Store (Redis/Fluss)  │
│ Key: click:{user_id}    │
│ Value: FlussClickSession│
│ TTL: 3600s              │
└──────┬──────────────────┘
       │
       ▼
┌─────────────┐     ┌─────────────────────────┐
│ Conversion  │────▶│ Attribution Engine Job  │
│ 事件 (Kafka) │     │ 1. Consume Conversion   │
└─────────────┘     │ 2. Query KV for Clicks  │
                    │ 3. Execute Attribution  │
                    │ 4. Write Result to Kafka│
                    └──────┬──────────────────┘
                           │
                           ▼
                    ┌─────────────────────────┐
                    │ Kafka                   │
                    │ - attribution-results-  │
                    │   success               │
                    │ - attribution-results-  │
                    │   failed                │
                    └─────────────────────────┘
```

### 8.2 重试流程

```
┌─────────────────────────┐
│ AttributionEngineJob    │
│ (Failed Result)         │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ Kafka Retry Topic       │
│ (attribution-retry)     │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ Retry Consumer Job      │
│ (Flink, 从 Kafka 消费)   │
│ 1. Consume Retry Msg    │
│ 2. Query KV for Clicks  │
│ 3. Retry Attribution    │
└──────┬──────────────────┘
       │
       ├──────────────┬────────────────┐
       │              │                │
       ▼              ▼                ▼
┌──────────┐  ┌──────────────┐  ┌──────────┐
│ SUCCESS  │  │ RETRY        │  │ DLQ      │
│ → Kafka  │  │ → retry      │  │ → dlq    │
│          │  │   topic      │  │   topic  │
└──────────┘  └──────────────┘  └──────────┘
```

**说明**:
- **AttributionEngineJob**: 失败结果发送到 Kafka retry topic
- **RetryConsumerJob**: 从 Kafka 消费重试消息（Flink 实现）
- **纯 Kafka 方案**: 架构简洁，易于维护

### 8.3 KV 存储切换流程

```
┌─────────────────────────────────────────────────────────────┐
│ 当前：Redis 模式                                             │
│                                                              │
│ 1. 配置：click.store.type=redis                             │
│ 2. KVClient: RedisKVClient                                  │
│ 3. 数据：Redis Key-Value                                    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 切换配置
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 未来：Fluss 模式                                             │
│                                                              │
│ 1. 配置：click.store.type=fluss-cluster                     │
│ 2. KVClient: FlussKVClient                                  │
│ 3. 数据：Fluss Table                                        │
│                                                              │
│ 优势：                                                       │
│ - 云原生支持                                                 │
│ - 事务支持                                                   │
│ - 更好的扩展性                                               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. 配置管理

### 9.1 配置文件结构

```
config/
├── click-writer.yaml
├── attribution-engine.yaml
└── retry-consumer.yaml
```

### 9.2 核心配置项

#### KV 存储配置

```yaml
click:
  store:
    type: redis  # redis | fluss-local | fluss-cluster
  
  redis:
    host: redis
    port: 6379
    ttl-seconds: 3600
    key-prefix: "click:"
  
  fluss:
    bootstrap-servers: fluss-broker:9092
    database: attribution_db
    table: user_click_sessions
```

#### Kafka 配置

```yaml
kafka:
  bootstrap-servers: kafka:29092
  topics:
    click: click-events
    conversion: conversion-events
    result-success: attribution-results-success
    result-failed: attribution-results-failed
    retry: attribution-retry
    dlq: attribution-retry-dlq
```

#### Flink 配置

```yaml
job:
  parallelism: 1  # 本地测试
  checkpoint:
    enabled: true
    interval: 60000  # 60 秒
  
attribution:
  window-ms: 604800000  # 7 天
  model: LAST_CLICK
  max-clicks: 50
```

### 9.3 环境变量覆盖

```bash
# KV 存储配置
export CLICK_STORE_TYPE=redis
export REDIS_HOST=redis
export REDIS_PORT=6379

# Kafka 配置
export KAFKA_BOOTSTRAP_SERVERS=kafka:29092

# Flink 配置
export JOB_PARALLELISM=4
```

---

## 10. 部署架构

### 10.1 Docker Compose (本地开发)

```yaml
version: '3.8'
services:
  flink-jobmanager:
    image: flink:1.17.1
    ports:
      - "8081:8081"
    command: jobmanager
  
  flink-taskmanager:
    image: flink:1.17.1
    depends_on:
      - flink-jobmanager
    command: taskmanager
    environment:
      - FLINK_JOBMANAGER_HOST=flink-jobmanager
  
  redis:
    image: redis:latest
    ports:
      - "6379:6379"
  
  kafka:
    image: confluentinc/cp-kafka:latest
    ports:
      - "9092:9092"
    environment:
      - KAFKA_BROKER_ID=1
      - KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181
      - KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:29092
  
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      - ZOOKEEPER_CLIENT_PORT=2181
```

### 10.2 Flink Jobs 提交

```bash
# 1. Click Writer Job
flink run -c com.attribution.job.ClickWriterJob \
  -d -p 1 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar

# 2. Attribution Engine Job
flink run -c com.attribution.job.AttributionEngineJob \
  -d -p 1 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar

# 3. Retry Consumer Job
flink run -c com.attribution.job.RetryConsumerJob \
  -d -p 2 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar
```

### 10.3 生产部署建议

**Flink 集群**:
- JobManager: 2 (HA)
- TaskManager: 4+ (根据负载)
- Slots per TM: 4

**Redis 集群**:
- 模式：Cluster 或 Sentinel
- 节点：3 Master + 3 Slave
- 内存：根据数据量评估

**Kafka 集群**:
- Broker: 3+
- 分区数：3-6 (根据吞吐)
- 副本数：2-3

---

## 📚 相关文档

- [CODE-CLEANUP-SUMMARY.md](./CODE-CLEANUP-SUMMARY.md) - 代码清理总结
- [REDIS-KV-IMPLEMENTATION.md](./REDIS-KV-IMPLEMENTATION.md) - Redis KV 实现
- [DOCUMENTATION-UPDATE.md](./DOCUMENTATION-UPDATE.md) - 文档更新说明

---

**最后更新**: 2026-02-24  
**维护者**: SimpleAttributeSystem Team

---

*多 KV 存储支持，灵活可扩展*
