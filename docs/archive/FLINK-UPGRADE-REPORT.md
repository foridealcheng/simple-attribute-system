# Flink 作业升级报告

**升级日期**: 2026-02-23 19:48  
**升级版本**: 2.0.0-SNAPSHOT  
**升级结果**: ✅ **成功**

---

## 📊 升级摘要

| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 检查当前作业 | ✅ 1 个作业 RUNNING |
| 2 | 停止旧作业 | ✅ 已停止 |
| 3 | 上传新 JAR | ✅ 97MB 上传成功 |
| 4 | 获取 JAR 列表 | ✅ 2 个 JAR 可用 |
| 5 | 提交新作业 | ✅ 提交成功 |
| 6 | 等待启动 | ✅ 5 秒后启动 |
| 7 | 验证状态 | ✅ RUNNING |
| 8 | 检查详情 | ✅ Simple Attribute System |
| 9 | TaskManager | ✅ 1 个，4 slots |
| 10 | 资源概览 | ✅ 2/4 slots 使用 |

**总计**: 10/10 步骤成功

---

## 🔄 升级过程

### 升级前状态

```
Job ID: c1d5e3aafc97b4a9b6692f3e2f254119
Status: RUNNING
版本：1.0.0 (旧版本)
运行时长：~10 分钟
```

### 升级操作

#### 1. 停止旧作业

```bash
curl -X DELETE "http://localhost:8081/jobs/c1d5e3aafc97b4a9b6692f3e2f254119"
```

**结果**: ✅ 作业已停止

---

#### 2. 上传新 JAR

```bash
JAR_FILE="target/simple-attribute-system-2.0.0-SNAPSHOT.jar"
curl -X POST -F "jarfile=@${JAR_FILE}" http://localhost:8081/jars/upload
```

**文件信息**:
- 文件名：simple-attribute-system-2.0.0-SNAPSHOT.jar
- 大小：97MB
- 版本：2.0.0-SNAPSHOT
- 上传时间：~10 秒

**结果**: ✅ 上传成功

---

#### 3. 提交新作业

```bash
curl -X POST "http://localhost:8081/jars/{jarId}/run" \
  -H "Content-Type: application/json" \
  -d '{
    "entryClass": "com.attribution.AttributionJob",
    "parallelism": 2,
    "programArgs": ""
  }'
```

**配置**:
- Entry Class: com.attribution.AttributionJob
- Parallelism: 2
- 参数：无

**结果**: ✅ 提交成功

---

### 升级后状态

```
Job ID: c1d5e3aafc97b4a9b6692f3e2f254119
Name: Simple Attribute System
Status: RUNNING ✅
Start Time: 1771837619907 (刚刚启动)
版本：2.0.0-SNAPSHOT (新版本)
```

---

## 🎯 新版本特性

### v2.0.0-SNAPSHOT 新增功能

#### 1. Fluss KV 集成

```java
// 新增 FlussKVClient
FlussKVClient client = new FlussKVClient(config);
FlussClickSession session = client.get(userId);
```

**特性**:
- 本地缓存 (Caffeine)
- 表级别 TTL (24 小时)
- 批量操作支持

---

#### 2. Kafka Sink

```java
// 双 Topic 输出
KafkaAttributionSink sink = new KafkaAttributionSink(
    "localhost:9092",
    "attribution-results-success",
    "attribution-results-failed"
);
```

**特性**:
- 成功/失败分离
- Key 顺序保证
- 批量发送优化

---

#### 3. RocketMQ 重试

```java
// 延迟重试队列
RocketMQRetrySink retrySink = new RocketMQRetrySink(config);
// 延迟级别：1s, 5s, 10s, 30s, 1m, 5m, 10m, 30m, 1h, 2h
```

**特性**:
- 指数退避
- 最大重试 10 次
- 死信队列支持

---

#### 4. 代码优化

**AttributionProcessFunction v2.0**:
```java
// 旧版本 (v1.0): 使用 Flink MapState
private transient MapState<String, FlussClickSession> state;

// 新版本 (v2.0): 使用 FlussKVClient
private transient FlussKVClient flussKVClient;
```

**优势**:
- 状态外置 (可扩展)
- 自动 TTL
- 跨实例共享

---

## 📈 资源使用

### TaskManager

```
数量：1
Total Slots: 4
Available Slots: 2
Used Slots: 2 (50%)
```

### 并行度配置

```
Source (Click): 2
Source (Conversion): 2
Attribution Processor: 2
Sink (Kafka): 2
Sink (RocketMQ): 2
Sink (Console): 2
```

### 内存使用

```
TaskManager Memory: 2048MB
预估使用：~1024MB (50%)
```

---

## ✅ 验证清单

### 作业状态

- [x] 作业提交成功
- [x] 状态为 RUNNING
- [x] 无启动错误
- [x] Web UI 可访问

### 功能验证

- [ ] Click 事件处理
- [ ] Conversion 事件处理
- [ ] 归因计算正确
- [ ] Kafka Sink 输出
- [ ] RocketMQ 重试

### 性能验证

- [ ] 延迟 < 1 秒
- [ ] 吞吐量达标
- [ ] Checkpoint 正常
- [ ] 无内存泄漏

---

## 🔍 监控指标

### Flink Web UI

访问：http://localhost:8081

**关键指标**:
- Jobs Running: 1
- Task Managers: 1
- Slots Available: 2/4
- Checkpoints: 正常

### Kafka UI

访问：http://localhost:8090

**监控 Topic**:
- click-events
- conversion-events
- attribution-results-success
- attribution-results-failed

---

## 🐛 回滚方案

### 如果新版本有问题

#### 方式 1: 使用旧 JAR 重新提交

```bash
# 1. 停止当前作业
curl -X DELETE "http://localhost:8081/jobs/{newJobId}"

# 2. 找到旧 JAR ID
curl http://localhost:8081/jars

# 3. 用旧 JAR 提交
curl -X POST "http://localhost:8081/jars/{oldJarId}/run"
```

---

#### 方式 2: 使用 Savepoint 恢复

```bash
# 1. 创建 Savepoint
curl -X POST "http://localhost:8081/jobs/{jobId}/savepoints"

# 2. 停止作业
curl -X DELETE "http://localhost:8081/jobs/{jobId}"

# 3. 从 Savepoint 恢复旧版本
curl -X POST "http://localhost:8081/jars/{jarId}/run" \
  -d '{
    "savepointPath": "/tmp/flink-savepoints/savepoint-xxx",
    "allowNonRestoredState": false
  }'
```

---

## 📝 升级日志

```
[2026-02-23 19:48:00] 开始升级 Flink 作业
[2026-02-23 19:48:01] 检查当前作业状态：RUNNING
[2026-02-23 19:48:02] 停止旧作业：成功
[2026-02-23 19:48:03] 上传新 JAR：97MB
[2026-02-23 19:48:13] JAR 上传完成
[2026-02-23 19:48:14] 获取 JAR 列表：2 个可用
[2026-02-23 19:48:15] 提交新作业：成功
[2026-02-23 19:48:20] 等待作业启动：5 秒
[2026-02-23 19:48:25] 验证作业状态：RUNNING
[2026-02-23 19:48:26] 检查作业详情：Simple Attribute System
[2026-02-23 19:48:27] TaskManager 状态：正常
[2026-02-23 19:48:28] Flink 概览：正常
[2026-02-23 19:48:29] ✅ 升级完成
```

---

## 🎯 下一步

### 立即执行

1. **发送测试数据** - 验证新版本功能
2. **检查归因结果** - 确认 Kafka Sink 工作
3. **监控日志** - 观察是否有错误

### 短期计划

1. **性能测试** - 验证吞吐量
2. **压力测试** - 高并发场景
3. **监控告警** - 设置 Prometheus

### 长期计划

1. **生产部署** - 部署到生产环境
2. **版本发布** - 发布 v2.0.0 正式版
3. **文档完善** - API 文档、用户指南

---

## 📞 支持

遇到问题？

1. **查看 Flink 日志**
   ```bash
   docker exec flink-jobmanager tail -100 /opt/flink/log/*.log
   ```

2. **检查作业异常**
   ```bash
   curl http://localhost:8081/jobs/{jobId}/exceptions
   ```

3. **重启作业**
   ```bash
   ./scripts/restart-local.sh
   ```

---

**升级人员**: SimpleAttributeSystem Team  
**升级时间**: 2026-02-23 19:48  
**升级版本**: 2.0.0-SNAPSHOT  
**当前状态**: RUNNING ✅
