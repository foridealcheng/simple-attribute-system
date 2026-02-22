# 设计文档 05 - 部署设计

> 最小化部署方案，支持本地开发

**版本**: v1.3  
**创建时间**: 2026-02-22  
**最后更新**: 2026-02-22 22:17  
**状态**: ✅ Approved  
**关联任务**: T010

---

## 1. 核心组件

| 组件 | 用途 | 必需 |
|------|------|------|
| **Flink** | 流处理引擎 | ✅ |
| **Fluss** | 消息队列 + KV 存储 | ✅ |
| **RocketMQ** | 重试队列 | ✅ |
| **ZooKeeper** | 协调服务 | ✅ |

---

## 2. 本地开发（Mac）

### 2.1 最低要求

| 资源 | 要求 | 说明 |
|------|------|------|
| **CPU** | 4 核 | 可运行 |
| **内存** | 8G | 最低，16G 推荐 |
| **磁盘** | 20G | 足够 |
| **系统** | macOS 12+ | Intel/Apple Silicon |

### 2.2 docker-compose.yml（最小化）

```yaml
# docker-compose.yml
version: '3.8'

services:
  # ZooKeeper (单节点)
  zookeeper:
    image: zookeeper:3.8
    ports:
      - "2181:2181"
    environment:
      - ZOO_MY_ID=1
    volumes:
      - zookeeper-data:/data

  # Flink (单 JobManager + 单 TaskManager)
  flink-jobmanager:
    image: flink:1.18
    command: jobmanager
    ports:
      - "8081:8081"
    depends_on:
      - zookeeper
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: flink-jobmanager
        high-availability: zookeeper
        high-availability.zookeeper.quorum: zookeeper:2181
        high-availability.storageDir: file:///tmp/flink-ha/
        state.backend: hashmap
        state.checkpoints.dir: file:///tmp/flink-checkpoints
        taskmanager.numberOfTaskSlots: 2
    volumes:
      - /tmp/flink:/tmp/flink
      - ./artifacts:/opt/flink/artifacts

  flink-taskmanager:
    image: flink:1.18
    command: taskmanager
    depends_on:
      - flink-jobmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: flink-jobmanager
        taskmanager.numberOfTaskSlots: 2
    volumes:
      - /tmp/flink:/tmp/flink

  # Fluss (单节点)
  fluss:
    image: apache/fluss:0.4
    ports:
      - "9110:9110"
    depends_on:
      - zookeeper
    environment:
      - ZOOKEEPER_CONNECT=zookeeper:2181
    volumes:
      - fluss-data:/opt/fluss/data

  # RocketMQ (单 NameServer + 单 Broker)
  rmq-nameserver:
    image: apache/rocketmq:5.0
    command: sh mqnamesrv
    ports:
      - "9876:9876"
    environment:
      - JAVA_OPTS=-Xms128m -Xmx128m

  rmq-broker:
    image: apache/rocketmq:5.0
    command: sh mqbroker -n rmq-nameserver:9876
    depends_on:
      - rmq-nameserver
    ports:
      - "10911:10911"
    environment:
      - JAVA_OPTS=-Xms256m -Xmx256m

volumes:
  zookeeper-data:
  fluss-data:
```

### 2.3 启动命令

```bash
# 1. 创建目录
mkdir -p artifacts /tmp/flink/{checkpoints,ha}

# 2. 启动服务
docker-compose up -d

# 3. 查看状态
docker-compose ps

# 4. 访问 Flink UI
open http://localhost:8081
```

### 2.4 初始化 Topic

```bash
#!/bin/bash
# init-local.sh

# Fluss Topic
docker-compose exec fluss fluss create table \
  --bootstrap-servers localhost:9110 \
  click-events-stream --partitions 2 \
  --schema "eventId:STRING,userId:STRING,timestamp:BIGINT"

docker-compose exec fluss fluss create table \
  --bootstrap-servers localhost:9110 \
  conversion-events-stream --partitions 2 \
  --schema "eventId:STRING,userId:STRING,timestamp:BIGINT"

docker-compose exec fluss fluss create table \
  --bootstrap-servers localhost:9110 \
  attribution-clicks --table-type kv \
  --schema "user_id:STRING,clicks_data:STRING"

docker-compose exec fluss fluss create table \
  --bootstrap-servers localhost:9110 \
  attribution-results-success --partitions 2 \
  --schema "attribution_id:STRING,user_id:STRING"

docker-compose exec fluss fluss create table \
  --bootstrap-servers localhost:9110 \
  attribution-results-failed --partitions 2 \
  --schema "attribution_id:STRING,user_id:STRING"

# RocketMQ Topic
docker-compose exec rmq-broker mqadmin updateTopic \
  -n localhost:9876 \
  -c DefaultCluster \
  -t attribution-retry-topic \
  -r 2 -w 2

docker-compose exec rmq-broker mqadmin updateTopic \
  -n localhost:9876 \
  -c DefaultCluster \
  -t attribution-dlq-topic \
  -r 2 -w 2

echo "✅ Local environment ready!"
```

### 2.5 提交作业

```bash
# 提交 Flink 作业
docker-compose exec flink-jobmanager flink run \
  -d -c com.attribution.AttributionJob \
  /opt/flink/artifacts/attribution-engine.jar \
  --fluss.bootstrap.servers fluss:9110 \
  --rocketmq.name.server rmq-nameserver:9876
```

### 2.6 停止服务

```bash
# 停止所有服务
docker-compose down

# 停止并清理数据
docker-compose down -v

# 查看日志
docker-compose logs -f flink-jobmanager
```

---

## 3. 生产环境

### 3.1 小规模（100 万事件/天）

| 组件 | 节点 | CPU | 内存 |
|------|------|-----|------|
| Flink JM | 2 (HA) | 2C | 4G |
| Flink TM | 2 | 4C | 8G |
| Fluss | 3 | 4C | 8G |
| RocketMQ | 2 | 4C | 8G |
| ZooKeeper | 3 | 2C | 4G |

**总计**: 12 节点，40C, 88G

### 3.2 K8s 部署

```yaml
# flink-deployment.yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: attribution-engine
spec:
  image: flink:1.18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "4"
    state.backend: rocksdb
    state.checkpoints.dir: s3://attribution/checkpoints/
    execution.checkpointing.interval: "60000"
  jobManager:
    resource:
      memory: 8Gi
      cpu: 4.0
  taskManager:
    resource:
      memory: 16Gi
      cpu: 8.0
    replicas: 4
  job:
    jarURI: local:///opt/flink/usrlib/attribution-engine.jar
    parallelism: 16
    state: running
```

---

## 4. 快速开始

### 4.1 本地开发（5 分钟启动）

```bash
# 1. 克隆项目
git clone https://github.com/your-org/simple-attribute-system.git
cd simple-attribute-system

# 2. 启动服务
docker-compose up -d

# 3. 初始化
./init-local.sh

# 4. 访问 Flink UI
open http://localhost:8081
```

### 4.2 常见问题

| 问题 | 解决方案 |
|------|---------|
| 内存不足 | 减少 TM 数量或调整 JVM 参数 |
| 端口冲突 | 修改 docker-compose.yml 端口映射 |
| 启动慢 | 增加 Docker 资源限制 |

---

## 5. 监控

### 5.1 Flink UI

- **地址**: `http://localhost:8081`
- **功能**: 作业状态、指标、日志

### 5.2 日志查看

```bash
# Flink 日志
docker-compose logs -f flink-jobmanager

# Fluss 日志
docker-compose logs -f fluss

# RocketMQ 日志
docker-compose logs -f rmq-broker
```

---

**文档结束**

*最小化部署设计，支持 Mac 本地开发。*
