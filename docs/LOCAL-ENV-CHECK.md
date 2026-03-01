# 本地环境检查报告

**检查时间**: 2026-02-24 12:15  
**目的**: v2.1 测试前环境准备

---

## ✅ 已运行的服务

| 服务 | 容器名 | 状态 | 端口 | 说明 |
|------|--------|------|------|------|
| **Flink JobManager** | flink-jobmanager | ✅ Running | 8081 | Flink 1.17.1 |
| **Kafka** | kafka | ✅ Running (healthy) | 9092/29092 | 消息队列 |
| **RocketMQ Namesrv** | rocketmq-namesrv | ✅ Running | 9876 | 名称服务 |
| **RocketMQ Broker** | rocketmq-broker | ✅ Running | 10909/10911 | 消息代理 |
| **RocketMQ Console** | rocketmq-console | ✅ Running | 8082 | Web 控制台 |
| **Kafka UI** | kafka-ui | ✅ Running | 8090 | Web 控制台 |

---

## ❌ 未运行的服务

| 服务 | 状态 | 说明 | 是否需要 |
|------|------|------|---------|
| **Fluss** | ❌ 未运行 | Fluss KV Store | ⚠️ **需要启动** 或 使用本地缓存模式 |
| **Flink TaskManager** | ❌ 未运行 | Flink 工作节点 | ⚠️ **需要启动** |

---

## 🔧 配置调整

### 1. 并行度已调整为 1

```yaml
# Click Writer Job
job.parallelism=1  # 本地测试

# Attribution Engine Job  
job.parallelism=1  # 本地测试
```

### 2. Fluss 使用本地缓存模式

当前 `FlussKVClient` 默认使用 Caffeine 本地缓存，**不需要启动 Fluss 集群**即可测试。

配置：
```java
// FlussSourceConfig.createDefault()
enableCache = true
cacheMaxSize = 10000
cacheExpireMinutes = 60
```

---

## 📋 需要执行的操作

### 方案 A: 使用本地缓存模式（推荐，快速测试）

**优点**:
- ✅ 不需要启动 Fluss 集群
- ✅ 快速验证功能
- ✅ 资源消耗少

**缺点**:
- ❌ 数据只在内存中，重启丢失
- ❌ 无法测试真正的 Fluss 集成

**步骤**:
1. 直接使用当前配置
2. 启动 Flink 任务
3. 数据会存储在 Caffeine 缓存中

---

### 方案 B: 启动 Fluss 集群（完整测试）

**优点**:
- ✅ 完整测试 v2.1 架构
- ✅ 数据持久化
- ✅ 可以测试 Fluss KV 性能

**缺点**:
- ❌ 需要额外资源
- ❌ 配置复杂

**步骤**:
```bash
# 1. 启动 Fluss（如果有 Docker 镜像）
docker run -d --name fluss-server \
  -p 9110:9110 \
  apache/fluss:0.8.0

# 2. 创建 KV Table
# (需要 Fluss CLI 或 REST API)
```

---

## 🚀 建议的测试流程

### Step 1: 检查 Flink 环境

```bash
# 1. 访问 Flink Web UI
open http://localhost:8081

# 2. 检查是否有 TaskManager
# 如果没有，需要启动 TaskManager
docker run -d --name flink-taskmanager \
  --link flink-jobmanager:jobmanager \
  flink:1.17.1 taskmanager
```

### Step 2: 创建 Kafka Topics

```bash
# 使用 Docker exec
docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic click-events \
  --partitions 1 \
  --replication-factor 1

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic conversion-events \
  --partitions 1 \
  --replication-factor 1

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic attribution-results-success \
  --partitions 1 \
  --replication-factor 1

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic attribution-results-failed \
  --partitions 1 \
  --replication-factor 1
```

### Step 3: 编译打包

```bash
cd SimpleAttributeSystem
mvn clean package -DskipTests
```

### Step 4: 提交任务

```bash
# 方式 1: 使用 Flink Web UI
# 访问 http://localhost:8081
# 上传 JAR 并提交

# 方式 2: 使用命令行（需要安装 Flink CLI）
docker exec flink-jobmanager flink run \
  -c com.attribution.job.ClickWriterJob \
  /opt/simple-attribute-system-2.1.0.jar
```

---

## ⚠️ 注意事项

### 1. Flink TaskManager

当前只有 JobManager，没有 TaskManager。需要启动 TaskManager 才能运行任务。

**启动命令**:
```bash
docker run -d --name flink-taskmanager \
  --link flink-jobmanager:jobmanager \
  -e FLINK_PROPERTIES="jobmanager.rpc.address: jobmanager" \
  flink:1.17.1 taskmanager
```

### 2. 网络连接

Flink 任务需要能够访问：
- Kafka: `kafka:29092` (Docker 网络) 或 `localhost:9092` (本地)
- RocketMQ: `rocketmq-namesrv:9876` (Docker 网络) 或 `localhost:9876` (本地)

**建议**: 使用 Docker 网络，修改配置中的主机名。

### 3. JAR 文件路径

提交任务时需要 JAR 文件路径。可以：
- 挂载本地目录到 Flink 容器
- 或复制到容器内

---

## 📝 快速启动脚本

创建 `scripts/quick-start-local.sh`:

```bash
#!/bin/bash

echo "========================================="
echo "本地测试环境快速启动"
echo "========================================="

# 1. 启动 Flink TaskManager
echo "Starting Flink TaskManager..."
docker run -d --name flink-taskmanager \
  --link flink-jobmanager:jobmanager \
  -e FLINK_PROPERTIES="jobmanager.rpc.address: jobmanager" \
  flink:1.17.1 taskmanager

# 2. 创建 Kafka Topics
echo "Creating Kafka topics..."
docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic click-events --partitions 1 --replication-factor 1 2>/dev/null || true

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic conversion-events --partitions 1 --replication-factor 1 2>/dev/null || true

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic attribution-results-success --partitions 1 --replication-factor 1 2>/dev/null || true

docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic attribution-results-failed --partitions 1 --replication-factor 1 2>/dev/null || true

# 3. 编译
echo "Building project..."
mvn clean package -DskipTests

# 4. 提交任务
echo "Submitting Click Writer Job..."
docker cp target/simple-attribute-system-2.1.0.jar flink-jobmanager:/opt/

docker exec flink-jobmanager flink run \
  -c com.attribution.job.ClickWriterJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

echo ""
echo "========================================="
echo "启动完成！"
echo "========================================="
echo "Flink Web UI: http://localhost:8081"
echo "Kafka UI: http://localhost:8090"
echo "RocketMQ Console: http://localhost:8082"
echo ""
```

---

## ✅ 检查清单

- [x] Flink JobManager 运行中
- [ ] Flink TaskManager 需要启动
- [x] Kafka 运行中
- [x] RocketMQ 运行中
- [ ] Kafka Topics 需要创建
- [x] 并行度已调整为 1
- [x] Fluss 使用本地缓存模式（可选）
- [ ] 项目需要重新编译
- [ ] 任务需要提交

---

**准备就绪度**: 80%  
**下一步**: 启动 TaskManager，创建 Topics，编译并提交任务

---

*根据您的需求，可以选择方案 A（快速测试）或方案 B（完整测试）*
