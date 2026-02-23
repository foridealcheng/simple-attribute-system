# 本地测试环境部署指南

> 快速搭建归因系统本地开发和测试环境

---

## 📋 前置要求

- **Docker Desktop** (Mac/Windows) 或 **Docker + Docker Compose** (Linux)
- **Java 11+**
- **Maven 3.8+**
- **Git**

---

## 🚀 快速开始

### 1. 启动本地环境

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem

# 启动所有服务（Kafka, Flink, Zookeeper, Kafka UI）
./scripts/start-local.sh
```

**启动的服务**:
- Zookeeper (2181)
- Kafka (9092)
- Flink JobManager (8081)
- Flink TaskManager
- Kafka UI (8090)

---

### 2. 初始化 Kafka Topic

```bash
./scripts/init-topics.sh
```

**创建的 Topic**:
- `click-events` - 广告点击事件
- `conversion-events` - 用户转化事件
- `attribution-results-success` - 归因成功结果
- `attribution-results-failed` - 归因失败结果

---

### 3. 编译项目

```bash
mvn clean package -DskipTests
```

---

### 4. 提交 Flink 作业

```bash
./scripts/submit-job.sh
```

---

### 5. 访问 Web UI

- **Flink UI**: http://localhost:8081
- **Kafka UI**: http://localhost:8090

---

## 🔧 常用命令

### 启动/停止服务

```bash
# 启动
./scripts/start-local.sh

# 停止
./scripts/stop-local.sh

# 重启
./scripts/restart-local.sh
```

### 查看日志

```bash
# 查看所有服务日志
cd docker
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f kafka
docker-compose logs -f flink-jobmanager
docker-compose logs -f flink-taskmanager
```

### 管理 Kafka Topic

```bash
# 列出所有 Topic
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# 查看 Topic 详情
docker exec kafka kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic click-events

# 删除 Topic
docker exec kafka kafka-topics --delete \
  --bootstrap-server localhost:9092 \
  --topic click-events
```

---

## 📝 测试数据

### 发送测试消息

```bash
# 发送 Click 事件
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic click-events

# 输入 JSON 消息
{"event_id":"click-001","user_id":"user-123","timestamp":1708675200000,"advertiser_id":"adv-001","campaign_id":"camp-001"}
```

### 消费消息

```bash
# 消费 Click 事件
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic click-events \
  --from-beginning
```

---

## 🐛 故障排查

### 服务启动失败

```bash
# 查看 Docker 容器状态
docker-compose ps

# 查看服务日志
docker-compose logs <service-name>

# 重启特定服务
docker-compose restart <service-name>
```

### Kafka 连接问题

```bash
# 检查 Kafka 是否运行
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092

# 重置 Kafka 数据
docker-compose down -v
docker-compose up -d
```

### Flink 作业提交失败

```bash
# 检查 JobManager 状态
curl http://localhost:8081/overview

# 查看 Flink 日志
docker exec flink-jobmanager cat /opt/flink/log/flink-*-jobmanager-*.log
```

---

## 📊 监控指标

### Flink 指标

访问：http://localhost:8081

- JobManager 状态
- TaskManager 资源使用
- Checkpoint 状态
- 作业运行状态

### Kafka 指标

访问：http://localhost:8090

- Topic 列表和详情
- Consumer Groups
- 消息生产/消费速率
- Partition 分布

---

## 🎯 下一步

1. **发送测试数据** - 使用 kafka-console-producer
2. **观察归因结果** - 在 Kafka UI 查看 attribution-results-success
3. **调整配置** - 修改 config/flink-conf.yaml
4. **性能测试** - 使用 kafka-producer-perf-test

---

## 📞 支持

遇到问题？

1. 查看日志：`docker-compose logs`
2. 检查配置：`config/flink-conf.yaml`
3. 重启服务：`./scripts/restart-local.sh`

---

**最后更新**: 2026-02-23
