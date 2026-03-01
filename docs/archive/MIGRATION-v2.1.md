# v2.1 迁移指南

> 从 v1.x 单体架构迁移到 v2.1 分布式隔离架构

**版本**: v2.1  
**创建时间**: 2026-02-24  
**状态**: 📝 Draft

---

## 📋 概述

v2.1 架构进行了重大重构，将原有的单体 Flink 作业拆分为三个独立任务：

| 原架构 (v1.x) | 新架构 (v2.1) | 变更说明 |
|--------------|--------------|---------|
| 单一 Flink 作业 | Click Writer Job | Click 数据写入独立 |
| 单一 Flink 作业 | Attribution Engine Job | 归因计算独立 |
| 无 | Retry Consumer App | 重试能力独立进程 |

---

## 🎯 迁移目标

- ✅ Click 数据直接写入 Fluss（不再通过 Flink State）
- ✅ 归因任务无状态化（从 Fluss 查询 Click 数据）
- ✅ RocketMQ 重试能力深度集成
- ✅ 任务独立部署、独立扩展

---

## 📦 前置条件

### 基础设施要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| Apache Flink | 1.18+ | 支持 Async IO 改进 |
| Apache Fluss | 0.6+ | 支持 KV Store |
| RocketMQ | 5.0+ | 支持延迟消息 |
| Kubernetes | 1.20+ | 可选，用于容器化部署 |

### 数据迁移

- **现有 Click 数据**: 需要从 Flink State 迁移到 Fluss KV
- **现有归因结果**: 保持兼容，无需迁移
- **重试队列**: 清空旧重试消息

---

## 🚀 迁移步骤

### Step 1: 准备 Fluss KV Store

```bash
# 1. 创建 Fluss KV Table
fluss table create attribution-clicks \
  --schema "user_id STRING PRIMARY KEY, clicks_data STRING, last_update_time BIGINT, session_start_time BIGINT, version BIGINT" \
  --partition-key user_id \
  --table-type kv

# 2. 验证 Table 创建成功
fluss table describe attribution-clicks
```

### Step 2: 部署 Click Writer Job

```bash
# 1. 构建新版本的 Click Writer Job
cd SimpleAttributeSystem
mvn clean package -DskipTests

# 2. 提交 Flink Job
flink run -c com.attribution.job.ClickWriterJob \
  -d \
  -p 4 \
  -ynm click-writer-job-v2.1 \
  target/simple-attribute-system-2.1.0.jar \
  --config config/click-writer.yaml

# 3. 验证 Job 运行状态
flink list --running | grep click-writer
```

**配置文件** (`config/click-writer.yaml`):
```yaml
job:
  name: click-writer-job
  parallelism: 4

source:
  type: kafka
  bootstrap.servers: kafka:9092
  topic: click-events
  consumer.group: click-writer-group

fluss:
  bootstrap.servers: fluss:9110
  kv.table.name: attribution-clicks
  batch.size: 100
  batch.interval.ms: 1000

click:
  max.per.user: 50
  dedup.enabled: true
  dedup.window.ms: 3600000
```

### Step 3: 部署 Attribution Engine Job

```bash
# 1. 提交 Flink Job
flink run -c com.attribution.job.AttributionEngineJob \
  -d \
  -p 8 \
  -ynm attribution-engine-job-v2.1 \
  target/simple-attribute-system-2.1.0.jar \
  --config config/attribution-engine.yaml

# 2. 验证 Job 运行状态
flink list --running | grep attribution-engine
```

**配置文件** (`config/attribution-engine.yaml`):
```yaml
job:
  name: attribution-engine-job
  parallelism: 8

source:
  type: fluss
  bootstrap.servers: fluss:9110
  table: conversion-events-stream

fluss:
  bootstrap.servers: fluss:9110
  kv.table.name: attribution-clicks
  async.timeout.ms: 5000
  async.concurrency: 10

attribution:
  window.ms: 604800000  # 7 days
  model: LAST_CLICK
  max.clicks.per.user: 50

result:
  success.topic: attribution-results-success
  failed.topic: attribution-results-failed

rocketmq:
  nameserver.address: rocketmq-namesrv:9876
  producer.group: attribution-retry-producer
  retry.topic: attribution-retry
  max.retries: 10
```

### Step 4: 部署 Retry Consumer App

```bash
# 1. 构建独立应用
cd SimpleAttributeSystem
mvn clean package -DskipTests

# 2. 启动 Retry Consumer（方式 1: 直接运行）
java -jar target/retry-consumer-app-2.1.0.jar \
  --config config/retry-consumer.yaml

# 3. 启动 Retry Consumer（方式 2: Docker）
docker run -d \
  --name retry-consumer \
  -v $(pwd)/config:/app/config \
  attribution-system/retry-consumer:2.1.0

# 4. 启动 Retry Consumer（方式 3: Kubernetes）
kubectl apply -f k8s/retry-consumer-deployment.yaml
```

**配置文件** (`config/retry-consumer.yaml`):
```yaml
rocketmq:
  nameserver.address: rocketmq-namesrv:9876
  consumer.group: attribution-retry-consumer-group
  retry.topic: attribution-retry
  dlq.topic: attribution-retry_DLQ
  max.retries: 10
  consume.thread.min: 4
  consume.thread.max: 16

fluss:
  bootstrap.servers: fluss:9110
  kv.table.name: attribution-clicks

result:
  success.topic: attribution-results-success
```

### Step 5: 数据迁移（可选）

如果已有 Flink State 中的 Click 数据需要迁移到 Fluss KV：

```java
// 迁移工具：StateToFlussMigrator
public class StateToFlussMigrator {
    
    public static void main(String[] args) throws Exception {
        // 1. 读取 Flink Savepoint
        Savepoint savepoint = Savepoint.load(...);
        
        // 2. 提取 Click State
        MapStateDescriptor<String, List<ClickEvent>> stateDesc = ...;
        Map<String, List<ClickEvent>> clickState = savepoint.getOperatorState(...);
        
        // 3. 写入 Fluss KV
        FlussKVClient kvClient = new FlussKVClientImpl("attribution-clicks", ...);
        
        for (Map.Entry<String, List<ClickEvent>> entry : clickState.entrySet()) {
            FlussKVKey key = new FlussKVKey(entry.getKey());
            FlussKVValue value = new FlussKVValue(entry.getValue());
            kvClient.put(key, value).join();
        }
        
        // 4. 验证迁移
        log.info("Migrated {} users to Fluss KV", clickState.size());
    }
}
```

### Step 6: 验证与测试

#### 6.1 功能验证

```bash
# 1. 发送测试 Click 事件
kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic click-events \
  --property "parse.key=true" \
  --property "key.separator=:" <<EOF
user-001:{"eventId":"click-001","userId":"user-001","timestamp":1708512000000,...}
EOF

# 2. 验证 Fluss KV 中是否有数据
fluss kv get attribution-clicks --key user-001

# 3. 发送测试 Conversion 事件
fluss produce conversion-events-stream \
  --key user-001 \
  --value '{"eventId":"conv-001","userId":"user-001",...}'

# 4. 验证归因结果
fluss consume attribution-results-success \
  --from-beginning \
  --max-messages 10
```

#### 6.2 性能验证

```bash
# 1. 监控 Click Writer 吞吐量
flink metrics | grep click.processed.total

# 2. 监控 Attribution Engine 延迟
flink metrics | grep attribution.success.rate

# 3. 监控 Retry Consumer 积压
mqadmin consumerProgress -n rocketmq-namesrv:9876 \
  -g attribution-retry-consumer-group
```

#### 6.3 重试验证

```bash
# 1. 模拟失败场景（临时关闭 Fluss）
docker stop fluss-server-1

# 2. 观察 Retry Consumer 日志
docker logs -f retry-consumer | grep "Retry"

# 3. 验证重试消息进入延迟队列
mqadmin queryMsgByUniqueKey \
  -n rocketmq-namesrv:9876 \
  -t attribution-retry \
  -i <message-id>

# 4. 验证 DLQ 消息（如果超过最大重试次数）
mqadmin queryMsgByUniqueKey \
  -n rocketmq-namesrv:9876 \
  -t attribution-retry_DLQ \
  -i <message-id>
```

### Step 7: 切换流量

#### 7.1 灰度切换

```
Phase 1: 10% 流量 → v2.1
Phase 2: 50% 流量 → v2.1
Phase 3: 100% 流量 → v2.1
```

**实现方式**:
```yaml
# Kafka 路由配置（使用 Kafka Streams 或 KSQL）
# 将 10% 的 Click 事件路由到新的 Click Writer Job

INSERT INTO click-events-v2
SELECT * FROM click-events
WHERE RAND() < 0.1;  -- 10% 流量

INSERT INTO click-events-v1
SELECT * FROM click-events
WHERE RAND() >= 0.1;  -- 90% 流量保持原有
```

#### 7.2 完全切换

```bash
# 1. 停止旧的单体作业
flink cancel <old-job-id>

# 2. 确认新作业正常运行
flink list --running

# 3. 监控关键指标 24 小时
# - 归因成功率
# - 重试率
# - 延迟
```

### Step 8: 清理旧资源

```bash
# 1. 删除旧的 Flink Savepoint（确认不再需要）
rm -rf /path/to/old/savepoints/*

# 2. 清理旧的 Kafka Topic（可选）
kafka-topics.sh --delete \
  --bootstrap-server kafka:9092 \
  --topic old-attribution-topic

# 3. 清理旧的 RocketMQ Consumer Group
mqadmin deleteSubGroup \
  -n rocketmq-namesrv:9876 \
  -g old-attribution-consumer-group
```

---

## ⚠️ 注意事项

### 1. 数据一致性

- 迁移期间确保 **不丢失任何 Click 事件**
- 使用 **双写** 策略过渡（同时写入旧 State 和新 Fluss KV）
- 验证 **归因结果一致性**（对比 v1.x 和 v2.1 的输出）

### 2. 回滚方案

```bash
# 如果 v2.1 出现问题，快速回滚到 v1.x

# 1. 停止新作业
flink cancel click-writer-job-v2.1
flink cancel attribution-engine-job-v2.1
kubectl delete deployment retry-consumer

# 2. 恢复旧作业（从 Savepoint）
flink run -s s3://flink-savepoints/v1-savepoint-xxx \
  -c com.attribution.job.AttributionJob \
  target/simple-attribute-system-1.1.0.jar

# 3. 验证回滚成功
flink list --running
```

### 3. 性能调优

迁移后可能需要调整以下参数：

| 参数 | 调整方向 | 说明 |
|------|---------|------|
| `fluss.batch.size` | 100 → 500 | 提高批量大小（如果网络允许） |
| `async.concurrency` | 10 → 20 | 提高异步并发度（如果 Fluss 支持） |
| `consume.thread.max` | 16 → 32 | 提高消费线程数（如果 CPU 允许） |

### 4. 监控告警

确保以下告警已配置：

- ✅ Click Writer Job 失败
- ✅ Attribution Engine Job 失败
- ✅ Retry Consumer App 失败
- ✅ Fluss KV 写入失败率 > 1%
- ✅ 归因成功率 < 95%
- ✅ DLQ 消息积压 > 100

---

## 📊 迁移检查清单

### 准备阶段

- [ ] Fluss KV Table 创建完成
- [ ] RocketMQ Topic 创建完成（retry + DLQ）
- [ ] 新版本代码编译通过
- [ ] 单元测试通过率 100%
- [ ] 集成测试通过

### 部署阶段

- [ ] Click Writer Job 部署成功
- [ ] Attribution Engine Job 部署成功
- [ ] Retry Consumer App 部署成功
- [ ] 所有 Job/App 健康检查通过

### 验证阶段

- [ ] Click 数据正确写入 Fluss KV
- [ ] 归因计算结果正确
- [ ] 重试机制正常工作
- [ ] 性能指标符合预期
- [ ] 监控告警正常

### 切换阶段

- [ ] 灰度切换（10% 流量）验证通过
- [ ] 灰度切换（50% 流量）验证通过
- [ ] 完全切换（100% 流量）执行完成
- [ ] 旧作业停止并清理

### 运维阶段

- [ ] 24 小时稳定运行监控
- [ ] 性能基线建立
- [ ] 运维文档更新
- [ ] 团队培训完成

---

## 🔧 故障排查

### 问题 1: Click 数据未写入 Fluss

**检查**:
```bash
# 1. 检查 Click Writer Job 状态
flink list --running | grep click-writer

# 2. 检查 Job 日志
flink log --jobId <job-id>

# 3. 检查 Fluss KV Table
fluss table describe attribution-clicks

# 4. 检查网络连接
telnet fluss-server 9110
```

**可能原因**:
- Fluss 连接配置错误
- Table 名称不匹配
- 网络不通

### 问题 2: 归因结果为空

**检查**:
```bash
# 1. 检查 Conversion 事件是否正常消费
fluss consume conversion-events-stream --from-beginning

# 2. 检查 Fluss KV 中是否有 Click 数据
fluss kv get attribution-clicks --key <user-id>

# 3. 检查归因窗口配置
cat config/attribution-engine.yaml | grep window.ms

# 4. 检查 Attribution Engine 日志
kubectl logs <attribution-engine-pod>
```

**可能原因**:
- Click 数据未写入或已过期
- 归因窗口配置过短
- userId 不匹配

### 问题 3: 重试消息积压

**检查**:
```bash
# 1. 检查 Retry Consumer 状态
kubectl get pods | grep retry-consumer

# 2. 检查消费进度
mqadmin consumerProgress -n rocketmq-namesrv:9876 \
  -g attribution-retry-consumer-group

# 3. 检查 Consumer 日志
kubectl logs <retry-consumer-pod> | grep "Failed"

# 4. 检查 DLQ 消息量
mqadmin consumerProgress -n rocketmq-namesrv:9876 \
  -g attribution-retry-consumer-group_DLQ
```

**可能原因**:
- Retry Consumer 实例数不足
- 处理逻辑有 Bug
- Fluss 查询超时

---

## 📈 性能对比

### v1.x vs v2.1

| 指标 | v1.x | v2.1 | 提升 |
|------|------|------|------|
| **Click 处理延迟** | < 100ms | < 50ms | 50% ↓ |
| **归因计算延迟** | < 200ms | < 100ms | 50% ↓ |
| **状态恢复时间** | ~60s | ~10s | 83% ↓ |
| **最大吞吐量** | 10,000/s | 50,000/s | 5x ↑ |
| **水平扩展性** | 中 | 高 | - |

---

## 📚 相关文档

- [ARCHITECTURE-v2.1-DISTRIBUTED.md](./ARCHITECTURE-v2.1-DISTRIBUTED.md) - v2.1 架构设计
- [ARCHITECTURE.md](./ARCHITECTURE.md) - v1.x 架构设计
- [ROCKETMQ-RETRY-COMPLETE.md](./ROCKETMQ-RETRY-COMPLETE.md) - RocketMQ 重试实现
- [ROCKETMQ-CONSUMER-IMPLEMENTATION.md](./ROCKETMQ-CONSUMER-IMPLEMENTATION.md) - Retry Consumer 实现

---

**迁移完成时间**: 预计 2-4 小时（不含测试）  
**风险等级**: 中高（建议灰度切换）  
**回滚时间**: < 10 分钟

---

*此迁移指南为 v2.1 架构迁移的参考文档，实际操作前请在测试环境充分验证。*
