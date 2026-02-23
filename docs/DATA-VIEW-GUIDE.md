# 归因系统数据查看指南

**更新时间**: 2026-02-23  
**系统状态**: ✅ 运行中

---

## 📊 当前数据流架构

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Kafka         │     │   Flink      │     │   Kafka         │
│   click-events  │────▶│  Attribution │────▶│   attribution-  │
│   (5 条消息)     │     │   Engine     │     │   results       │
└─────────────────┘     └──────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌──────────────┐
                        │  Flink State │
                        │  (用户会话)   │
                        └──────────────┘
```

---

## 🔍 数据查看方式

### 1. 查看 Kafka 消息 (推荐 ⭐)

**Web UI**: http://localhost:8090

**命令行**:
```bash
# 查看点击事件
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic click-events \
  --from-beginning

# 查看转化事件
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic conversion-events \
  --from-beginning

# 查看归因结果
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic attribution-results-success \
  --from-beginning
```

**预期结果**:
- click-events: 5 条消息
- conversion-events: 2 条消息
- attribution-results-success: 2 条消息

---

### 2. 查看 Flink 作业状态

**Web UI**: http://localhost:8081

**查看内容**:
- 作业运行状态
- Task 处理指标
- Checkpoint 状态
- 反压情况

**命令行**:
```bash
# 查看作业列表
curl http://localhost:8081/jobs

# 查看作业详情
curl http://localhost:8081/jobs/c1d5e3aafc97b4a9b6692f3e2f254119

# 查看异常
curl http://localhost:8081/jobs/c1d5e3aafc97b4a9b6692f3e2f254119/exceptions
```

---

### 3. 查看 Flink TaskManager 日志

```bash
# 查看归因结果日志
docker logs flink-taskmanager --tail 500 | grep -E "Attribution|result"

# 查看用户会话状态
docker logs flink-taskmanager --tail 500 | grep -E "user-|clickCount"

# 查看错误日志
docker logs flink-taskmanager --tail 500 | grep -E "ERROR|Exception"
```

---

### 4. 查看 Flink State (用户会话)

**通过 Web UI**:
1. 访问 http://localhost:8081
2. 点击作业 `Simple Attribute System`
3. 点击 `Attribution Processor`
4. 查看 `State` 标签

**State 内容**:
- 用户点击会话 (FlussClickSession)
- 去重状态 (lastProcessedEventId)

---

### 5. Fluss 数据 (未来扩展)

**当前状态**: ⏳ 未启用 (可选)

**启用步骤**:
```bash
# 1. 启动 Fluss (需要添加到 docker-compose)
docker-compose up -d fluss

# 2. 创建表
fluss sql-client << EOF
CREATE DATABASE attribution;
CREATE TABLE attribution.click_events (...);
CREATE TABLE attribution.attribution_results (...);
EOF

# 3. 修改 Flink 作业写入 Fluss
# (需要重新编译和部署)
```

---

## 📈 实时数据监控

### Kafka 消息速率

```bash
# 查看 Topic 分区信息
docker exec kafka kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic click-events
```

### Flink 处理指标

访问 http://localhost:8081 查看：
- Records In/Out
- Processing Latency
- Checkpoint 状态

---

## 🧪 验证数据完整性

### 测试流程

1. **发送测试数据**
```bash
# 发送 Click
echo '{"event_id":"test-001","user_id":"test-user","timestamp":1708675200000}' | \
docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic click-events

# 发送 Conversion
echo '{"event_id":"test-conv-001","user_id":"test-user","timestamp":1708678800000}' | \
docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic conversion-events
```

2. **查看归因结果**
```bash
docker logs flink-taskmanager --tail 100 | grep "test-user"
```

3. **验证结果**
- 检查归因模型是否正确
- 检查功劳分配是否正确
- 检查转化价值是否正确

---

## 📊 当前数据快照

**截至 2026-02-23 17:22**:

| 数据源 | 消息数 | 状态 |
|--------|--------|------|
| click-events | 5 | ✅ 已消费 |
| conversion-events | 2 | ✅ 已消费 |
| attribution-results | 2 | ✅ 已输出 |
| Flink State | 2 用户 | ✅ 正常 |

**归因结果**:
- user-123: conv-001 → click-003 (199.99 CNY)
- user-456: conv-002 → click-005 (299.99 CNY)

---

## 🔧 故障排查

### 问题 1: 看不到消息

**可能原因**:
- Topic 不存在
- 消费者组配置错误
- 消息已过期

**解决方案**:
```bash
# 检查 Topic
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# 检查消费者组
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list

# 查看消费者组详情
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group attribution-click-group
```

### 问题 2: Flink 作业异常

**查看异常**:
```bash
curl http://localhost:8081/jobs/<job-id>/exceptions | python3 -m json.tool
```

**重启作业**:
```bash
# 取消作业
docker exec flink-jobmanager flink cancel <job-id>

# 重新提交
docker exec flink-jobmanager flink run -d -c com.attribution.AttributionJob \
  /opt/flink/usrlib/simple-attribute-system.jar
```

---

## 📞 快速诊断命令

```bash
# 一键查看系统状态
echo "=== Flink 作业状态 ===" && \
curl -s http://localhost:8081/overview | python3 -m json.tool && \
echo "" && \
echo "=== Kafka Topic ===" && \
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 && \
echo "" && \
echo "=== 最近归因日志 ===" && \
docker logs flink-taskmanager --tail 50 | grep "Attribution"
```

---

**最后更新**: 2026-02-23 17:22
