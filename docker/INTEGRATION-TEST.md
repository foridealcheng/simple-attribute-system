# Docker 集成测试指南

**版本**: 2.0.0  
**日期**: 2026-02-23  
**状态**: ✅ Ready

---

## 📋 测试目标

验证整个归因系统在 Docker 环境中的完整功能：

1. ✅ Docker 服务启动（Kafka, Flink, Zookeeper）
2. ✅ Kafka Topic 创建
3. ✅ 项目编译
4. ✅ 测试数据发送
5. ✅ 归因计算
6. ✅ 结果验证

---

## 🚀 快速开始

### 方式 1: 自动测试（推荐）

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem/docker

# 执行完整集成测试
./integration-test.sh
```

**自动执行**:
1. 启动 Docker 服务
2. 等待服务健康
3. 初始化 Kafka Topic
4. 编译项目
5. 发送测试数据
6. 验证结果

---

### 方式 2: 手动测试

#### 步骤 1: 启动服务

```bash
cd docker
docker-compose up -d

# 等待服务启动
sleep 15
```

#### 步骤 2: 检查服务状态

```bash
# 查看容器状态
docker-compose ps

# 预期输出：
# NAME              STATUS
# zookeeper         Up (healthy)
# kafka             Up (healthy)
# flink-jobmanager  Up
# flink-taskmanager Up
# kafka-ui          Up
```

#### 步骤 3: 初始化 Topic

```bash
cd ..
./scripts/init-topics.sh
```

#### 步骤 4: 编译项目

```bash
mvn clean package -DskipTests
```

#### 步骤 5: 提交 Flink 作业

```bash
./scripts/submit-job.sh
```

#### 步骤 6: 发送测试数据

```bash
# 发送 Click 事件
cat <<EOF | docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic click-events
{"event_id":"click-001","user_id":"user-123","timestamp":1708675200000,"advertiser_id":"adv-001","campaign_id":"camp-001","creative_id":"creative-001","click_type":"click"}
{"event_id":"click-002","user_id":"user-123","timestamp":1708675201000,"advertiser_id":"adv-001","campaign_id":"camp-001","creative_id":"creative-002","click_type":"click"}
EOF

# 发送 Conversion 事件
cat <<EOF | docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic conversion-events
{"event_id":"conv-001","user_id":"user-123","timestamp":1708675202000,"conversion_type":"purchase","conversion_value":100.0}
EOF
```

#### 步骤 7: 查看结果

```bash
# 查看归因结果
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic attribution-results-success \
  --from-beginning \
  --timeout-ms 10000
```

---

## ✅ 验证测试

### 自动验证

```bash
cd docker
./verify-test.sh
```

### 手动验证

#### 1. 检查 Docker 容器

```bash
docker-compose ps
```

**预期**: 所有容器状态为 `Up`

#### 2. 检查 Kafka Topic

```bash
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

**预期**:
```
click-events
conversion-events
attribution-results-success
attribution-results-failed
```

#### 3. 检查 Flink 作业

```bash
curl http://localhost:8081/jobs | python3 -m json.tool
```

**预期**: 显示运行的作业列表

#### 4. 检查消息数量

```bash
# Click Events
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic click-events

# Conversion Events
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic conversion-events

# Attribution Results
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic attribution-results-success
```

---

## 📊 测试报告

### 示例输出

```
========================================
  归因系统 Docker 集成测试
========================================

[1/8] 启动 Docker 服务...
[2/8] 等待服务启动...
[3/8] 检查服务健康状态...
✓ Zookeeper 正常 (2181)
✓ Kafka 正常 (9092)
✓ Flink JobManager 正常 (8081)
✓ Kafka UI 正常 (8090)
[4/8] 初始化 Kafka Topic...
[5/8] 验证 Topic 创建...
✓ Topic 'click-events' 已创建
✓ Topic 'conversion-events' 已创建
✓ Topic 'attribution-results-success' 已创建
✓ Topic 'attribution-results-failed' 已创建
[6/8] 编译项目...
✓ 编译成功
[7/8] 发送测试数据...
发送 Click 事件...
发送 Conversion 事件...
✓ 测试数据发送完成
[8/8] 等待归因计算...
检查归因结果...
✓ 归因结果生成成功 (成功：1)

Flink 作业状态:
{
  "jobs": [
    {
      "id": "abc123...",
      "status": "RUNNING"
    }
  ]
}

========================================
  集成测试完成！
========================================
```

---

## 🐛 故障排查

### 问题 1: 容器启动失败

```bash
# 查看日志
docker-compose logs zookeeper
docker-compose logs kafka

# 重启服务
docker-compose restart

# 完全重置
docker-compose down -v
docker-compose up -d
```

### 问题 2: Kafka 连接失败

```bash
# 检查 Kafka 是否可访问
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092

# 重置 Kafka
docker-compose stop kafka
docker-compose rm -f kafka
docker-compose up -d kafka
```

### 问题 3: Flink 作业提交失败

```bash
# 检查 JobManager 状态
curl http://localhost:8081/overview

# 查看 Flink 日志
docker exec flink-jobmanager tail -100 /opt/flink/log/flink-*-jobmanager-*.log

# 重启 Flink
docker-compose restart flink-jobmanager flink-taskmanager
```

### 问题 4: 归因结果未生成

```bash
# 检查 Flink 作业状态
curl http://localhost:8081/jobs

# 检查 Checkpoint
curl http://localhost:8081/jobs/<job-id>/checkpoints

# 查看 TaskManager 日志
docker exec flink-taskmanager tail -100 /opt/flink/log/flink-*-taskmanager-*.log
```

---

## 📈 性能测试

### Kafka 生产性能

```bash
docker exec kafka kafka-producer-perf-test \
  --topic click-events \
  --num-records 10000 \
  --record-size 1024 \
  --throughput 1000 \
  --producer-props bootstrap-server=localhost:9092
```

### Flink 处理延迟

```bash
# 发送带时间戳的消息
# 记录从发送到接收的时间差
# 预期：< 1 秒
```

---

## 🧪 清理环境

### 停止服务

```bash
./scripts/stop-local.sh
```

### 完全清理

```bash
cd docker
docker-compose down -v
```

### 删除所有数据

```bash
# 停止并删除容器
docker-compose down -v --remove-orphans

# 删除镜像（可选）
docker rmi flink:1.17.1
docker rmi confluentinc/cp-kafka:7.5.0
docker rmi confluentinc/cp-zookeeper:7.5.0
```

---

## 📝 测试清单

### 必测项

- [ ] Docker 容器全部启动
- [ ] 所有服务健康检查通过
- [ ] Kafka Topic 创建成功
- [ ] 项目编译成功
- [ ] Flink 作业提交成功
- [ ] 测试数据发送成功
- [ ] 归因结果生成

### 选测项

- [ ] Kafka UI 可访问
- [ ] Flink UI 可访问
- [ ] 消息顺序正确
- [ ] 归因逻辑正确
- [ ] 性能达标

---

## 🔗 相关文档

- [本地部署指南](./README.md)
- [Kafka Sink 文档](../docs/KAFKA-SINK-COMPLETE.md)
- [RocketMQ 重试文档](../docs/ROCKETMQ-RETRY-COMPLETE.md)
- [Fluss 集成文档](../docs/FLUSS-INTEGRATION-COMPLETE.md)

---

**最后更新**: 2026-02-23  
**维护者**: SimpleAttributeSystem Team
