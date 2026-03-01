# SimpleAttributeSystem - 分布式架构设计文档 v2.1

> 基于 Apache Flink + Apache Fluss + RocketMQ 的实时广告归因系统  
> **分布式隔离部署版本**

**版本**: v2.1  
**创建时间**: 2026-02-24  
**最后更新**: 2026-02-24  
**变更说明**: 重构为分布式隔离部署，Click 写入与归因计算分离，集成 RocketMQ 重试能力

---

## 1. 架构变更概述

### 1.1 核心设计原则

本次架构重构基于以下核心原则：

1. **任务隔离**: Click 数据写入与归因计算分离为独立任务
2. **数据集中**: 所有 Click 数据统一存储到 Fluss，作为单一事实源
3. **无状态归因**: 归因任务从 Fluss 查询 Click 数据，不维护本地状态
4. **重试内建**: RocketMQ 重试能力深度集成到数据流中

### 1.2 架构演进对比

#### v1.x 架构（单体式）
```
┌─────────────────────────────────────────┐
│         单一 Flink 作业                  │
│  ┌─────────────────────────────────┐   │
│  │  Click Source → State → Result  │   │
│  │  Conv Source   ↑                │   │
│  └─────────────────────────────────┘   │
│         所有逻辑在一个作业中             │
└─────────────────────────────────────────┘
```

#### v2.1 架构（分布式隔离）
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Click Writer  │    │  Attribution    │    │  Retry          │
│   Task          │    │  Task           │    │  Consumer       │
│                 │    │                 │    │                 │
│  写入 Fluss KV  │───▶│  查询 Fluss KV  │───▶│  处理失败       │
│                 │    │  计算归因       │    │  重试逻辑       │
└─────────────────┘    └─────────────────┘    └─────────────────┘
       ↓                       ↓                       ↓
  Fluss KV Store         Kafka Results         RocketMQ DLQ
```

---

## 2. 整体架构

### 2.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           数据产生层                                     │
│  ┌──────────────────┐                      ┌──────────────────┐         │
│  │   广告点击事件    │                      │   用户转化事件    │         │
│  │   Click Events   │                      │ Conversion Events│         │
│  └────────┬─────────┘                      └────────┬─────────┘         │
└───────────┼─────────────────────────────────────────┼────────────────────┘
            │                                         │
            ▼                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      Apache Fluss 集群 (统一数据层)                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  KV Store: attribution-clicks (Key: user_id, Value: Click List) │   │
│  │  MQ Stream: conversion-events-stream (分区键：user_id)           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
            │                                         │
            │ (Click 数据写入)                          │ (转化事件流)
            ▼                                         ▼
┌──────────────────────────┐         ┌──────────────────────────────────┐
│   Task 1: Click Writer   │         │   Task 2: Attribution Engine     │
│                          │         │                                   │
│  ┌────────────────────┐  │         │  ┌────────────────────────────┐  │
│  │  Click Source      │  │         │  │  Conversion Source (Fluss) │  │
│  │  (Kafka/RocketMQ)  │  │         │  └────────────┬───────────────┘  │
│  └─────────┬──────────┘  │         │               │                  │
│            │             │         │               ▼                  │
│  ┌─────────▼──────────┐  │         │  ┌────────────────────────────┐  │
│  │  Click Validator   │  │         │  │  Fluss KV Client           │  │
│  └─────────┬──────────┘  │         │  │  - Query user clicks       │  │
│            │             │         │  │  - Async, non-blocking     │  │
│  ┌─────────▼──────────┐  │         │  └────────────┬───────────────┘  │
│  │  Fluss KV Writer   │  │         │               │                  │
│  │  - Batch write     │  │         │               ▼                  │
│  │  - Retry on fail   │  │         │  ┌────────────────────────────┐  │
│  └─────────┬──────────┘  │         │  │  Attribution Engine        │  │
│            │             │         │  │  - Load clicks from Fluss  │  │
│            ▼             │         │  │  - Apply attribution model │  │
│  ┌────────────────────┐  │         │  │  - Generate results        │  │
│  │  Fluss KV Store    │◄─┼─────────┼─►│  └────────────┬───────────────┘  │
│  │  (Click Sessions)  │  │         │               │                  │
│  └────────────────────┘  │         │               │                  │
└──────────────────────────┘         │      ┌────────┴────────┐         │
                                     │      ▼                 ▼         │
                                     │  ┌─────────┐    ┌──────────┐    │
                                     │  │ Success │    │  Failed  │    │
                                     │  │ Results │    │  Results │    │
                                     │  │ (Kafka) │    │ (Kafka)  │    │
                                     │  └─────────┘    └────┬─────┘    │
                                     │                      │           │
                                     │                      ▼           │
                                     │           ┌─────────────────┐   │
                                     │           │ RocketMQ Retry  │   │
                                     │           │ Sink            │   │
                                     │           └────────┬────────┘   │
                                     └────────────────────┼────────────┘
                                                          │
                                         ┌────────────────┼────────────────┐
                                         │                │                │
                                         ▼                ▼                ▼
                                  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
                                  │  Retry < 10 │  │  Retry = 10 │  │  Retry > 10 │
                                  │  (Delay)    │  │  (DLQ)      │  │  (DLQ)      │
                                  └──────┬──────┘  └─────────────┘  └─────────────┘
                                         │
                                         ▼
                                  ┌──────────────────────────┐
                                  │  Task 3: Retry Consumer  │
                                  │                          │
                                  │  - Consume retry messages│
                                  │  - Re-process attribution│
                                  │  - Send to success/DLQ   │
                                  └──────────────────────────┘
```

### 2.2 任务分解

| 任务编号 | 任务名称 | 职责 | 技术栈 | 部署单元 |
|---------|---------|------|--------|---------|
| **Task 1** | Click Writer Job | 接收 Click 事件，写入 Fluss KV | Flink + Fluss | 独立 Flink Job |
| **Task 2** | Attribution Engine Job | 消费 Conversion 事件，查询 Fluss，计算归因 | Flink + Fluss | 独立 Flink Job |
| **Task 3** | Retry Consumer App | 消费 RocketMQ 重试消息，重新处理归因 | Java App | 独立进程 |

---

## 3. Task 1: Click Writer Job

### 3.1 职责

- 从数据源（Kafka/RocketMQ）消费 Click 事件
- 验证 Click 数据格式和完整性
- 批量写入 Fluss KV Store
- 处理写入失败（本地重试 + 发送到死信队列）

### 3.2 数据流

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Kafka     │    │   Flink     │    │   Fluss     │    │   Fluss     │
│   Source    │───▶│  Validator  │───▶│  KV Writer  │───▶│  KV Store   │
│  (Clicks)   │    │             │    │  (Batch)    │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                          │                  │
                          │                  │ (失败)
                          ▼                  ▼
                   ┌─────────────┐    ┌─────────────┐
                   │   DLQ       │    │  RocketMQ   │
                   │  (Kafka)    │    │  (Retry)    │
                   └─────────────┘    └─────────────┘
```

### 3.3 核心代码结构

```java
public class ClickWriterJob {
    
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // 1. Click Source
        DataStream<ClickEvent> clickStream = env
            .addSource(new FlinkKafkaConsumer<>("click-events", ...))
            .name("Click Source");
        
        // 2. 验证
        DataStream<ClickEvent> validClicks = clickStream
            .filter(ClickWriterJob::validateClick)
            .name("Click Validator");
        
        // 3. 无效 Click 发送到 DLQ
        DataStream<ClickEvent> invalidClicks = clickStream
            .filter(click -> !validateClick(click))
            .name("Invalid Click Filter");
        
        invalidClicks.addSink(new KafkaSink<>("click-dlq", ...))
            .name("Click DLQ Sink");
        
        // 4. 批量写入 Fluss KV
        validClicks
            .keyBy(ClickEvent::getUserId)
            .process(new FlussKVBatchWriterProcessFunction(
                "attribution-clicks",
                100,     // batch size
                1000L    // batch interval ms
            ))
            .name("Fluss KV Writer");
        
        env.execute("Click Writer Job");
    }
    
    private static boolean validateClick(ClickEvent click) {
        return click.getUserId() != null &&
               click.getEventId() != null &&
               click.getTimestamp() != null &&
               click.getAdvertiserId() != null;
    }
}
```

### 3.4 Fluss KV 写入优化

```java
public class FlussKVBatchWriterProcessFunction 
    extends KeyedProcessFunction<String, ClickEvent, Void> {
    
    private transient FlussKVClient kvClient;
    private transient List<ClickEvent> buffer;
    private transient long lastFlushTime;
    
    private final String tableName;
    private final int batchSize;
    private final long batchIntervalMs;
    
    @Override
    public void open(OpenContext openContext) throws Exception {
        this.kvClient = new FlussKVClientImpl(tableName, ...);
        this.buffer = new ArrayList<>();
        this.lastFlushTime = System.currentTimeMillis();
    }
    
    @Override
    public void processElement(ClickEvent click, Context ctx, Collector<Void> out) {
        buffer.add(click);
        
        // 批量条件：达到批量大小 或 达到时间间隔
        if (buffer.size() >= batchSize || 
            System.currentTimeMillis() - lastFlushTime >= batchIntervalMs) {
            flush();
        }
    }
    
    private void flush() {
        if (buffer.isEmpty()) return;
        
        // 按 userId 分组
        Map<String, List<ClickEvent>> grouped = buffer.stream()
            .collect(Collectors.groupingBy(ClickEvent::getUserId));
        
        // 批量更新 Fluss KV
        List<CompletableFuture<Void>> futures = grouped.entrySet().stream()
            .map(entry -> {
                FlussKVKey key = new FlussKVKey(entry.getKey());
                return kvClient.update(key, currentValue -> {
                    currentValue.addClicks(entry.getValue(), MAX_CLICKS_PER_USER);
                    return currentValue;
                });
            })
            .collect(Collectors.toList());
        
        // 等待所有写入完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .join();
        
        buffer.clear();
        lastFlushTime = System.currentTimeMillis();
    }
}
```

### 3.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `click.source.topic` | click-events | Click 数据源 Topic |
| `click.source.bootstrap.servers` | kafka:9092 | Kafka 地址 |
| `fluss.kv.table.name` | attribution-clicks | Fluss KV Table 名称 |
| `fluss.batch.size` | 100 | 批量写入大小 |
| `fluss.batch.interval.ms` | 1000 | 批量写入间隔 |
| `click.max.per.user` | 50 | 单用户最大 Click 数 |
| `parallelism` | 4 | Flink 并行度 |

---

## 4. Task 2: Attribution Engine Job

### 4.1 职责

- 从 Fluss 消费 Conversion 事件流
- 异步查询 Fluss KV 获取用户 Click 历史
- 执行归因计算
- 输出归因结果到 Kafka
- 失败结果发送到 RocketMQ 重试队列

### 4.2 数据流

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Fluss     │    │   Flink     │    │   Fluss     │
│  Conversion │───▶│ Attribution │───▶│  Results    │
│  Stream     │    │   Engine    │    │  (Success)  │
└─────────────┘    └──────┬──────┘    └─────────────┘
                          │
                          │ (查询 Click)
                          ▼
                   ┌─────────────┐
                   │   Fluss     │
                   │   KV Store  │
                   └─────────────┘
                          │
                          │ (失败)
                          ▼
                   ┌─────────────┐    ┌─────────────┐
                   │   Fluss     │    │  RocketMQ   │
                   │  Results    │───▶│   Retry     │
                   │  (Failed)   │    │   Queue     │
                   └─────────────┘    └─────────────┘
```

### 4.3 核心代码结构

```java
public class AttributionEngineJob {
    
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // 1. Conversion Source (from Fluss)
        DataStream<ConversionEvent> conversionStream = env
            .fromSource(new FlussSource<>("conversion-events-stream"), ...)
            .name("Conversion Source");
        
        // 2. Attribution Process
        DataStream<AttributionResult> resultStream = conversionStream
            .keyBy(ConversionEvent::getUserId)
            .process(new AttributionProcessFunction(
                604800000L,  // attribution window (7 days)
                50,          // max clicks per user
                AttributionModel.LAST_CLICK,
                new MetricsReporter()
            ))
            .name("Attribution Engine");
        
        // 3. 分流：成功 vs 失败
        DataStream<AttributionResult> successResults = resultStream
            .filter(r -> "SUCCESS".equals(r.getStatus()))
            .name("Success Filter");
        
        DataStream<AttributionResult> failedResults = resultStream
            .filter(r -> "FAILED".equals(r.getStatus()))
            .name("Failed Filter");
        
        // 4. 成功结果 → Kafka
        successResults.addSink(new KafkaSink<>("attribution-results-success", ...))
            .name("Success Result Sink");
        
        // 5. 失败结果 → Kafka + RocketMQ Retry
        failedResults
            .addSink(new MultiSink<>(
                new KafkaSink<>("attribution-results-failed", ...),
                new RocketMQRetrySink<>(...)
            ))
            .name("Failed Result Sink");
        
        env.execute("Attribution Engine Job");
    }
}
```

### 4.4 AttributionProcessFunction 核心逻辑

```java
public class AttributionProcessFunction 
    extends KeyedProcessFunction<String, ConversionEvent, AttributionResult> {
    
    private transient FlussKVClient kvClient;
    private transient AttributionResultWriter resultWriter;
    private transient RocketMQRetrySink retrySink;
    
    private final long attributionWindowMs;
    private final int maxClicksPerUser;
    private final AttributionModel attributionModel;
    
    @Override
    public void open(OpenContext openContext) throws Exception {
        // 初始化 Fluss KV Client
        this.kvClient = new FlussKVClientImpl("attribution-clicks", ...);
        
        // 初始化结果写入器
        this.resultWriter = new AttributionResultWriter(...);
        
        // 初始化重试 Sink
        this.retrySink = new RocketMQRetrySink(...);
    }
    
    @Override
    public void processElement(ConversionEvent conv, Context ctx, Collector<AttributionResult> out) {
        try {
            // 1. 异步查询 Fluss KV 获取 Click 历史
            FlussKVKey key = new FlussKVKey(conv.getUserId());
            FlussKVValue kvValue = kvClient.get(key)
                .get(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            // 2. 过滤有效 Click（归因窗口内）
            List<ClickEvent> validClicks = kvValue.getValidClicks(
                conv.getTimestamp(), 
                attributionWindowMs
            );
            
            // 3. 检查是否有可归因的 Click
            if (validClicks.isEmpty()) {
                AttributionResult result = buildUnmatchedResult(conv);
                out.collect(result);
                return;
            }
            
            // 4. 应用归因模型
            List<AttributedClick> attributedClicks = attributionModel.calculate(
                validClicks, 
                conv
            );
            
            // 5. 生成归因结果
            AttributionResult result = buildSuccessResult(conv, attributedClicks);
            out.collect(result);
            
        } catch (Exception e) {
            // 6. 失败处理
            AttributionResult failedResult = buildFailedResult(conv, e.getMessage());
            out.collect(failedResult);
        }
    }
}
```

### 4.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `conversion.source.table` | conversion-events-stream | Conversion 数据源 |
| `fluss.kv.table.name` | attribution-clicks | Click KV Table |
| `attribution.window.ms` | 604800000 | 归因窗口（7 天） |
| `attribution.model` | LAST_CLICK | 归因模型 |
| `async.timeout.ms` | 5000 | 异步查询超时 |
| `result.success.topic` | attribution-results-success | 成功结果 Topic |
| `result.failed.topic` | attribution-results-failed | 失败结果 Topic |
| `rocketmq.retry.topic` | attribution-retry | 重试 Topic |
| `parallelism` | 8 | Flink 并行度 |

---

## 5. Task 3: Retry Consumer App

### 5.1 职责

- 消费 RocketMQ 重试队列消息
- 重新执行归因计算
- 成功 → 发送到 Kafka 成功队列
- 失败 → 根据重试次数决定重试或发送到 DLQ

### 5.2 数据流

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  RocketMQ   │    │   Retry     │    │   Fluss     │
│   Retry     │───▶│  Consumer   │───▶│  Results    │
│   Queue     │    │   App       │    │  (Success)  │
└─────────────┘    └──────┬──────┘    └─────────────┘
                          │
                          │ (失败)
                          ▼
                   ┌─────────────┐
                   │  Retry < 10 │
                   │  → RocketMQ │
                   │  (Delay)    │
                   └─────────────┘
                          │
                          │ (失败且 Retry >= 10)
                          ▼
                   ┌─────────────┐
                   │   RocketMQ  │
                   │   DLQ       │
                   └─────────────┘
```

### 5.3 核心代码结构

```java
public class RetryConsumerApplication {
    
    public static void main(String[] args) throws Exception {
        // 1. 初始化配置
        RocketMQConsumerConfig config = RocketMQConsumerConfig.builder()
            .nameServerAddress("rocketmq-namesrv:9876")
            .consumerGroup("attribution-retry-consumer-group")
            .retryTopic("attribution-retry")
            .maxRetries(10)
            .build();
        
        // 2. 创建消费者
        DefaultLitePullConsumer consumer = new DefaultLitePullConsumer(
            config.getConsumerGroup()
        );
        consumer.setNamesrvAddr(config.getNameServerAddress());
        consumer.subscribe(config.getRetryTopic());
        consumer.start();
        
        // 3. 初始化组件
        FlussKVClient kvClient = new FlussKVClientImpl("attribution-clicks", ...);
        AttributionEngine engine = new AttributionEngine();
        KafkaProducer successProducer = new KafkaProducer("attribution-results-success", ...);
        RocketMQProducer retryProducer = new RocketMQProducer(...);
        
        // 4. 消费循环
        while (true) {
            List<MessageExt> messages = consumer.poll(1000);
            
            for (MessageExt msg : messages) {
                try {
                    // 解析重试消息
                    AttributionResult failedResult = parseMessage(msg);
                    
                    // 检查重试次数
                    int retryCount = failedResult.getRetryCount();
                    if (retryCount >= config.getMaxRetries()) {
                        // 发送到 DLQ
                        sendToDLQ(failedResult, retryProducer);
                        consumer.commitSync();
                        continue;
                    }
                    
                    // 重新执行归因
                    AttributionResult newResult = reprocessAttribution(
                        failedResult, 
                        kvClient, 
                        engine
                    );
                    
                    // 发送结果
                    if ("SUCCESS".equals(newResult.getStatus())) {
                        successProducer.send(newResult);
                        consumer.commitSync();
                    } else {
                        // 增加重试次数，重新发送到重试队列
                        newResult.setRetryCount(retryCount + 1);
                        int nextDelayLevel = calculateDelayLevel(retryCount + 1);
                        retryProducer.sendWithDelay(
                            config.getRetryTopic(), 
                            newResult, 
                            nextDelayLevel
                        );
                        consumer.commitSync();
                    }
                    
                } catch (Exception e) {
                    log.error("Failed to process retry message", e);
                    consumer.commitSync(); // 避免死循环
                }
            }
        }
    }
    
    private static int calculateDelayLevel(int retryCount) {
        // RocketMQ 延迟级别映射
        Map<Integer, Integer> delayLevelMap = Map.ofEntries(
            Map.entry(1, 1),   // 1s
            Map.entry(2, 2),   // 5s
            Map.entry(3, 3),   // 10s
            Map.entry(4, 4),   // 30s
            Map.entry(5, 5),   // 1m
            Map.entry(6, 6),   // 5m
            Map.entry(7, 7),   // 10m
            Map.entry(8, 8),   // 30m
            Map.entry(9, 9),   // 1h
            Map.entry(10, 10)  // 2h
        );
        return delayLevelMap.getOrDefault(retryCount, 10);
    }
}
```

### 5.4 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rocketmq.nameserver.address` | rocketmq-namesrv:9876 | NameServer 地址 |
| `rocketmq.consumer.group` | attribution-retry-consumer-group | 消费者组 |
| `rocketmq.retry.topic` | attribution-retry | 重试 Topic |
| `rocketmq.dlq.topic` | attribution-retry_DLQ | 死信队列 Topic |
| `retry.max.count` | 10 | 最大重试次数 |
| `consume.thread.min` | 4 | 最小消费线程 |
| `consume.thread.max` | 16 | 最大消费线程 |
| `fluss.kv.table.name` | attribution-clicks | Click KV Table |
| `result.success.topic` | attribution-results-success | 成功结果 Topic |

---

## 6. RocketMQ 重试能力详解

### 6.1 延迟级别映射

RocketMQ 提供 18 个延迟级别，我们使用其中 10 个：

| 级别 | 延迟时间 | 重试次数 | 适用场景 |
|------|---------|---------|---------|
| 1 | 1 秒 | 第 1 次 | 瞬时失败（网络抖动） |
| 2 | 5 秒 | 第 2 次 | 临时资源不足 |
| 3 | 10 秒 | 第 3 次 | 状态访问超时 |
| 4 | 30 秒 | 第 4 次 | 数据不一致 |
| 5 | 1 分钟 | 第 5 次 | 外部依赖故障 |
| 6 | 5 分钟 | 第 6 次 | 严重故障 |
| 7 | 10 分钟 | 第 7 次 | 系统级故障 |
| 8 | 30 分钟 | 第 8 次 | 长时间故障 |
| 9 | 1 小时 | 第 9 次 | 严重系统故障 |
| 10 | 2 小时 | 第 10 次 | 最终尝试 |

### 6.2 重试消息格式

```json
{
  "retryId": "retry_1708512000000_a1b2c3d4",
  "originalEventType": "CONVERSION",
  "originalEvent": {
    "eventId": "conv_789xyz",
    "userId": "user_123456",
    "timestamp": 1708512000000,
    "conversionType": "PURCHASE",
    "conversionValue": 299.99,
    ...
  },
  "failureReason": "FLUSS_KV_READ_TIMEOUT",
  "failureTimestamp": 1708512001000,
  "retryCount": 2,
  "maxRetries": 10,
  "nextRetryTime": 1708512011000,
  "delayLevel": 3,
  "retryHistory": [
    {
      "attemptTime": 1708512001000,
      "failureReason": "FLUSS_KV_READ_TIMEOUT",
      "delayLevel": 1
    },
    {
      "attemptTime": 1708512006000,
      "failureReason": "FLUSS_KV_READ_TIMEOUT",
      "delayLevel": 2
    }
  ],
  "metadata": {
    "originalSource": "flink-attribution-engine",
    "businessKey": "user_123456",
    "priority": "NORMAL",
    "advertiserId": "adv_001"
  }
}
```

### 6.3 重试策略配置

```yaml
# retry-config.yaml
retry:
  enabled: true
  
  # 最大重试次数
  max_attempts: 10
  
  # 延迟级别配置
  delay_levels:
    - level: 1
      delay_seconds: 1
      max_attempts: 1
    - level: 2
      delay_seconds: 5
      max_attempts: 1
    - level: 3
      delay_seconds: 10
      max_attempts: 1
    - level: 4
      delay_seconds: 30
      max_attempts: 1
    - level: 5
      delay_seconds: 60
      max_attempts: 1
    - level: 6
      delay_seconds: 300
      max_attempts: 1
    - level: 7
      delay_seconds: 600
      max_attempts: 1
    - level: 8
      delay_seconds: 1800
      max_attempts: 1
    - level: 9
      delay_seconds: 3600
      max_attempts: 1
    - level: 10
      delay_seconds: 7200
      max_attempts: 1
  
  # 死信队列配置
  dead_letter:
    enabled: true
    topic: attribution-retry_DLQ
    alert_enabled: true
    alert_threshold: 100  # DLQ 消息超过 100 条时告警
```

### 6.4 失败分类与重试策略

| 失败类型 | 描述 | 重试策略 | 告警 |
|---------|------|---------|------|
| **瞬时失败** | 网络抖动、连接超时 | 立即重试（Level 1-2） | 否 |
| **状态失败** | Fluss KV 访问超时、状态损坏 | 延迟重试（Level 3-5） | 是（超过 5 次） |
| **数据失败** | 格式错误、缺失关键字段 | 延迟重试（Level 5-7） | 是（超过 3 次） |
| **业务失败** | 归因规则冲突、数据不一致 | 延迟重试（Level 7-10） | 是（立即） |
| **永久失败** | 不可恢复的错误 | 发送到 DLQ | 是（立即） |

---

## 7. 部署架构

### 7.1 集群规模规划

#### 小规模（日处理 100 万事件）
```
- Click Writer Job: 1 JobManager + 2 TaskManager (4 slots)
- Attribution Engine Job: 1 JobManager + 2 TaskManager (4 slots)
- Retry Consumer App: 2 实例（主备）
- Fluss: 3 节点集群
- RocketMQ: 2 Broker (1 主 1 从)
```

#### 中规模（日处理 1000 万事件）
```
- Click Writer Job: 1 JobManager + 4 TaskManager (8 slots)
- Attribution Engine Job: 1 JobManager + 4 TaskManager (8 slots)
- Retry Consumer App: 4 实例（负载均衡）
- Fluss: 5 节点集群
- RocketMQ: 4 Broker (2 主 2 从)
```

#### 大规模（日处理 1 亿 + 事件）
```
- Click Writer Job: 2 JobManager (HA) + 8+ TaskManager (16+ slots)
- Attribution Engine Job: 2 JobManager (HA) + 8+ TaskManager (16+ slots)
- Retry Consumer App: 8+ 实例（K8s 自动扩缩容）
- Fluss: 9+ 节点集群
- RocketMQ: 8 Broker (4 主 4 从)
```

### 7.2 部署拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│                      Kubernetes Cluster                          │
│                                                                  │
│  ┌─────────────────────┐      ┌─────────────────────┐          │
│  │  Flink Namespace    │      │  App Namespace      │          │
│  │                     │      │                     │          │
│  │  ┌───────────────┐  │      │  ┌───────────────┐  │          │
│  │  │ Click Writer  │  │      │  │ Retry         │  │          │
│  │  │ Job (Flink)   │  │      │  │ Consumer (×4) │  │          │
│  │  └───────────────┘  │      │  └───────────────┘  │          │
│  │                     │      │                     │          │
│  │  ┌───────────────┐  │      │  ┌───────────────┐  │          │
│  │  │ Attribution   │  │      │  │ Monitoring    │  │          │
│  │  │ Engine Job    │  │      │  │ (Prometheus)  │  │          │
│  │  │ (Flink)       │  │      │  └───────────────┘  │          │
│  │  └───────────────┘  │      │                     │          │
│  └─────────────────────┘      └─────────────────────┘          │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              StatefulSet Namespace                       │   │
│  │                                                          │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │   Fluss      │  │   RocketMQ   │  │    Kafka     │  │   │
│  │  │  Cluster     │  │   Cluster    │  │   Cluster    │  │   │
│  │  │  (×5)        │  │   (×4)       │  │   (×3)       │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 7.3 资源配额

#### Click Writer Job
```yaml
resources:
  jobmanager:
    cpu: 2
    memory: 4Gi
  taskmanager:
    cpu: 4
    memory: 8Gi
    slots: 4
```

#### Attribution Engine Job
```yaml
resources:
  jobmanager:
    cpu: 2
    memory: 4Gi
  taskmanager:
    cpu: 4
    memory: 16Gi  # 需要更多内存用于异步 KV 查询
    slots: 4
```

#### Retry Consumer App
```yaml
resources:
  cpu: 2
  memory: 4Gi
  replicas: 4
```

---

## 8. 容灾设计

### 8.1 高可用架构

#### Flink 作业高可用
```yaml
# Flink HA 配置（两个 Job 相同）
high-availability: zookeeper
high-availability.zookeeper.quorum: zk1:2181,zk2:2181,zk3:2181
high-availability.storageDir: s3://flink-ha/

execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
state.backend: rocksdb
state.checkpoints.dir: s3://flink-checkpoints/

restart-strategy: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s
```

#### Fluss 集群高可用
```yaml
replication.factor: 3
min.insync.replicas: 2
auto.leader.rebalance.enable: true
```

#### RocketMQ 高可用
```yaml
brokerClusterName: attribution-cluster
brokerRole: SYNC_MASTER
flushDiskType: SYNC_FLUSH

# 2 主 2 从架构
broker-a: master (192.168.1.10)
broker-a-s: slave (192.168.1.11)
broker-b: master (192.168.1.12)
broker-b-s: slave (192.168.1.13)
```

### 8.2 故障恢复策略

#### Click Writer Job 故障
```
1. JobManager 故障
   - ZooKeeper 检测 → 选举新 JM
   - 从 Checkpoint 恢复作业
   - 恢复时间：~30 秒

2. TaskManager 故障
   - JM 检测 → 重新调度 Task
   - 从 Checkpoint 恢复
   - 恢复时间：~10 秒

3. Fluss 写入失败
   - 本地重试（3 次）
   - 失败后发送到 Kafka DLQ
   - 告警通知
```

#### Attribution Engine Job 故障
```
1. JobManager/TaskManager 故障
   - 同 Click Writer Job

2. Fluss KV 查询失败
   - 异步超时（5 秒）
   - 结果标记为 FAILED
   - 发送到 RocketMQ 重试队列

3. 归因计算失败
   - 捕获异常
   - 结果标记为 FAILED
   - 发送到 RocketMQ 重试队列
```

#### Retry Consumer App 故障
```
1. 实例故障
   - K8s 自动重启
   - 其他实例继续消费
   - RocketMQ 自动负载均衡

2. 消息处理失败
   - 捕获异常
   - 增加重试次数
   - 重新发送到 RocketMQ（带延迟）

3. DLQ 积压
   - 监控告警
   - 人工介入处理
```

### 8.3 数据一致性保证

| 场景 | 一致性级别 | 说明 |
|------|-----------|------|
| Click 存储 | 强一致 | Fluss 多副本同步写入 |
| 归因计算 | 精确一次 | Flink Checkpoint + 幂等计算 |
| 结果输出 | 精确一次 | 事务性写入 Kafka |
| 重试机制 | 至少一次 | RocketMQ 保证消息不丢失 |

---

## 9. 监控与告警

### 9.1 核心监控指标

#### Click Writer Job 指标
| 指标名称 | 类型 | 说明 | 告警阈值 |
|---------|------|------|---------|
| `click.processed.total` | Counter | 处理的 Click 总数 | - |
| `click.invalid.total` | Counter | 无效 Click 数 | > 1% |
| `fluss.kv.write.latency.ms` | Histogram | Fluss 写入延迟 | P99 > 100ms |
| `fluss.kv.write.errors` | Counter | 写入失败数 | > 10/min |
| `checkpoint.duration.ms` | Histogram | Checkpoint 耗时 | P99 > 60s |

#### Attribution Engine Job 指标
| 指标名称 | 类型 | 说明 | 告警阈值 |
|---------|------|------|---------|
| `conversion.processed.total` | Counter | 处理的转化数 | - |
| `attribution.success.rate` | Gauge | 归因成功率 | < 95% |
| `fluss.kv.read.latency.ms` | Histogram | Fluss 读取延迟 | P99 > 50ms |
| `attribution.retry.rate` | Gauge | 重试率 | > 5% |
| `result.success.written` | Counter | 成功结果写入数 | - |
| `result.failed.written` | Counter | 失败结果写入数 | - |

#### Retry Consumer App 指标
| 指标名称 | 类型 | 说明 | 告警阈值 |
|---------|------|------|---------|
| `retry.consumed.total` | Counter | 消费的重试消息数 | - |
| `retry.success.rate` | Gauge | 重试成功率 | < 80% |
| `retry.dlq.sent` | Counter | 发送到 DLQ 的消息数 | > 10/hour |
| `retry.processing.latency.ms` | Histogram | 重试处理延迟 | P99 > 1s |
| `rocketmq.consume.lag` | Gauge | RocketMQ 消费积压 | > 1000 |

### 9.2 告警策略

| 告警级别 | 触发条件 | 响应时间 | 通知方式 |
|---------|---------|---------|---------|
| **P0 - 严重** | 作业失败、数据丢失、DLQ > 1000 | 5 分钟 | 电话 + 短信 + IM |
| **P1 - 高** | 归因成功率 < 90%、重试率 > 10% | 15 分钟 | 短信 + IM |
| **P2 - 中** | 延迟 P99 > 100ms、重试率 > 5% | 1 小时 | IM |
| **P3 - 低** | 资源使用率 > 80%、Checkpoint 超时 | 4 小时 | 邮件 |

### 9.3 Grafana 仪表板

#### 概览仪表板
- 三个任务的运行状态
- 总吞吐量（Click/s, Conversion/s）
- 归因成功率趋势
- 重试率趋势

#### Click Writer 仪表板
- Click 处理量
- 无效 Click 比例
- Fluss 写入延迟
- Checkpoint 状态

#### Attribution Engine 仪表板
- Conversion 处理量
- 归因成功率
- Fluss KV 查询延迟
- 结果输出量

#### Retry Consumer 仪表板
- 重试消息消费量
- 重试成功率
- DLQ 消息量
- RocketMQ 消费积压

---

## 10. 性能优化

### 10.1 Click Writer 优化

#### 批量写入
```java
// 批量大小：100-500
// 批量间隔：500ms-2s
// 根据吞吐量动态调整
```

#### 连接池
```java
// 复用 Fluss KV Client 连接
// 连接池大小：10-20
// 连接超时：5s
```

### 10.2 Attribution Engine 优化

#### 异步 IO
```java
// 使用 Flink AsyncDataStream
// 并发度：10-20
// 超时：3-5s
// 最大待处理：1000
```

#### 本地缓存
```java
// Caffeine 缓存热点用户
// 最大缓存：10000 用户
// 过期时间：5 分钟
// 命中率目标：> 80%
```

### 10.3 Retry Consumer 优化

#### 批量消费
```java
// 每次拉取：100-500 条消息
// 消费线程：4-16
// 批量提交 Offset
```

#### 并行处理
```java
// 多线程并行处理重试消息
// 线程池大小：10-20
// 无锁队列
```

---

## 11. 扩展性设计

### 11.1 水平扩展

| 组件 | 扩展策略 | 瓶颈 |
|------|---------|------|
| Click Writer Job | 增加 TaskManager 并行度 | Fluss 写入吞吐 |
| Attribution Engine Job | 增加 TaskManager 并行度 | Fluss KV 查询吞吐 |
| Retry Consumer App | 增加实例数（K8s HPA） | RocketMQ 消费吞吐 |
| Fluss KV | 增加节点数 | 网络带宽 |
| RocketMQ | 增加 Broker 数 | 磁盘 IO |

### 11.2 功能扩展

- **新归因模型**: 插件化设计，支持自定义归因算法
- **A/B 测试**: 支持不同归因模型对比
- **多广告源**: 支持多个广告平台数据接入
- **多渠道转化**: 支持线上/线下多渠道转化追踪
- **跨设备归因**: 支持跨设备用户识别和归因

### 11.3 数据扩展

- **多租户支持**: 租户隔离，资源配额
- **数据分区**: 按广告主/地域分区
- **冷热分离**: 热数据在 Fluss KV，冷数据归档到对象存储

---

## 12. 配置管理

### 12.1 配置中心

使用 Nacos 作为配置中心：

```yaml
# Nacos 配置
nacos:
  server-addr: nacos:8848
  namespace: attribution-system
  group: DEFAULT_GROUP

# 配置分类
configs:
  - data-id: click-writer.yaml
  - data-id: attribution-engine.yaml
  - data-id: retry-consumer.yaml
  - data-id: fluss-config.yaml
  - data-id: rocketmq-config.yaml
```

### 12.2 热更新配置

支持热更新的配置：
- 归因窗口大小
- 归因模型切换
- 重试策略配置
- 日志级别
- 批量写入参数

---

## 13. 安全与合规

### 13.1 数据安全

- **传输加密**: TLS 1.3 加密传输
- **存储加密**: 敏感数据加密存储
- **访问控制**: RBAC 权限管理
- **审计日志**: 操作审计追踪

### 13.2 隐私保护

- **数据脱敏**: 用户标识符脱敏处理
- **数据保留**: 自动清理过期数据（7 天/30 天/90 天可配置）
- **合规性**: 符合 GDPR/个人信息保护法要求

---

## 14. 附录

### 14.1 术语表

| 术语 | 定义 |
|------|------|
| **Click Writer** | 负责将 Click 事件写入 Fluss KV 的独立任务 |
| **Attribution Engine** | 负责从 Fluss 查询 Click 并计算归因的独立任务 |
| **Retry Consumer** | 负责消费 RocketMQ 重试消息并重新处理的独立应用 |
| **Fluss KV** | Apache Fluss 的 Key-Value 存储模式 |
| **Kafka Topic** | Apache Kafka 的消息主题 |
| **DLQ** | Dead Letter Queue，死信队列 |

### 14.2 参考资料

- Apache Flink: https://flink.apache.org/
- Apache Fluss: https://fluss.apache.org/
- RocketMQ: https://rocketmq.apache.org/
- Flink Async IO: https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/operators/async_io/

---

**文档结束**

*此文档为 SimpleAttributeSystem v2.1 分布式隔离部署架构设计，后续将根据实际开发情况进行更新和完善。*
