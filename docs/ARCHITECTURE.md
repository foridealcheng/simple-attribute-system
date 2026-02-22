# SimpleAttributeSystem - 系统架构设计文档

> 基于 Apache Flink + Apache Fluss + RocketMQ 的实时广告归因系统

**版本**: v1.1  
**创建时间**: 2026-02-21  
**最后更新**: 2026-02-22  
**变更说明**: 更新归因结果输出为 Fluss 消息队列

---

## 1. 系统概述

### 1.1 项目背景
SimpleAttributeSystem 是一个商业广告归因系统，用于实时追踪广告点击与用户转化之间的关系，支持多种归因模型，帮助广告主准确评估广告效果。

### 1.2 核心目标
- **实时性**: 秒级处理广告点击和转化事件
- **准确性**: 支持多种归因模型，确保归因结果准确
- **可靠性**: 多级重试机制，保证数据不丢失
- **可扩展**: 水平扩展支持高并发场景

### 1.3 技术选型

| 组件 | 技术 | 用途 |
|------|------|------|
| 流处理引擎 | Apache Flink 1.18+ | 实时流处理和归因计算 |
| 消息中间件 | Apache Fluss | 主数据流传输 |
| 重试队列 | RocketMQ 5.0+ | 归因失败重试（延迟消费） |
| 状态存储 | Flink Keyed State + Fluss | 用户会话状态管理 |
| 开发语言 | Java 11+ | 核心业务逻辑 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         数据产生层                                   │
│  ┌──────────────┐                      ┌──────────────┐             │
│  │ 广告点击事件  │                      │ 用户转化事件  │             │
│  │ Click Events │                      │Conversion Evts│             │
│  └──────┬───────┘                      └──────┬───────┘             │
└─────────┼─────────────────────────────────────┼──────────────────────┘
          │                                     │
          ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Apache Fluss 集群 (主数据流)                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  click-events-stream (分区键：user_id)                       │   │
│  │  conversion-events-stream (分区键：user_id)                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
          │                                     │
          ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Apache Flink 作业集群                             │
│  ┌─────────────┐    ┌─────────────┐                                │
│  │ Click Source│    │ Conv Source │                                │
│  │   (Fluss)   │    │   (Fluss)   │                                │
│  └──────┬──────┘    └──────┬──────┘                                │
│         │                  │                                       │
│         └────────┬─────────┴──────────┐                            │
│                  │                    │                            │
│         ┌────────▼────────┐   ┌───────▼────────┐                   │
│         │  Event Enrichment│   │ Attribution    │                   │
│         │     Processor   │   │    Engine      │                   │
│         └─────────────────┘   └───────┬────────┘                   │
│                                      │                            │
│                  ┌───────────────────┼───────────────────┐        │
│                                      │                            │
│         ┌──────────────────────────────────────────────────┐      │
│         │         Attribution Result Writer                │      │
│         └──────────────────────────────────────────────────┘      │
│                    │                    │                         │
│         ┌──────────▼────────┐  ┌───────▼────────┐                │
│         │  Success Results  │  │  Failed Results│                │
│         │  (Fluss MQ)       │  │  (Fluss MQ)    │                │
│         └───────────────────┘  └────────────────┘                │
│                                                                   │
│         ┌───────▼────────┐                                       │
│         │  Retry Handler │                                       │
│         │  (RocketMQ)    │                                       │
│         └────────────────┘                                       │
└───────────────────────────────────────────────────────────────────┘
         │                                    │
         ▼                                    ▼
┌────────────────────────────────┐  ┌──────────────────────────────┐
│     Apache Fluss (结果队列)     │  │   RocketMQ (重试队列)        │
│  ┌──────────────────────────┐  │  │  ┌──────────────────────┐   │
│  │ attribution-results-     │  │  │  │ attribution-retry-   │   │
│  │     success              │  │  │  │     topic            │   │
│  │                          │  │  │  │  - Level 1: 5 分钟     │   │
│  │  ┌────────────────────┐  │  │  │  │  - Level 2: 30 分钟   │   │
│  │  │ attribution-results│  │  │  │  │  - Level 3: 2 小时    │   │
│  │  │     -failed        │  │  │  │  └──────────────────────┘   │
│  │  └────────────────────┘  │  │  │                           │   │
│  └──────────────────────────┘  │  └──────────────────────────┘   │
└────────────────────────────────┘                                  
```

### 2.2 数据流设计

#### 2.2.1 正常处理流程
```
1. 事件产生 → Apache Fluss (click/conversion streams)
2. Flink 消费 Fluss 流 → 事件富化 → 实时归因计算
3. 成功 → 写入 Fluss 消息队列 (attribution-results-success)
4. 失败 → 写入 Fluss 消息队列 (attribution-results-failed)
5. 需要重试 → 进入 RocketMQ 重试队列（带延迟级别）
```

#### 2.2.2 重试处理流程
```
1. RocketMQ 延迟消费触发重试
2. 重试事件重新进入 Flink 处理
3. 根据重试次数决定：
   - 继续重试（升级延迟级别）
   - 永久失败（进入死信队列）
   - 人工干预队列
```

---

## 3. 数据模型设计

### 3.1 事件数据结构

#### 3.1.1 广告点击事件 (Click Event)
```json
{
  "eventId": "click_123456789",
  "eventType": "CLICK",
  "timestamp": 1708512000000,
  "userId": "user_789abc",
  "advertiserId": "adv_001",
  "campaignId": "camp_001",
  "creativeId": "cre_001",
  "adPlacementId": "place_001",
  "clickUrl": "https://example.com/track?cid=123",
  "landingPageUrl": "https://example.com/product/456",
  "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)...",
  "ipAddress": "192.168.1.1",
  "referrer": "https://google.com/search?q=xxx",
  "deviceInfo": {
    "deviceType": "MOBILE",
    "os": "iOS",
    "osVersion": "17.0",
    "browser": "Safari",
    "browserVersion": "17.0"
  },
  "locationInfo": {
    "country": "CN",
    "province": "Beijing",
    "city": "Beijing"
  },
  "attributes": {
    "keyword": "跑鞋",
    "adRank": "1",
    "bidPrice": "2.50"
  }
}
```

#### 3.1.2 用户转化事件 (Conversion Event)
```json
{
  "eventId": "conv_987654321",
  "eventType": "CONVERSION",
  "timestamp": 1708515600000,
  "userId": "user_789abc",
  "advertiserId": "adv_001",
  "conversionType": "PURCHASE",
  "conversionSubtype": "ONLINE_ORDER",
  "conversionValue": 299.99,
  "currency": "CNY",
  "orderId": "order_456789",
  "productId": "prod_001",
  "productCategory": "sports/shoes",
  "quantity": 1,
  "attributes": {
    "paymentMethod": "alipay",
    "couponUsed": "true",
    "couponValue": "50.00",
    "firstPurchase": "false"
  }
}
```

### 3.2 归因结果模型

#### 3.2.1 归因结果 (Attribution Result)
```json
{
  "attributionId": "attr_123456789",
  "conversionEventId": "conv_987654321",
  "userId": "user_789abc",
  "advertiserId": "adv_001",
  "attributedClicks": [
    {
      "clickEventId": "click_123456789",
      "campaignId": "camp_001",
      "creativeId": "cre_001",
      "attributionModel": "LAST_CLICK",
      "attributionWeight": 1.0,
      "attributedValue": 299.99,
      "clickTimestamp": 1708512000000,
      "conversionTimestamp": 1708515600000,
      "timeToConversion": 3600000,
      "timeToConversionUnit": "MILLISECONDS"
    }
  ],
  "totalAttributionValue": 299.99,
  "attributionModel": "LAST_CLICK",
  "processingTimestamp": 1708515601000,
  "processingNode": "flink-taskmanager-01",
  "metadata": {
    "clickCount": 3,
    "firstClickTimestamp": 1708508400000,
    "lastClickTimestamp": 1708512000000,
    "attributionWindow": 604800000
  }
}
```

### 3.3 重试消息模型

#### 3.3.1 重试消息 (Retry Message)
```json
{
  "retryId": "retry_123456789",
  "originalEventType": "CLICK|CONVERSION",
  "originalEvent": "{...原始事件 JSON...}",
  "failureReason": "STATE_ACCESS_TIMEOUT",
  "failureTimestamp": 1708515601000,
  "retryCount": 1,
  "retryLevel": 1,
  "maxRetries": 3,
  "nextRetryTime": 1708515901000,
  "retryHistory": [
    {
      "attemptTime": 1708515601000,
      "failureReason": "STATE_ACCESS_TIMEOUT",
      "retryLevel": 0
    }
  ],
  "metadata": {
    "originalSource": "flink-attribution-engine",
    "priority": "NORMAL",
    "businessKey": "user_789abc"
  }
}
```

---

## 4. 核心处理逻辑

### 4.1 归因引擎设计

#### 4.1.1 状态管理
```
用户会话状态 (Keyed State by user_id)
├── pendingClicks: List<ClickEvent> (最近 N 个点击，默认 50 个)
├── sessionStartTime: Long
├── lastActivityTime: Long
└── attributionWindow: Long (归因窗口，默认 7 天)
```

#### 4.1.2 归因流程
```
1. 接收转化事件
2. 从状态中获取用户历史点击列表
3. 过滤有效点击（在归因窗口内）
4. 应用归因模型计算权重
5. 生成归因结果
6. 更新/清理状态
```

### 4.2 支持的归因模型

#### 4.2.1 最后点击归因 (Last Click)
```
- 转化前最后一次点击获得 100% 权重
- 最简单、最常用的归因模型
- 适合转化路径短的场景
```

#### 4.2.2 线性归因 (Linear)
```
- 所有点击平均分配权重
- 每个点击权重 = 1.0 / 点击数量
- 适合长转化路径的场景
```

#### 4.2.3 时间衰减归因 (Time Decay)
```
- 基于时间距离的指数衰减
- 越接近转化的点击权重越高
- 衰减因子可配置（默认 0.5）
- 公式：weight = decay_factor ^ (time_diff / half_life)
```

#### 4.2.4 位置归因 (Position Based)
```
- 首次点击和最后点击各占 40%
- 中间点击平分剩余 20%
- 兼顾拉新和转化
```

### 4.3 失败处理策略

#### 4.3.1 失败分类
| 失败类型 | 描述 | 处理策略 |
|---------|------|---------|
| 瞬时失败 | 网络抖动、临时资源不足 | 立即重试 |
| 状态失败 | 状态访问超时、状态损坏 | 延迟重试 |
| 数据失败 | 格式错误、缺失关键字段 | 延迟重试 + 告警 |
| 业务失败 | 归因规则冲突、数据不一致 | 人工审核 |

#### 4.3.2 重试策略配置
```yaml
retry:
  enabled: true
  max_attempts: 3
  levels:
    - level: 1
      delay_minutes: 5
      max_attempts: 2
    - level: 2
      delay_minutes: 30
      max_attempts: 2
    - level: 3
      delay_minutes: 120
      max_attempts: 1
  dead_letter:
    enabled: true
    topic: attribution-dlq-topic
```

---

## 5. 系统组件详细设计

### 5.1 Flink 作业拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│                     Flink Job Topology                          │
│                                                                  │
│  ┌──────────────┐     ┌──────────────┐                         │
│  │ Fluss Source │     │ Fluss Source │                         │
│  │   (Click)    │     │ (Conversion) │                         │
│  └──────┬───────┘     └──────┬───────┘                         │
│         │                    │                                  │
│         │  KeyBy(userId)     │  KeyBy(userId)                   │
│         │                    │                                  │
│         ▼                    ▼                                  │
│  ┌──────────────┐     ┌──────────────┐                         │
│  │  Click       │     │  Conversion  │                         │
│  │  Enrichment  │     │  Enrichment  │                         │
│  └──────┬───────┘     └──────┬───────┘                         │
│         │                    │                                  │
│         │      KeyBy(userId) │                                  │
│         └──────────┬─────────┘                                  │
│                    │                                            │
│                    ▼                                            │
│           ┌─────────────────┐                                  │
│           │  Attribution    │                                  │
│           │  ProcessFunction│                                  │
│           │  (Stateful)     │                                  │
│           └────────┬────────┘                                  │
│                    │                                            │
│         ┌──────────┴──────────┐                                │
│         │                     │                                │
│         ▼                     ▼                                │
│  ┌─────────────────┐   ┌──────────────┐                       │
│  │ Result Writer   │   │ Retry Handler│                       │
│  │ (Fluss MQ)      │   │ (RocketMQ)   │                       │
│  └────────┬────────┘   └──────────────┘                       │
│           │                                                    │
│    ┌──────┴──────┐                                            │
│    │             │                                            │
│    ▼             ▼                                            │
│ ┌─────────┐ ┌──────────┐                                     │
│ │ Success │ │  Failed  │                                     │
│ │ Results │ │ Results  │                                     │
│ │ (Fluss) │ │ (Fluss)  │                                     │
│ └─────────┘ └──────────┘                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Fluss Stream 配置

#### 5.2.1 Click Events Stream
```java
StreamSchema clickSchema = StreamSchema.builder()
    .field("eventId", DataTypes.STRING())
    .field("eventType", DataTypes.STRING())
    .field("timestamp", DataTypes.BIGINT())
    .field("userId", DataTypes.STRING())
    .field("advertiserId", DataTypes.STRING())
    .field("campaignId", DataTypes.STRING())
    .field("creativeId", DataTypes.STRING())
    .field("attributes", DataTypes.MAP(DataTypes.STRING(), DataTypes.STRING()))
    .primaryKey("eventId")
    .partitionKey("userId")
    .build();
```

#### 5.2.2 Conversion Events Stream
```java
StreamSchema conversionSchema = StreamSchema.builder()
    .field("eventId", DataTypes.STRING())
    .field("eventType", DataTypes.STRING())
    .field("timestamp", DataTypes.BIGINT())
    .field("userId", DataTypes.STRING())
    .field("advertiserId", DataTypes.STRING())
    .field("conversionType", DataTypes.STRING())
    .field("conversionValue", DataTypes.DOUBLE())
    .field("attributes", DataTypes.MAP(DataTypes.STRING(), DataTypes.STRING()))
    .primaryKey("eventId")
    .partitionKey("userId")
    .build();
```

### 5.3 Fluss 结果队列配置

#### 5.3.1 结果 Topic 配置
```java
// 成功结果 Topic
String successTopic = "attribution-results-success";

// 失败结果 Topic
String failedTopic = "attribution-results-failed";

// Table Schema
StreamSchema resultSchema = StreamSchema.builder()
    .field("attribution_id", DataTypes.STRING())
    .field("conversion_event_id", DataTypes.STRING())
    .field("user_id", DataTypes.STRING())
    .field("advertiser_id", DataTypes.STRING())
    .field("attributed_clicks", DataTypes.STRING())  // JSON
    .field("total_value", DataTypes.DOUBLE())
    .field("attribution_model", DataTypes.STRING())
    .field("processing_time", DataTypes.BIGINT())
    .field("processing_node", DataTypes.STRING())
    .field("metadata", DataTypes.STRING())  // JSON
    .field("status", DataTypes.STRING())    // SUCCESS | FAILED
    .field("failure_reason", DataTypes.STRING())  // 仅失败时有值
    .field("create_time", DataTypes.BIGINT())
    .primaryKey("attribution_id")
    .partitionKey("user_id")
    .build();
```

### 5.4 RocketMQ 重试队列配置

#### 5.4.1 Topic 配置
```java
// 重试 Topic
String retryTopic = "attribution-retry-topic";
// 死信 Topic
String dlqTopic = "attribution-dlq-topic";

// 延迟级别映射
Map<Integer, Integer> delayLevelMap = Map.of(
    1, 5,    // Level 1: 5 分钟
    2, 30,   // Level 2: 30 分钟
    3, 120   // Level 3: 2 小时
);
```

---

## 6. 数据输出与集成

### 6.1 归因结果输出

归因引擎将结果输出到 **Apache Fluss 消息队列**，分为两个 Topic：

| Topic | 用途 | 消息格式 | 分区策略 |
|-------|------|---------|---------|
| `attribution-results-success` | 成功的归因结果 | JSON | user_id |
| `attribution-results-failed` | 失败的归因结果 | JSON | user_id |

### 6.2 下游系统集成

下游系统可以通过消费 Fluss Topic 获取归因结果：

```
┌─────────────────────────────────────────────────────────┐
│              归因结果 → Fluss MQ                         │
│                                                          │
│  attribution-results-success                             │
│         │                                                │
│         ├──────────┬──────────┬──────────┐              │
│         ▼          ▼          ▼          ▼              │
│    ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐         │
│    │ MySQL  │ │  Click │  │ 实时  │ │ 自定义│         │
│    │  Sink  │ │ House  │  │ 看板  │ │ 系统  │         │
│    └────────┘ └────────┘ └────────┘ └────────┘         │
│                                                          │
│  attribution-results-failed                              │
│         │                                                │
│         ▼                                                │
│    ┌────────────────┐                                   │
│    │ 失败分析系统    │                                   │
│    │ 人工审核队列    │                                   │
│    └────────────────┘                                   │
└─────────────────────────────────────────────────────────┘
```

### 6.3 典型集成场景

#### 6.3.1 数据仓库集成
```sql
-- Flink SQL 将结果写入数据仓库
CREATE TABLE attribution_results (
    attribution_id STRING,
    user_id STRING,
    advertiser_id STRING,
    total_value DOUBLE,
    attribution_model STRING,
    status STRING,
    create_time BIGINT
) WITH (
    'connector' = 'fluss',
    'bootstrap.servers' = 'fluss:9110',
    'database' = 'attribution',
    'table' = 'attribution-results-success'
);

INSERT INTO attribution_results
SELECT 
    attribution_id,
    user_id,
    advertiser_id,
    total_value,
    attribution_model,
    status,
    create_time
FROM attribution_results_raw;
```

#### 6.3.2 实时看板
```java
// Flink 消费结果 Topic 并聚合
DataStream<AttributionResult> results = env
    .fromSource(new FlussSource<>("attribution-results-success"), ...)
    .keyBy(AttributionResult::getAdvertiserId)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new AttributionAggregator());
```

---

## 7. 部署架构

### 7.1 环境要求

| 组件 | 最低配置 | 推荐配置 | 生产配置 |
|------|---------|---------|---------|
| Flink JobManager | 2C4G | 4C8G | 8C16G |
| Flink TaskManager | 4C8G | 8C16G | 16C32G |
| Fluss Server | 4C8G | 8C16G | 16C32G |
| RocketMQ Broker | 4C8G | 8C16G | 16C32G |
| ZooKeeper | 2C4G (3 节点) | 4C8G (3 节点) | 8C16G (5 节点) |

### 7.2 集群规模规划

#### 6.2.1 小规模（日处理 100 万事件）
```
- Flink: 1 JobManager + 2 TaskManager
- Fluss: 3 节点集群
- RocketMQ: 2 Broker (主从)
```

#### 6.2.2 中规模（日处理 1000 万事件）
```
- Flink: 1 JobManager + 4 TaskManager
- Fluss: 5 节点集群
- RocketMQ: 4 Broker (2 主 2 从)
```

#### 6.2.3 大规模（日处理 1 亿 + 事件）
```
- Flink: 2 JobManager (HA) + 8+ TaskManager
- Fluss: 9+ 节点集群
- RocketMQ: 8 Broker (4 主 4 从)
```

### 7.3 网络拓扑
```
┌─────────────────────────────────────────────────────────┐
│                    数据中心 A                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │   Flink     │  │   Fluss     │  │  RocketMQ   │     │
│  │  Cluster    │  │  Cluster    │  │   Cluster   │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
│         │                │                │             │
│         └────────────────┴────────────────┘             │
│                          │                              │
└──────────────────────────┼──────────────────────────────┘
                           │
                    ┌──────▼──────┐
                    │  负载均衡器  │
                    └──────┬──────┘
                           │
┌──────────────────────────┼──────────────────────────────┐
│                    数据中心 B (灾备)                     │
│                          │                              │
│                   ┌──────▼──────┐                       │
│                   │  数据同步    │                       │
│                   └─────────────┘                       │
└─────────────────────────────────────────────────────────┘
```

---

## 8. 容灾设计

### 8.1 高可用架构

#### 8.1.1 Flink 作业高可用
```yaml
# Flink HA 配置
high-availability: zookeeper
high-availability.zookeeper.quorum: zk1:2181,zk2:2181,zk3:2181
high-availability.storageDir: s3://flink-ha/

# Checkpoint 配置
execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
state.backend: rocksdb
state.checkpoints.dir: s3://flink-checkpoints/
state.savepoints.dir: s3://flink-savepoints/

# 重启策略
restart-strategy: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s
```

#### 8.1.2 Fluss 集群高可用
```yaml
# Fluss 副本配置
replication.factor: 3
min.insync.replicas: 2

# Leader 选举
auto.leader.rebalance.enable: true
leader.imbalance.check.interval.seconds: 300

# 数据持久化
log.flush.interval.messages: 10000
log.flush.interval.ms: 1000
```

#### 8.1.3 RocketMQ 高可用
```yaml
# Broker 配置
brokerClusterName: attribution-cluster
brokerRole: SYNC_MASTER
flushDiskType: SYNC_FLUSH

# 主从配置
# 2 主 2 从架构
broker-a: master (192.168.1.10)
broker-a-s: slave (192.168.1.11)
broker-b: master (192.168.1.12)
broker-b-s: slave (192.168.1.13)
```

### 8.2 故障恢复策略

#### 8.2.1 Flink 作业故障恢复
```
1. JobManager 故障
   - ZooKeeper 检测故障
   - 自动选举新 JobManager
   - 从 Checkpoint 恢复作业
   - 恢复时间：~30 秒

2. TaskManager 故障
   - JobManager 检测故障
   - 重新调度 Task
   - 从 Checkpoint 恢复状态
   - 恢复时间：~10 秒

3. 数据源故障
   - 自动重试连接（最多 3 次）
   - 失败后作业进入 Restarting 状态
   - 恢复后继续消费
```

#### 8.2.2 Fluss 故障恢复
```
1. Broker 故障
   - Controller 检测故障
   - 自动选举新 Leader
   - 客户端自动重连
   - 恢复时间：~5 秒

2. 数据恢复
   - 从副本同步数据
   - 保证数据不丢失
   - 可能有秒级延迟
```

#### 8.2.3 RocketMQ 故障恢复
```
1. Broker 故障
   - NameServer 检测故障
   - 客户端自动切换到从节点
   - 消息自动同步
   - 恢复时间：~3 秒

2. 消息恢复
   - 从节点提供读服务
   - 保证消息不丢失
   - 消费者自动重试
```

### 8.3 数据一致性保证

#### 8.3.1 End-to-End 精确一次
```
1. Flink Checkpoint 机制
   - Barrier 对齐
   - 两阶段提交（2PC）
   - 状态快照

2. Fluss 事务支持
   - 事务性写入
   - 幂等消费

3. RocketMQ 事务消息
   - 半消息机制
   - 事务回查
```

#### 8.3.2 数据一致性级别
| 场景 | 一致性级别 | 说明 |
|------|-----------|------|
| Click 存储 | 强一致 | Fluss 多副本同步 |
| 归因计算 | 精确一次 | Flink Checkpoint |
| 结果输出 | 精确一次 | 事务性写入 |
| 重试机制 | 至少一次 | RocketMQ 保证 |

---

## 9. 监控与运维

### 8.1 核心监控指标

#### 7.1.1 Flink 作业指标
- **吞吐量**: 每秒处理事件数
- **延迟**: 事件处理延迟（P50, P95, P99）
- **背压**: Task 背压状态
- **Checkpoint**: 检查点成功率和耗时
- **重启次数**: 作业重启频率

#### 7.1.2 业务指标
- **归因成功率**: 成功归因的转化占比
- **重试率**: 进入重试队列的事件占比
- **平均归因时间**: 点击到转化的平均时间
- **归因模型分布**: 各归因模型的使用情况

#### 7.1.3 系统指标
- **Fluss 吞吐量**: 消息生产/消费速率
- **RocketMQ 积压**: 重试队列消息积压量
- **存储使用率**: 状态存储使用情况

### 8.2 告警策略

| 告警级别 | 触发条件 | 响应时间 | 通知方式 |
|---------|---------|---------|---------|
| P0 - 严重 | 作业失败、数据丢失 | 5 分钟 | 电话 + 短信 + IM |
| P1 - 高 | 延迟 > 5 分钟、重试率 > 10% | 15 分钟 | 短信 + IM |
| P2 - 中 | 延迟 > 1 分钟、重试率 > 5% | 1 小时 | IM |
| P3 - 低 | 资源使用率 > 80% | 4 小时 | 邮件 |

---

## 9. 扩展性设计

### 8.1 水平扩展
- **Flink 并行度**: 按 userId 分区，可线性扩展
- **Fluss 分区**: 增加分区数提升吞吐量
- **RocketMQ 队列**: 增加队列数提升并发

### 8.2 功能扩展
- **新归因模型**: 插件化设计，支持自定义归因算法
- **A/B 测试**: 支持不同归因模型对比
- **实时仪表板**: 归因结果可视化展示
- **API 接口**: 外部系统集成

### 8.3 数据扩展
- **多广告源**: 支持多个广告平台数据接入
- **多渠道转化**: 支持线上/线下多渠道转化追踪
- **跨设备归因**: 支持跨设备用户识别和归因

---

## 10. 配置管理

### 10.1 配置中心

#### 10.1.1 配置存储
- **配置中心**: Nacos / Apollo
- **配置格式**: YAML
- **版本管理**: Git
- **热更新**: 支持

#### 10.1.2 配置分类
```yaml
# 1. 应用配置
application:
  name: simple-attribute-system
  version: 1.0.0
  environment: production

# 2. Flink 作业配置
flink:
  job.name: attribution-engine
  parallelism: 4
  checkpoint.interval: 60000
  state.backend: rocksdb

# 3. 业务配置
attribution:
  window.days: 7
  window.ms: 604800000
  model: LAST_CLICK
  max_clicks_per_user: 50
  dedup.enabled: true

# 4. Fluss 配置
fluss:
  bootstrap.servers: fluss:9110
  kv.table: attribution-clicks
  success.topic: attribution-results-success
  failed.topic: attribution-results-failed
  
# 5. RocketMQ 配置
rocketmq:
  name.server: rmq-nameserver:9876
  producer.group: attribution-producer
  retry.topic: attribution-retry-topic
  dlq.topic: attribution-dlq-topic
  
# 6. 重试配置
retry:
  enabled: true
  max.attempts: 3
  levels:
    - level: 1
      delay.minutes: 5
      max.attempts: 2
    - level: 2
      delay.minutes: 30
      max.attempts: 2
    - level: 3
      delay.minutes: 120
      max.attempts: 1
  dead.letter:
    enabled: true
    send.alert: true
```

### 10.2 配置热更新

#### 10.2.1 支持热更新的配置
- 归因窗口大小
- 归因模型切换
- 重试策略配置
- 日志级别

#### 10.2.2 配置变更流程
```
1. 配置中心更新配置
2. Nacos 推送变更通知
3. 应用监听配置变更
4. 动态更新运行时配置
5. 记录配置变更日志
```

### 10.3 配置版本管理

#### 10.3.1 Git 版本控制
```
config/
├── base.yaml           # 基础配置
├── application.yaml    # 应用配置
├── flink.yaml         # Flink 配置
├── fluss.yaml         # Fluss 配置
├── rocketmq.yaml      # RocketMQ 配置
└── environments/
    ├── dev.yaml       # 开发环境
    ├── test.yaml      # 测试环境
    └── prod.yaml      # 生产环境
```

#### 10.3.2 配置变更审批
- 开发环境：直接变更
- 测试环境：Team Leader 审批
- 生产环境：变更委员会审批

---

## 11. 安全与合规

### 9.1 数据安全
- **传输加密**: TLS 1.3 加密传输
- **存储加密**: 敏感数据加密存储
- **访问控制**: RBAC 权限管理
- **审计日志**: 操作审计追踪

### 9.2 隐私保护
- **数据脱敏**: 用户标识符脱敏处理
- **数据保留**: 自动清理过期数据
- **合规性**: 符合 GDPR/个人信息保护法要求

---

## 12. 性能优化

### 12.1 Fluss KV 性能优化

#### 12.1.1 本地缓存
```java
/**
 * 带本地缓存的 Fluss KV 客户端
 */
public class CachedFlussKVClient {
    
    // Caffeine 本地缓存
    private final Cache<FlussKVKey, FlussKVValue> cache;
    private final FlussKVClient delegate;
    
    public CachedFlussKVClient(FlussKVClient delegate) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
            .maximumSize(10000)  // 最多缓存 10000 个用户
            .expireAfterWrite(5, TimeUnit.MINUTES)  // 5 分钟过期
            .recordStats()
            .build();
    }
    
    public CompletableFuture<FlussKVValue> get(FlussKVKey key) {
        // 先查缓存
        FlussKVValue cached = cache.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        // 缓存未命中，查询 Fluss
        return delegate.get(key).thenApply(value -> {
            cache.put(key, value);
            return value;
        });
    }
    
    public CompletableFuture<Void> put(FlussKVKey key, FlussKVValue value) {
        // 写入 Fluss
        return delegate.put(key, value).thenRun(() -> {
            // 更新缓存
            cache.put(key, value);
        });
    }
}
```

#### 12.1.2 批量写入
```java
/**
 * 批量写入器
 */
public class BatchFlussWriter {
    
    private final List<Map.Entry<FlussKVKey, FlussKVValue>> buffer = new ArrayList<>();
    private final int batchSize;
    private final long batchIntervalMs;
    private final FlussKVClient client;
    
    public BatchFlussWriter(FlussKVClient client, int batchSize, long batchIntervalMs) {
        this.client = client;
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;
        
        // 定时刷新
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(
            this::flush,
            batchIntervalMs,
            batchIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }
    
    public synchronized void write(FlussKVKey key, FlussKVValue value) {
        buffer.add(new AbstractMap.SimpleEntry<>(key, value));
        
        // 达到批量大小，立即刷新
        if (buffer.size() >= batchSize) {
            flush();
        }
    }
    
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        
        // 批量写入
        client.batchPut(new ArrayList<>(buffer)).join();
        buffer.clear();
    }
}
```

### 12.2 归因结果批量写入

#### 12.2.1 批量配置
```yaml
result:
  batch:
    enabled: true
    size: 100        # 批量大小
    interval.ms: 1000  # 批量间隔（1 秒）
    timeout.ms: 5000   # 写入超时
```

#### 12.2.2 批量写入器
```java
public class BatchAttributionResultWriter {
    
    private final List<AttributionResult> buffer = new ArrayList<>();
    private final int batchSize;
    private final Table successTable;
    private final Table failedTable;
    
    public BatchAttributionResultWriter(Table successTable, Table failedTable, int batchSize) {
        this.successTable = successTable;
        this.failedTable = failedTable;
        this.batchSize = batchSize;
    }
    
    public synchronized void writeSuccess(AttributionResult result) {
        buffer.add(result);
        
        if (buffer.size() >= batchSize) {
            flush();
        }
    }
    
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        
        // 批量构建 Row
        List<Row> rows = buffer.stream()
            .map(this::buildRow)
            .collect(Collectors.toList());
        
        // 批量写入
        for (Row row : rows) {
            successTable.write(row);
        }
        
        buffer.clear();
    }
}
```

### 12.3 异步 IO 优化

#### 12.3.1 异步配置
```yaml
async:
  enabled: true
  concurrency: 10      # 并发度
  timeout.ms: 3000     # 超时时间
  max.pending: 1000    # 最大待处理
```

#### 12.3.2 异步 IO 实现
```java
public class AsyncAttributionProcessFunction 
    extends KeyedProcessFunction<String, CallbackData, AttributionOutput> {
    
    // 异步 IO 客户端
    private transient AsyncIOPool<FlussKVClient> asyncPool;
    
    @Override
    public void open(OpenContext openContext) throws Exception {
        super.open(openContext);
        
        // 创建异步 IO 池
        asyncPool = AsyncIOPool.builder()
            .factory(this::createKVClient)
            .maxConcurrency(10)
            .timeout(3000, TimeUnit.MILLISECONDS)
            .build();
    }
    
    @Override
    public void processElement(CallbackData data, Context ctx, Collector<AttributionOutput> out) {
        // 异步处理
        AsyncDataStream.unorderedWait(
            dataStream,
            new AttributionAsyncFunction(asyncPool),
            3000,
            TimeUnit.MILLISECONDS,
            100  // 最大待处理
        );
    }
}
```

### 12.4 性能基准

#### 12.4.1 预期性能指标
| 指标 | 目标值 | 说明 |
|------|--------|------|
| 吞吐量 | > 100,000 events/s | 单 TaskManager |
| 延迟 P50 | < 100ms | 端到端延迟 |
| 延迟 P99 | < 1s | 端到端延迟 |
| Checkpoint 耗时 | < 30s | 1TB 状态 |
| 恢复时间 | < 1 分钟 | 故障恢复 |

#### 12.4.2 性能测试方案
```
1. 基准测试
   - 单节点性能
   - 多节点扩展性
   
2. 压力测试
   - 峰值流量测试
   - 长时间稳定性测试
   
3. 故障测试
   - 节点故障恢复
   - 网络分区测试
```

---

## 13. 附录

### 10.1 术语表
| 术语 | 定义 |
|------|------|
| 归因 (Attribution) | 将转化归功于特定广告点击的过程 |
| 归因窗口 (Attribution Window) | 点击后有效的归因时间范围 |
| 转化 (Conversion) | 用户完成的期望行为（如购买、注册） |
| 点击 (Click) | 用户点击广告的行为 |

### 10.2 参考资料
- Apache Flink 官方文档: https://flink.apache.org/
- Apache Fluss 官方文档: https://fluss.apache.org/
- RocketMQ 官方文档: https://rocketmq.apache.org/

---

**文档结束**

*此文档为 SimpleAttributeSystem 的核心架构设计文档，后续将根据实际开发情况进行更新和完善。*
