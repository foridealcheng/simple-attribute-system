# 归因功能重新测试报告

**测试日期**: 2026-02-23 20:49  
**测试类型**: 完整功能测试  
**测试结果**: ⚠️ **归因结果仍为 0**

---

## 📊 测试摘要

| 测试项 | 结果 | 详情 |
|--------|------|------|
| **Flink 作业状态** | ✅ RUNNING | 作业正常 |
| **发送 Click 事件** | ✅ 成功 | 3 个事件 |
| **发送 Conversion** | ✅ 成功 | 1 个事件 |
| **作业异常** | ✅ 无异常 | 空 |
| **RocketMQ 错误** | ✅ 已修复 | 无连接错误 |
| **归因结果 (Success)** | ❌ 0 条 | 未生成 |
| **归因结果 (Failed)** | ❌ 0 条 | 未生成 |

---

## 🎯 测试数据

### 唯一测试 ID

```
测试 ID: test-204920
时间戳：1771850960000
用户 ID: user-test-204920
```

### Click 事件 (3 个)

```json
{"event_id":"click-test-204920-001","user_id":"user-test-204920","timestamp":1771850960000,"advertiser_id":"adv-001","campaign_id":"camp-001","creative_id":"creative-001","click_type":"click","device_type":"mobile"}

{"event_id":"click-test-204920-002","user_id":"user-test-204920","timestamp":1771850961000,"advertiser_id":"adv-002","campaign_id":"camp-002","creative_id":"creative-002","click_type":"click","device_type":"desktop"}

{"event_id":"click-test-204920-003","user_id":"user-test-204920","timestamp":1771850962000,"advertiser_id":"adv-003","campaign_id":"camp-003","creative_id":"creative-003","click_type":"click","device_type":"mobile"}
```

### Conversion 事件 (1 个)

```json
{"event_id":"conv-test-204920-001","user_id":"user-test-204920","timestamp":1771850963000,"conversion_type":"purchase","conversion_value":100.0,"currency":"USD","order_id":"order-test-204920"}
```

---

## 📈 Kafka Topic 统计

| Topic | Partition 0 | Partition 1 | Partition 2 | 总计 |
|-------|-------------|-------------|-------------|------|
| click-events | 3 | 7 | 6 | **16** |
| conversion-events | 1 | 4 | 2 | **7** |
| attribution-results-success | 0 | 0 | 0 | **0** ❌ |
| attribution-results-failed | 0 | 0 | 0 | **0** ❌ |

---

## ✅ 验证通过项

### Flink 作业

```
作业名称：Simple Attribute System
作业 ID: 7a28233f6fc3...
状态：RUNNING ✅
启动时间：1771850680485
```

### 作业异常

```json
{
  "root-exception": null,
  "all-exceptions": [],
  "exceptionHistory": {
    "entries": []
  }
}
```

**结果**: ✅ 无异常

### RocketMQ 错误

```bash
docker logs flink-jobmanager | grep -i "RocketMQ|RemotingConnect"
```

**结果**: ✅ 无连接错误（已修复）

---

## ❌ 问题分析

### 归因结果未生成的可能原因

#### 1. 数据未消费

**检查项**:
- Kafka Consumer Group 是否正常
- Offset 是否推进
- Consumer 是否连接

**验证命令**:
```bash
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list
```

---

#### 2. 数据分区问题

**可能原因**: Click 和 Conversion 在不同 Partition，导致 KeyBy 后无法关联

**当前分布**:
```
click-events:
  Partition 0: 3 条
  Partition 1: 7 条
  Partition 2: 6 条

conversion-events:
  Partition 0: 1 条
  Partition 1: 4 条
  Partition 2: 2 条
```

**问题**: 如果 userId 的 hash 导致 Click 和 Conversion 在不同 Partition，则无法关联

---

#### 3. AttributionEngine 逻辑问题

**可能原因**:
- 归因窗口设置过短
- 时间戳比较逻辑错误
- 数据匹配条件过严

**检查代码**:
```java
// AttributionEngine.java
public AttributionResult attribute(ConversionEvent conversion, FlussClickSession session) {
    // 检查点击是否在归因窗口内
    List<ClickEvent> validClicks = session.getValidClicks(
        conversion.getTimestamp(), 
        attributionWindowHours
    );
    
    if (validClicks.isEmpty()) {
        return AttributionResult.builder()
            .status("FAILED")
            .failureReason("NO_VALID_CLICKS")
            .build();
    }
}
```

---

#### 4. Kafka Sink 配置问题

**可能原因**:
- Producer 未正确初始化
- Topic 权限问题
- 序列化失败

**检查日志**:
```bash
docker logs flink-taskmanager 2>&1 | grep -E "ERROR|WARN.*Sink|Kafka" | tail -50
```

---

#### 5. FlussKVClient 状态问题

**可能原因**:
- 本地缓存未命中
- 状态未正确保存
- 读取返回 null

**当前模式**: 本地缓存（Caffeine）

**检查**:
```java
// FlussKVClient.get()
public FlussClickSession get(String userId) {
    if (localCache != null) {
        FlussClickSession cached = localCache.getIfPresent(userId);
        if (cached != null) {
            return cached; // 缓存命中
        }
    }
    return null; // 缓存未命中
}
```

---

## 🔍 下一步排查

### 优先级 1: 检查 Consumer Group

```bash
# 列出所有 Consumer Group
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list

# 查看归因作业的 Consumer Group
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group attribution-click-group

docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group attribution-conversion-group
```

---

### 优先级 2: 检查 TaskManager 日志

```bash
docker exec flink-taskmanager cat /opt/flink/log/*.log 2>/dev/null | tail -100
```

---

### 优先级 3: 检查背压

```bash
curl http://localhost:8081/jobs/7a28233f6fc3.../backpressure
```

---

### 优先级 4: 添加调试日志

**修改代码添加日志**:
```java
// AttributionProcessFunction.java
@Override
public void processElement2(ConversionEvent conversion, Context ctx, Collector<AttributionResult> out) {
    log.info("Processing conversion: userId={}, eventId={}", 
        conversion.getUserId(), conversion.getEventId());
    
    FlussClickSession session = getClickSession(conversion.getUserId());
    log.info("Session loaded: {}", session != null ? "YES" : "NO");
    
    AttributionResult result = attributionEngine.attribute(conversion, session);
    log.info("Attribution result: status={}", result.getStatus());
    
    out.collect(result);
}
```

---

## 📝 测试结论

### 已确认正常

- ✅ Flink 作业运行正常
- ✅ 无异常和错误
- ✅ RocketMQ 连接错误已修复
- ✅ Kafka Topic 正常接收消息
- ✅ 测试数据成功发送

### 待排查问题

- ❌ 归因结果未生成（Success: 0, Failed: 0）
- ❌ Consumer Group 状态未知
- ❌ TaskManager 日志未检查
- ❌ 背压情况未知
- ❌ AttributionEngine 逻辑未验证

---

## 🎯 下一步行动

### 立即执行

1. **检查 Consumer Group** - 确认消费正常
2. **查看 TaskManager 日志** - 查找错误
3. **检查背压** - 确认无阻塞

### 短期计划

1. **添加调试日志** - 定位问题
2. **验证 AttributionEngine** - 单元测试
3. **检查 KeyBy 逻辑** - 确认分区正确

### 长期计划

1. **完善监控** - Prometheus + Grafana
2. **集成测试** - 自动化测试
3. **性能优化** - 调整并行度

---

**测试人员**: SimpleAttributeSystem Team  
**测试时间**: 2026-02-23 20:49  
**测试版本**: 2.0.0-SNAPSHOT  
**当前状态**: ⚠️ 需要进一步排查
