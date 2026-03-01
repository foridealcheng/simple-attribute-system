# 归因任务启动报告

**启动日期**: 2026-02-23 19:54  
**版本**: v2.0.0-SNAPSHOT  
**启动结果**: ✅ **成功**

---

## 📊 启动摘要

| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 检查当前作业 | ✅ 1 个作业 CANCELED |
| 2 | 检查 Flink 集群 | ✅ TaskManagers: 1, Slots: 4 |
| 3 | 获取已上传 JAR | ✅ 2 个 JAR 可用 |
| 4 | 启动归因任务 | ✅ 作业已提交 |
| 5 | 等待作业启动 | ✅ 5 秒等待 |
| 6 | 验证作业状态 | ✅ RUNNING |
| 7 | 检查作业详情 | ✅ Simple Attribute System |
| 8 | 重新上传 JAR | ✅ 97MB 上传成功 |
| 9 | 获取最新 JAR ID | ✅ 获取成功 |
| 10 | 复制 JAR 到 Flink | ✅ 已复制到 usrlib |
| 11 | Flink CLI 提交 | ✅ 作业提交成功 |
| 12 | 等待启动 | ✅ 8 秒等待 |
| 13 | 验证最终状态 | ✅ 1 个作业 RUNNING |
| 14 | 检查详细信息 | ✅ 状态正常 |
| 15 | 资源概览 | ✅ Slots: 2/4 使用 |

**总计**: 15/15 步骤成功

---

## 🎯 作业信息

### 基本信息

```
作业名称：Simple Attribute System
作业 ID: 589a4358bbe3...
状态：RUNNING ✅
版本：2.0.0-SNAPSHOT
启动时间：1771847681173
```

### 资源配置

```
TaskManagers: 1
Total Slots: 4
Used Slots: 2 (50%)
Available Slots: 2
Parallelism: 2
```

### Flink 集群

```
Flink Version: 1.17.1
Jobs Running: 1
Jobs Finished: 0
Jobs Cancelled: 1 (旧作业)
Jobs Failed: 0
```

---

## 🚀 启动过程

### 步骤 1: 检查当前状态

```bash
curl http://localhost:8081/jobs
```

**结果**: 1 个作业 CANCELED（之前的作业已停止）

---

### 步骤 2: 验证 Flink 集群

```bash
curl http://localhost:8081/overview
```

**结果**:
```json
{
  "taskmanagers": 1,
  "slots-total": 4,
  "slots-available": 4,
  "flink-version": "1.17.1"
}
```

---

### 步骤 3-4: 上传并提交 JAR

```bash
# 上传 JAR
curl -X POST -F "jarfile=@target/*.jar" http://localhost:8081/jars/upload

# 提交作业
curl -X POST "http://localhost:8081/jars/{jarId}/run" \
  -H "Content-Type: application/json" \
  -d '{
    "entryClass": "com.attribution.AttributionJob",
    "parallelism": 2
  }'
```

**结果**: ✅ 作业提交成功

---

### 步骤 10-11: 使用 Flink CLI 提交

```bash
# 复制 JAR 到 Flink
docker cp target/*.jar flink-jobmanager:/opt/flink/usrlib/

# 使用 Flink CLI 提交
docker exec flink-jobmanager flink run \
  -c com.attribution.AttributionJob \
  -d \
  /opt/flink/usrlib/simple-attribute-system-2.0.0-SNAPSHOT.jar
```

**结果**: ✅ 作业提交成功，Job ID: 589a4358bbe3...

---

## 📦 作业拓扑

### 数据流

```
Click Source (Kafka)
    ↓
[Click Event Mapper]
    ↓
    ├──────────────────────┐
    │                      ↓
    │            [AttributionProcessFunction]
    │                      ↓
    │            [Kafka Attribution Sink]
    │                      ↓
    │            attribution-results-success
    │
Conversion Source (Kafka)
    ↓
[Conversion Event Mapper]
    ↓
    └──────────────────────┘
```

### 算子并行度

```
Source: Click Source          parallelism: 2
Source: Conversion Source     parallelism: 2
Map: Click Event Mapper       parallelism: 2
Map: Conversion Event Mapper  parallelism: 2
Process: AttributionFunction  parallelism: 2
Sink: Kafka Sink              parallelism: 2
Sink: Console Sink            parallelism: 2
```

---

## 🔍 监控指标

### Flink Web UI

访问：http://localhost:8081

**关键指标**:
- ✅ Jobs Running: 1
- ✅ TaskManagers: 1
- ✅ Slots Available: 2/4
- ✅ Checkpoints: 配置为 60 秒

### Kafka UI

访问：http://localhost:8090

**监控 Topic**:
- ✅ click-events (输入)
- ✅ conversion-events (输入)
- ✅ attribution-results-success (输出)
- ✅ attribution-results-failed (输出)

---

## 📝 作业配置

### 并行度

```yaml
parallelism: 2
```

### Checkpoint

```yaml
interval: 60000 (60 秒)
timeout: 300000 (5 分钟)
mode: EXACTLY_ONCE
```

### 重启策略

```yaml
strategy: fixed-delay
attempts: 3
delay: 10s
```

### 状态后端

```yaml
backend: filesystem
checkpoints: /tmp/flink-checkpoints
savepoints: /tmp/flink-savepoints
```

---

## ✅ 验证清单

### 启动验证

- [x] 作业提交成功
- [x] 状态为 RUNNING
- [x] 无启动错误
- [x] Web UI 可访问
- [x] TaskManager 正常

### 功能验证（待执行）

- [ ] 发送 Click 事件
- [ ] 发送 Conversion 事件
- [ ] 检查归因结果
- [ ] 验证 Kafka Sink
- [ ] 验证 RocketMQ 重试

### 性能验证（待执行）

- [ ] 延迟 < 1 秒
- [ ] 吞吐量达标
- [ ] Checkpoint 正常
- [ ] 资源使用合理

---

## 🐛 故障排查

### 如果作业启动失败

#### 1. 检查 Flink 日志

```bash
docker exec flink-jobmanager tail -100 /opt/flink/log/*.log
```

#### 2. 查看作业异常

```bash
curl http://localhost:8081/jobs/{jobId}/exceptions
```

#### 3. 检查资源

```bash
curl http://localhost:8081/taskmanagers
```

#### 4. 重新启动作业

```bash
# 停止旧作业
curl -X DELETE "http://localhost:8081/jobs/{jobId}"

# 重新提交
docker exec flink-jobmanager flink run \
  -c com.attribution.AttributionJob \
  /opt/flink/usrlib/simple-attribute-system-2.0.0-SNAPSHOT.jar
```

---

## 📈 资源使用预估

### 内存

```
TaskManager Memory: 2048MB
预估使用：~1024MB (50%)
```

### CPU

```
TaskManager Slots: 4
使用 Slots: 2 (50%)
```

### 网络

```
预估流量：~10MB/s (取决于输入流量)
```

---

## 🎯 下一步

### 立即执行

1. **发送测试数据** - 验证作业处理正常
   ```bash
   # 发送 Click 事件
   cat test-clicks.json | docker exec -i kafka \
     kafka-console-producer --bootstrap-server localhost:9092 \
     --topic click-events
   ```

2. **检查归因结果** - 确认输出正常
   ```bash
   docker exec kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 \
     --topic attribution-results-success \
     --timeout-ms 10000
   ```

3. **监控日志** - 观察是否有错误
   ```bash
   docker exec flink-jobmanager tail -f /opt/flink/log/*.log
   ```

### 短期计划

1. **性能测试** - 发送大量消息验证吞吐量
2. **压力测试** - 高并发场景验证
3. **监控告警** - 设置 Prometheus + Grafana

### 长期计划

1. **生产部署** - 部署到生产环境
2. **版本发布** - 发布 v2.0.0 正式版
3. **文档完善** - API 文档、用户指南

---

## 📞 支持

遇到问题？

1. **查看 Flink UI**: http://localhost:8081
2. **查看 Kafka UI**: http://localhost:8090
3. **查看作业日志**: 
   ```bash
   docker exec flink-jobmanager cat /opt/flink/log/*.log
   ```
4. **重启服务**:
   ```bash
   ./scripts/restart-local.sh
   ```

---

**启动人员**: SimpleAttributeSystem Team  
**启动时间**: 2026-02-23 19:54  
**作业版本**: 2.0.0-SNAPSHOT  
**当前状态**: RUNNING ✅
