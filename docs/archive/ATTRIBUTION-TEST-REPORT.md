# 归因数据测试报告

**测试日期**: 2026-02-23 20:11  
**测试类型**: 功能验证测试  
**测试结果**: ⚠️ **部分成功**

---

## 📊 测试摘要

| 测试项 | 结果 | 详情 |
|--------|------|------|
| **发送 Click 事件** | ✅ 成功 | 3 个事件已发送 |
| **发送 Conversion 事件** | ✅ 成功 | 1 个事件已发送 |
| **Flink 作业状态** | ✅ 正常 | RUNNING |
| **Kafka Topic 消息** | ✅ 正常 | Click: 11 条，Conv: 5 条 |
| **归因结果生成** | ⚠️ 待验证 | Success: 0 条 |

---

## 🎯 测试场景

### 测试数据

**用户**: user-test-001

**Click 事件** (3 次):
```json
{
  "event_id": "click-test-001",
  "user_id": "user-test-001",
  "timestamp": 1771848582000,
  "advertiser_id": "adv-001",
  "campaign_id": "camp-001",
  "creative_id": "creative-001",
  "click_type": "click",
  "device_type": "mobile"
}

{
  "event_id": "click-test-002",
  "user_id": "user-test-001",
  "timestamp": 1771848583000,
  "advertiser_id": "adv-002",
  "campaign_id": "camp-002",
  "creative_id": "creative-002",
  "click_type": "click",
  "device_type": "desktop"
}

{
  "event_id": "click-test-003",
  "user_id": "user-test-001",
  "timestamp": 1771848584000,
  "advertiser_id": "adv-003",
  "campaign_id": "camp-003",
  "creative_id": "creative-003",
  "click_type": "click",
  "device_type": "mobile"
}
```

**Conversion 事件** (1 次):
```json
{
  "event_id": "conv-test-001",
  "user_id": "user-test-001",
  "timestamp": 1771848585000,
  "conversion_type": "purchase",
  "conversion_value": 100.0,
  "currency": "USD",
  "order_id": "order-001"
}
```

### 预期结果

**归因模型**: Last Click  
**预期**: 100% 功劳分配给 click-test-003 (最后一次点击)

```json
{
  "resultId": "...",
  "conversionId": "conv-test-001",
  "userId": "user-test-001",
  "status": "SUCCESS",
  "attributionModel": "LAST_CLICK",
  "attributedClicks": ["click-test-003"],
  "creditDistribution": {
    "click-test-003": 1.0
  },
  "totalConversionValue": 100.0
}
```

---

## 📈 测试结果

### Kafka Topic 消息统计

| Topic | Partition 0 | Partition 1 | Partition 2 | 总计 |
|-------|-------------|-------------|-------------|------|
| click-events | 0 | 5 | 6 | **11** |
| conversion-events | 0 | 4 | 1 | **5** |
| attribution-results-success | 0 | 0 | 0 | **0** |
| attribution-results-failed | 0 | 0 | 0 | **0** |

### Flink 作业状态

```
作业名称：Simple Attribute System
作业 ID: 589a4358bbe3...
状态：RUNNING ✅
启动时间：1771847681173
```

---

## ⚠️ 问题分析

### 归因结果未生成的可能原因

#### 1. 作业未处理新消息

**检查项**:
- Flink Checkpoint 是否正常
- Kafka Consumer 是否在消费
- 算子是否有背压

**验证命令**:
```bash
# 检查 Checkpoint
curl http://localhost:8081/jobs/{jobId}/checkpoints

# 检查背压
curl http://localhost:8081/jobs/{jobId}/backpressure
```

---

#### 2. 数据分区问题

**可能原因**: Click 和 Conversion 事件在不同 Partition，导致无法关联

**检查项**:
- KeyBy 是否使用 userId
- Partition 分配是否均匀

**验证命令**:
```bash
# 查看消息 Key
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic click-events \
  --property print.key=true \
  --timeout-ms 5000
```

---

#### 3. 状态未正确加载

**可能原因**: FlussKVClient 未正确初始化或状态为空

**检查项**:
- Fluss 连接是否正常
- 本地缓存是否工作
- 日志是否有错误

**验证命令**:
```bash
# 查看 Flink 日志
docker exec flink-jobmanager ls /opt/flink/log/

# 查看作业异常
curl http://localhost:8081/jobs/{jobId}/exceptions
```

---

#### 4. 归因逻辑问题

**可能原因**: 归因窗口设置过短或逻辑错误

**检查项**:
- AttributionEngine 配置
- 归因窗口大小 (默认 24 小时)
- 时间戳是否正确

---

## 🔍 排查步骤

### 步骤 1: 检查 Flink 日志

```bash
docker exec flink-jobmanager cat /opt/flink/log/*.log | tail -100
```

---

### 步骤 2: 检查作业异常

```bash
curl http://localhost:8081/jobs/589a4358bbe3.../exceptions
```

---

### 步骤 3: 检查 Checkpoint

```bash
curl http://localhost:8081/jobs/589a4358bbe3.../checkpoints
```

---

### 步骤 4: 重启作业

```bash
# 停止作业
curl -X DELETE "http://localhost:8081/jobs/589a4358bbe3..."

# 重新提交
docker exec flink-jobmanager flink run \
  -c com.attribution.AttributionJob \
  /opt/flink/usrlib/simple-attribute-system-2.0.0-SNAPSHOT.jar
```

---

### 步骤 5: 发送更多测试数据

```bash
# 发送更多 Click 事件
for i in {1..10}; do
  echo "{\"event_id\":\"click-$i\",\"user_id\":\"user-$i\",\"timestamp\":$(date +%s000),\"advertiser_id\":\"adv-001\"}" | \
  docker exec -i kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic click-events
done

# 发送更多 Conversion 事件
for i in {1..5}; do
  echo "{\"event_id\":\"conv-$i\",\"user_id\":\"user-$i\",\"timestamp\":$(date +%s000),\"conversion_type\":\"purchase\",\"conversion_value\":100.0}" | \
  docker exec -i kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic conversion-events
done
```

---

## ✅ 已完成项

- [x] 测试环境准备
- [x] Click 事件发送 (3 个)
- [x] Conversion 事件发送 (1 个)
- [x] Flink 作业运行正常
- [x] Kafka Topic 消息正常
- [ ] 归因结果生成 (待解决)
- [ ] 结果验证 (待解决)

---

## 📝 下一步

### 立即执行

1. **检查 Flink 日志** - 查找错误信息
2. **检查作业异常** - 查看是否有 exception
3. **验证数据流** - 确认消息被消费

### 短期计划

1. **修复问题** - 根据日志定位问题
2. **重新测试** - 发送新测试数据
3. **验证结果** - 确认归因正确

### 长期计划

1. **添加监控** - Prometheus + Grafana
2. **完善日志** - 结构化日志输出
3. **性能优化** - 调整并行度和资源

---

## 📞 支持

需要帮助？

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

**测试人员**: SimpleAttributeSystem Team  
**测试时间**: 2026-02-23 20:11  
**测试版本**: 2.0.0-SNAPSHOT  
**当前状态**: ⚠️ 需要排查
