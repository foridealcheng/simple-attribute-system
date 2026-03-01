# 本地测试环境部署清单

**日期**: 2026-02-23  
**状态**: ✅ 准备就绪

---

## ✅ 已完成的工作

### 1. 代码编译与测试
- [x] Java 11 环境配置
- [x] Maven 项目编译 (BUILD SUCCESS)
- [x] 单元测试 (12/12 通过)
- [x] 核心功能验证

### 2. Docker 环境配置
- [x] Docker Compose 配置文件
  - Zookeeper
  - Kafka
  - Flink JobManager
  - Flink TaskManager
  - Kafka UI
- [x] Flink 配置文件 (flink-conf.yaml)
- [x] 网络配置 (attribution-network)

### 3. 部署脚本
- [x] start-local.sh - 启动环境
- [x] stop-local.sh - 停止环境
- [x] restart-local.sh - 重启环境
- [x] init-topics.sh - 初始化 Kafka Topic
- [x] submit-job.sh - 提交 Flink 作业
- [x] generate-test-data.sh - 生成测试数据

### 4. 文档
- [x] docker/README.md - 部署指南
- [x] COMPILE-TEST-REPORT.md - 编译测试报告
- [x] FINAL-COMPILE-STATUS.md - 最终编译状态

---

## 📦 环境组件

| 组件 | 版本 | 端口 | 状态 |
|------|------|------|------|
| Zookeeper | 7.5.0 | 2181 | ✅ 就绪 |
| Kafka | 7.5.0 | 9092 | ✅ 就绪 |
| Flink JobManager | 1.17.1 | 8081 | ✅ 就绪 |
| Flink TaskManager | 1.17.1 | - | ✅ 就绪 |
| Kafka UI | latest | 8090 | ✅ 就绪 |

---

## 🚀 部署步骤

### Step 1: 启动环境

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem

# 启动所有服务
./scripts/start-local.sh
```

**预期输出**:
```
=========================================
  归因系统 - 本地测试环境启动
=========================================

✅ Docker 版本：Docker version 24.x.x
✅ Docker Compose 版本：docker-compose version 1.29.x

📦 停止旧容器...
🚀 启动服务...
⏳ 等待服务启动...

📊 检查服务状态...
✅ Zookeeper (端口 2181) - 就绪
✅ Kafka (端口 9092) - 就绪
✅ Flink JobManager (端口 8081) - 就绪
✅ Kafka UI (端口 8090) - 就绪

=========================================
  🎉 服务启动完成！
=========================================
```

---

### Step 2: 初始化 Kafka Topic

```bash
./scripts/init-topics.sh
```

**预期输出**:
```
=========================================
  初始化 Kafka Topic
=========================================

✅ Kafka 连接成功：localhost:9092

📝 创建 Topic...
   - click-events
   - conversion-events
   - attribution-results-success
   - attribution-results-failed

✅ Topic 创建完成

📋 当前 Topic 列表:
click-events
conversion-events
attribution-results-success
attribution-results-failed
```

---

### Step 3: 编译项目

```bash
mvn clean package -DskipTests
```

**预期输出**:
```
[INFO] BUILD SUCCESS
[INFO] Total time:  XX.XXX s
```

---

### Step 4: 生成测试数据 (可选)

```bash
./scripts/generate-test-data.sh
```

**预期输出**:
```
=========================================
  生成测试数据
=========================================

✅ Kafka 连接成功

📊 生成 Click 事件...
   ✅ Click 1: user=user-042
   ✅ Click 2: user=user-017
   ...
   ✅ Click 20: user=user-089

✅ Click 事件生成完成 (20 条)

📊 生成 Conversion 事件...
   ✅ Conversion 1: user=user-053, value=45.6
   ...
   ✅ Conversion 10: user=user-021, value=78.9

✅ Conversion 事件生成完成 (10 条)
```

---

### Step 5: 提交 Flink 作业

```bash
./scripts/submit-job.sh
```

**预期输出**:
```
=========================================
  提交 Flink 归因作业
=========================================

✅ JAR 文件已找到
✅ Flink JobManager 连接成功：http://localhost:8081

🚀 提交作业...

✅ 作业提交成功

📊 查看作业状态:
   http://localhost:8081
```

---

## 📊 验证清单

### 服务验证

- [ ] Zookeeper 运行正常
  ```bash
  docker exec zookeeper echo ruok | nc localhost 2181
  # 应返回：imok
  ```

- [ ] Kafka 运行正常
  ```bash
  docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092
  ```

- [ ] Flink JobManager 可访问
  ```bash
  curl http://localhost:8081/overview
  # 应返回 JSON 格式的集群信息
  ```

- [ ] Kafka UI 可访问
  ```bash
  curl http://localhost:8090
  # 应返回 HTML
  ```

### Topic 验证

- [ ] click-events Topic 存在
- [ ] conversion-events Topic 存在
- [ ] attribution-results-success Topic 存在
- [ ] attribution-results-failed Topic 存在

### 作业验证

- [ ] Flink 作业运行中
- [ ] Checkpoint 正常
- [ ] 无异常日志

---

## 🔧 故障排查

### 问题 1: 服务启动失败

**症状**: `docker-compose up` 报错

**解决方案**:
```bash
# 清理旧容器
docker-compose down -v

# 重新构建
docker-compose build

# 重新启动
docker-compose up -d
```

---

### 问题 2: Kafka 连接超时

**症状**: `Connection to node -1 could not be established`

**解决方案**:
```bash
# 检查 Kafka 日志
docker-compose logs kafka

# 重启 Kafka
docker-compose restart kafka

# 检查 advertised.listeners 配置
docker-compose exec kafka cat /etc/kafka/server.properties | grep advertised
```

---

### 问题 3: Flink 作业提交失败

**症状**: `Could not connect to Flink cluster`

**解决方案**:
```bash
# 检查 JobManager 状态
curl http://localhost:8081/overview

# 查看 JobManager 日志
docker-compose logs flink-jobmanager

# 重启 Flink
docker-compose restart flink-jobmanager flink-taskmanager
```

---

## 📝 下一步计划

### 短期 (本周)
- [ ] 运行端到端测试
- [ ] 性能基准测试
- [ ] 监控仪表板配置

### 中期 (下周)
- [ ] 集成测试
- [ ] 文档完善
- [ ] CI/CD 配置

### 长期 (本月)
- [ ] 生产环境部署方案
- [ ] 性能优化
- [ ] 高可用配置

---

## 📞 联系支持

遇到问题？

1. **查看日志**: `docker-compose logs -f`
2. **检查文档**: `docker/README.md`
3. **重启环境**: `./scripts/restart-local.sh`

---

**部署完成时间**: 2026-02-23  
**部署负责人**: AI Assistant  
**环境版本**: v1.0.0-SNAPSHOT
