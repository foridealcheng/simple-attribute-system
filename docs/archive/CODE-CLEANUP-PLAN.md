# Code Cleanup Plan - v2.1

**日期**: 2026-02-24  
**目标**: 删除未使用的代码，保持代码库简洁

---

## 🗑️ 待删除的文件

### 1. 旧版本 ProcessFunction

| 文件 | 原因 | 替代者 |
|------|------|--------|
| `AttributionProcessFunction.java` | v1.0 旧版本 | `AttributionProcessFunctionV2.java` |
| `RetryAttributionMapFunction.java` | 未被任何 Job 引用 | `RetryProcessFunction.java` |

### 2. 未使用的 Attribution 框架

| 文件 | 原因 |
|------|------|
| `function/AttributionFunction.java` | 接口未被使用 |
| `function/AttributionFunctionFactory.java` | 工厂未被使用 |
| `function/impl/LastClickAttribution.java` | 实现未被使用 |
| `function/impl/LinearAttribution.java` | 实现未被使用 |
| `function/impl/PositionBasedAttribution.java` | 实现未被使用 |
| `function/impl/TimeDecayAttribution.java` | 实现未被使用 |

### 3. 未使用的 Source Adapters

| 文件 | 原因 |
|------|------|
| `source/adapter/SourceAdapter.java` | 未被使用 |
| `source/adapter/KafkaSourceAdapter.java` | 未被使用 |
| `source/adapter/RocketMQSourceAdapter.java` | 未被使用 |
| `source/adapter/SourceConfig.java` | 未被使用 |
| `source/adapter/KafkaSourceConfig.java` | 未被使用 |
| `source/adapter/RocketMQSourceConfig.java` | 未被使用 |
| `source/flink/BaseFlinkSource.java` | 未被使用 |
| `source/flink/FlinkKafkaSource.java` | 未被使用 |
| `source/flink/FlinkRocketMQSource.java` | 未被使用 |

### 4. 未使用的 Decoders

| 文件 | 原因 |
|------|------|
| `decoder/AvroDecoder.java` | 未被使用 |
| `decoder/ProtobufDecoder.java` | 未被使用 |
| `decoder/JsonDecoder.java` | 未被使用 |
| `decoder/DecoderFactory.java` | 未被使用 |
| `decoder/DecoderException.java` | 未被使用 |
| `decoder/FormatDecoder.java` | 未被使用 |

### 5. 未使用的 Models

| 文件 | 原因 |
|------|------|
| `model/RawEvent.java` | 未被使用 |
| `model/EventType.java` | 未被使用 |

### 6. 未使用的 Consumer Config

| 文件 | 原因 |
|------|------|
| `consumer/RocketMQConsumerConfig.java` | 未被使用 |

---

## ✅ 保留的核心文件

### Jobs (3 个)
- `job/ClickWriterJob.java`
- `job/AttributionEngineJob.java`
- `job/RetryConsumerJob.java`
- `job/LocalTestJob.java` (测试用)

### ProcessFunctions (3 个)
- `flink/AttributionProcessFunctionV2.java`
- `flink/RetryProcessFunction.java`
- `flink/ClickKVBatchWriterProcessFunction.java`

### KV Clients (4 个)
- `client/KVClient.java` (接口)
- `client/KVClientFactory.java`
- `client/RedisKVClient.java`
- `client/FlussKVClient.java` (未来使用)

### Models (7 个)
- `model/ClickEvent.java`
- `model/ConversionEvent.java`
- `model/AttributionResult.java`
- `model/FlussClickSession.java`
- `model/RetryMessage.java`
- `model/AttributionModel.java`

### Configs (2 个)
- `config/KafkaSinkConfig.java`
- `config/RocketMQRetryConfig.java`

### Sinks (3 个)
- `sink/KafkaAttributionSink.java`
- `sink/RocketMQRetrySink.java`
- `sink/AttributionResultSink.java` (调试用)

### Utils (2 个)
- `validator/ClickValidator.java`
- `source/adapter/FlussSourceConfig.java`

### Engine (1 个)
- `engine/AttributionEngine.java`

---

## 📊 统计

**删除前**: 52 个 Java 文件  
**删除后**: ~25 个 Java 文件  
**减少**: ~52%

---

## ⚠️ 注意事项

1. `AttributionEngine.java` 保留 - 被 AttributionProcessFunctionV2 和 RetryProcessFunction 使用
2. `FlussKVClient.java` 保留 - 支持未来切换到 Fluss
3. `AttributionResultSink.java` 保留 - 调试用 CONSOLE sink
4. `FlussSourceConfig.java` 保留 - KVClient 配置需要

---

## 🔧 执行步骤

1. 删除未使用的文件
2. 编译验证
3. 更新文档
4. 提交代码
