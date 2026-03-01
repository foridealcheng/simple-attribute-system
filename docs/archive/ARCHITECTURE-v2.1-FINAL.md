# SimpleAttributeSystem v2.1 架构设计文档

**版本**: v2.1.0  
**日期**: 2026-02-24  
**状态**: ✅ 生产就绪  
**编译**: ✅ BUILD SUCCESS  
**代码行数**: ~26 个核心 Java 文件

---

## 📋 目录

1. [架构概述](#1-架构概述)
2. [技术栈](#2-技术栈)
3. [核心组件](#3-核心组件)
4. [数据流](#4-数据流)
5. [存储设计](#5-存储设计)
6. [重试机制](#6-重试机制)
7. [配置管理](#7-配置管理)
8. [部署架构](#8-部署架构)
9. [监控指标](#9-监控指标)
10. [文件清单](#10-文件清单)

---

## 1. 架构概述

### 1.1 设计目标

- ✅ **分布式**: 支持水平扩展，多 JVM 进程共享状态
- ✅ **实时性**: 秒级归因延迟
- ✅ **高可用**: Flink Checkpoint + 持久化存储
- ✅ **统一技术栈**: 全部基于 Flink，简化运维

### 1.2 架构演进

| 版本 | 架构 | 状态 |
|------|------|------|
| v1.0 | 单体 Job + 本地缓存 | ❌ 已废弃 |
| v2.0 | 分布式 + Fluss KV | ⚠️ 过渡版本 |
| **v2.1** | **分布式 + Redis KV** | ✅ **当前版本** |

### 1.3 核心特性

```
┌─────────────────────────────────────────────────────────────┐
│  Flink 集群 (3 个独立 Jobs)                                   │
│                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │ Click Writer    │  │ Attribution     │  │ Retry       │ │
│  │ Job             │  │ Engine Job      │  │ Consumer    │ │
│  │                 │  │                 │  │             │ │
│  │ Click → Redis   │  │ Conv + Redis →  │  │ Retry →     │ │
│  │                 │  │ Kafka           │  │ Kafka       │ │
│  └─────────────────┘  └─────────────────┘  └─────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  外部服务                                                    │
│  ┌─────────┐  ┌─────────────┐  ┌──────────┐                │
│  │ Redis   │  │ Kafka       │  │ RocketMQ │                │
│  │ (KV)    │  │ (MQ)        │  │ (备用)   │                │
│  └─────────┘  └─────────────┘  └──────────┘                │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 技术栈

### 2.1 核心技术

| 组件 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **流处理** | Apache Flink | 1.17.1 | 实时计算引擎 |
| **KV 存储** | Redis | Latest | Click 会话存储 |
| **消息队列** | Kafka | Latest | 事件和结果传输 |
| **重试队列** | Kafka | Latest | 延迟重试 |
| **备用 MQ** | RocketMQ | 5.1.0 | 备用重试机制 |

### 2.2 开发依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| flink-streaming-java | 1.17.1 | Flink 核心 |
| flink-connector-kafka | 1.17.1 | Kafka 连接器 |
| jedis | 4.3.1 | Redis 客户端 |
| jackson-databind | 2.15.2 | JSON 序列化 |
| lombok | 1.18.30 | 代码简化 |
| slf4j-api | 1.7.36 | 日志框架 |

### 2.3 构建工具

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.11.0</version>
      <configuration>
        <source>11</source>
        <target>11</target>
      </configuration>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <version>3.5.0</version>
    </plugin>
  </plugins>
</build>
```

---

## 3. 核心组件

### 3.1 Jobs (4 个)

#### 3.1.1 ClickWriterJob

**入口**: `com.attribution.job.ClickWriterJob`

**职责**:
- 从 Kafka 消费 Click 事件 (`click-events`)
- 验证 Click 数据格式
- 批量写入 Redis KV Store

**配置**:
```yaml
job.parallelism=1
click.store.type=redis
click.redis.host=redis
click.redis.port=6379
click.redis.ttl-seconds=3600
click.batch.size=100
click.batch.interval.ms=1000
```

**关键类**:
- `ClickValidator` - Click 数据验证
- `ClickKVBatchWriterProcessFunction` - 批量写入处理器

**并行度**: 1 (本地测试) / 4+ (生产)

---

#### 3.1.2 AttributionEngineJob

**入口**: `com.attribution.job.AttributionEngineJob`

**职责**:
- 从 Kafka 消费 Conversion 事件 (`conversion-events`)
- 从 Redis 查询用户 Click 历史
- 执行 LAST_CLICK 归因计算
- 输出归因结果到 Kafka

**配置**:
```yaml
job.parallelism=1
click.store.type=redis
click.redis.host=redis
click.redis.port=6379
attribution.window.ms=604800000  # 7 天
attribution.model=LAST_CLICK
```

**关键类**:
- `AttributionProcessFunction` - 归因处理函数
- `AttributionEngine` - 归因引擎

**并行度**: 1 (本地测试) / 8+ (生产)

---

#### 3.1.3 RetryConsumerJob

**入口**: `com.attribution.job.RetryConsumerJob`

**职责**:
- 从 Kafka 消费重试消息 (`attribution-retry`)
- 从 Redis 查询 Click 历史
- 重新执行归因计算
- 成功结果输出到 Kafka
- 失败结果重新发送到重试队列或 DLQ

**配置**:
```yaml
job.parallelism=2
click.store.type=redis
click.redis.host=redis
click.redis.port=6379
```

**关键类**:
- `RetryProcessFunction` - 重试处理函数
- `RetryMessage` - 重试消息模型

**并行度**: 2 (本地测试) / 4+ (生产)

---

#### 3.1.4 LocalTestJob

**入口**: `com.attribution.job.LocalTestJob`

**职责**:
- 单 JVM 测试完整流程
- 开发环境调试

**配置**: 同 ClickWriterJob + AttributionEngineJob

**并行度**: 1

---

### 3.2 KV Clients (4 个)

#### 3.2.1 KVClient (接口)

```java
public interface KVClient {
    FlussClickSession get(String userId);
    void put(String userId, FlussClickSession session);
    void close();
}
```

#### 3.2.2 KVClientFactory

```java
public class KVClientFactory {
    public static KVClient create(FlussSourceConfig config);
    public static KVClient createDefault();
}
```

#### 3.2.3 RedisKVClient

**实现**: 基于 Jedis 的 Redis 客户端

**配置**:
- Host: `redis` (Docker) / `localhost` (本地)
- Port: `6379`
- TTL: `3600` 秒
- Key 前缀：`click:`

#### 3.2.4 FlussKVClient

**实现**: 基于 Fluss Client 的 KV 客户端

**状态**: 保留用于未来切换到 Fluss

---

### 3.3 Models (6 个)

#### 3.3.1 ClickEvent

```java
{
  "event_id": "click-001",
  "user_id": "user-123",
  "timestamp": 1708512000000,
  "advertiser_id": "adv-001",
  "campaign_id": "camp-001",
  ...
}
```

#### 3.3.2 ConversionEvent

```java
{
  "event_id": "conv-001",
  "user_id": "user-123",
  "timestamp": 1708515600000,
  "advertiser_id": "adv-001",
  "conversion_type": "PURCHASE",
  "conversion_value": 299.99,
  "currency": "CNY"
}
```

#### 3.3.3 AttributionResult

```java
{
  "resultId": "result_conv-001_1771931934364",
  "conversionId": "conv-001",
  "userId": "user-123",
  "advertiserId": "adv-001",
  "attributionModel": "LAST_CLICK",
  "attributedClicks": ["click-002"],
  "creditDistribution": {"adv-001:null:click-002": 1.0},
  "totalConversionValue": 299.99,
  "currency": "CNY",
  "status": "SUCCESS"
}
```

#### 3.3.4 FlussClickSession

**用途**: 存储用户 Click 会话

**结构**:
```java
{
  "userId": "user-123",
  "clicks": [ClickEvent, ...],
  "clickCount": 10,
  "sessionStartTime": 1708512000000,
  "lastUpdateTime": 1771931295005,
  "version": 2
}
```

#### 3.3.5 RetryMessage

**用途**: 重试消息封装

**结构**:
```java
{
  "originalResult": AttributionResult,
  "retryCount": 0,
  "lastRetryTime": 1771931934364,
  "reason": "Redis timeout"
}
```

#### 3.3.6 AttributionModel

**用途**: 归因模型枚举

**值**: `LAST_CLICK`, `LINEAR`, `TIME_DECAY`, `POSITION_BASED`

---

## 4. 数据流

### 4.1 正常流程

```
┌─────────────┐
│ Click 事件   │
│ (Kafka)     │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│ Click Writer Job        │
│ - 验证 Click 数据        │
│ - 批量写入 Redis        │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ Redis KV Store          │
│ Key: click:{user_id}    │
│ Value: FlussClickSession│
└──────┬──────────────────┘
       │
       ▼
┌─────────────┐     ┌─────────────────────────┐
│ Conversion  │────▶│ Attribution Engine Job  │
│ 事件 (Kafka) │     │ - 查询 Redis            │
└─────────────┘     │ - 执行归因计算          │
                    │ - 输出结果到 Kafka      │
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

### 4.2 重试流程

```
┌─────────────────────────┐
│ 失败归因结果            │
│ (Kafka: failed)         │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ Retry Consumer Job      │
│ - 查询 Redis            │
│ - 重新执行归因          │
└──────┬──────────────────┘
       │
       ├──────────────┬────────────────┐
       │              │                │
       ▼              ▼                ▼
┌──────────┐  ┌──────────────┐  ┌──────────┐
│ 成功     │  │ 重试 (Kafka) │  │ DLQ      │
│ → Kafka  │  │ → retry      │  │ → dlq    │
└──────────┘  └──────────────┘  └──────────┘
```

---

## 5. 存储设计

### 5.1 Redis KV Store

**Key 格式**: `click:{user_id}`

**Value 结构**: `FlussClickSession` (JSON)

**TTL**: 3600 秒 (1 小时)

**示例**:
```bash
GET click:user-123
```

```json
{
  "userId": "user-123",
  "clicks": [
    {
      "event_id": "click-001",
      "user_id": "user-123",
      "timestamp": 1708512000000,
      "advertiser_id": "adv-001",
      "campaign_id": "camp-001"
    }
  ],
  "clickCount": 1,
  "sessionStartTime": 1708512000000,
  "lastUpdateTime": 1771931295005,
  "version": 2
}
```

### 5.2 Kafka Topics

| Topic | 用途 | 分区数 | 保留策略 |
|-------|------|--------|----------|
| `click-events` | Click 事件输入 | 3 | 7 天 |
| `conversion-events` | Conversion 事件输入 | 3 | 7 天 |
| `attribution-results-success` | 成功归因结果 | 3 | 30 天 |
| `attribution-results-failed` | 失败归因结果 | 3 | 7 天 |
| `attribution-retry` | 重试队列 | 3 | 1 天 |
| `attribution-retry-dlq` | 死信队列 | 1 | 7 天 |

---

## 6. 重试机制

### 6.1 重试策略

**最大重试次数**: 10 次

**延迟策略**:
- 第 1 次：立即重试
- 第 2-3 次：1 分钟延迟
- 第 4-6 次：5 分钟延迟
- 第 7-9 次：30 分钟延迟
- 第 10 次：2 小时延迟

**超过最大重试**: 发送到 DLQ

### 6.2 重试原因

- Redis 连接超时
- 归因计算异常
- Kafka 写入失败

### 6.3 重试监控

**指标**:
- 重试成功率
- 平均重试次数
- DLQ 消息数

---

## 7. 配置管理

### 7.1 配置文件

| 文件 | 用途 |
|------|------|
| `config/click-writer.yaml` | Click Writer 配置 |
| `config/attribution-engine.yaml` | Attribution Engine 配置 |
| `config/retry-consumer.yaml` | Retry Consumer 配置 |

### 7.2 环境变量

```bash
# Redis 配置
REDIS_HOST=redis
REDIS_PORT=6379

# Kafka 配置
KAFKA_BOOTSTRAP_SERVERS=kafka:29092

# Flink 配置
FLINK_JOBMANAGER=flink-jobmanager:8081
```

### 7.3 配置优先级

1. 环境变量 (最高优先级)
2. YAML 配置文件
3. 代码默认值 (最低优先级)

---

## 8. 部署架构

### 8.1 Docker Compose

```yaml
version: '3.8'
services:
  flink-jobmanager:
    image: flink:1.17.1
    ports:
      - "8081:8081"
  
  flink-taskmanager:
    image: flink:1.17.1
    depends_on:
      - flink-jobmanager
  
  redis:
    image: redis:latest
    ports:
      - "6379:6379"
  
  kafka:
    image: confluentinc/cp-kafka:latest
    ports:
      - "9092:9092"
```

### 8.2 Flink Jobs 提交

```bash
# Click Writer Job
flink run -c com.attribution.job.ClickWriterJob \
  -d -p 1 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar

# Attribution Engine Job
flink run -c com.attribution.job.AttributionEngineJob \
  -d -p 1 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar

# Retry Consumer Job
flink run -c com.attribution.job.RetryConsumerJob \
  -d -p 2 \
  simple-attribute-system-2.0.0-SNAPSHOT.jar
```

---

## 9. 监控指标

### 9.1 Flink 指标

- Jobs Running: 3
- TaskManagers: 1+
- Slots Total: 4+
- Checkpoint 成功率

### 9.2 业务指标

**Click Writer Job**:
- Click 处理速率 (条/秒)
- Redis 写入延迟 (ms)
- 无效 Click 比例 (%)

**Attribution Engine Job**:
- Conversion 处理速率 (条/秒)
- Redis 查询延迟 (ms)
- 归因成功率 (%)
- 归因延迟 (ms)

**Retry Consumer Job**:
- 重试消息处理速率 (条/秒)
- 重试成功率 (%)
- DLQ 消息数 (条)

---

## 10. 文件清单

### 10.1 核心代码 (26 个文件)

```
src/main/java/com/attribution/
├── client/
│   ├── KVClient.java
│   ├── KVClientFactory.java
│   ├── RedisKVClient.java
│   └── FlussKVClient.java
├── config/
│   ├── FlussSourceConfig.java
│   ├── KafkaSinkConfig.java
│   └── RocketMQRetryConfig.java
├── engine/
│   └── AttributionEngine.java
├── flink/
│   ├── AttributionProcessFunction.java
│   ├── ClickKVBatchWriterProcessFunction.java
│   └── RetryProcessFunction.java
├── job/
│   ├── ClickWriterJob.java
│   ├── AttributionEngineJob.java
│   ├── RetryConsumerJob.java
│   └── LocalTestJob.java
├── model/
│   ├── ClickEvent.java
│   ├── ConversionEvent.java
│   ├── AttributionResult.java
│   ├── FlussClickSession.java
│   ├── RetryMessage.java
│   └── AttributionModel.java
├── schema/
│   └── FlussSchemas.java
├── sink/
│   ├── KafkaAttributionSink.java
│   ├── RocketMQRetrySink.java
│   └── AttributionResultSink.java
└── validator/
    └── ClickValidator.java
```

### 10.2 配置文件

```
config/
├── click-writer.yaml
├── attribution-engine.yaml
└── retry-consumer.yaml
```

### 10.3 脚本文件

```
scripts/
├── quick-start-local.sh
├── stop-all.sh
├── start-click-writer.sh
├── start-attribution-engine.sh
└── start-retry-consumer.sh
```

---

## 📚 相关文档

- [CODE-CLEANUP-SUMMARY.md](./CODE-CLEANUP-SUMMARY.md) - 代码清理总结
- [ARCHITECTURE-SUMMARY-v2.1.md](./ARCHITECTURE-SUMMARY-v2.1.md) - 架构总结
- [IMPLEMENTATION-COMPLETE-v2.1.md](./IMPLEMENTATION-COMPLETE-v2.1.md) - 实现完成报告
- [REDIS-KV-IMPLEMENTATION.md](./REDIS-KV-IMPLEMENTATION.md) - Redis KV 实现

---

**文档版本**: v2.1.0  
**最后更新**: 2026-02-24  
**维护者**: SimpleAttributeSystem Team

---

*v2.1 架构 - 统一 Flink 技术栈，简化运维，提升可靠性*
