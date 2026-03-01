# Simple Attribute System

> 基于 Apache Flink 的实时广告归因系统 - 纯 Kafka 方案

**版本**: v2.1.0  
**创建时间**: 2026-02-22  
**最后更新**: 2026-02-24  
**状态**: ✅ 生产就绪

---

## 📖 项目简介

SimpleAttributeSystem 是一个实时广告归因系统，用于追踪广告点击与用户转化之间的关系。采用**多 KV 存储抽象架构**，支持 Redis、Apache Fluss 等多种存储后端，可灵活切换。

### 核心特性

- ✅ **实时处理**: 秒级处理广告点击和转化事件
- ✅ **多 KV 存储支持**: Redis (当前) / Fluss (未来) / 可扩展
- ✅ **LAST_CLICK 归因**: 支持多种归因模型（可扩展）
- ✅ **重试机制**: Kafka 重试队列，保证数据不丢失
- ✅ **分布式架构**: 3 个独立 Flink Jobs，支持水平扩展
- ✅ **统一技术栈**: 纯 Flink 实现，简化运维

---

## 🏗️ 技术架构

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│  Flink Cluster (3 Jobs)                                      │
│                                                              │
│  Click Writer Job    Attribution Engine Job    Retry Job    │
│  Click → KV Store    Conv + KV → Result      Retry Logic    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  KV Abstraction Layer (KVClient)                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Redis     │  │   Fluss     │  │   Other     │         │
│  │   (当前)    │  │   (未来)    │  │   (可扩展)   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

### 数据流

```
Click Events (Kafka)
    ↓
Click Writer Job (Flink)
    ↓
KV Store (Redis/Fluss) ← Key: click:{user_id}
    ↓
Attribution Engine Job (Flink) ← Conversion Events (Kafka)
    ↓
Kafka (attribution-results-success/failed/retry)
    ↓
Retry Consumer Job (Flink) ← Retry Messages
    ↓
Kafka (success/retry/dlq)
```

### 技术栈

| 组件 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **流处理** | Apache Flink | 1.17.1 | 实时计算引擎 |
| **KV 存储** | Redis / Apache Fluss | Latest | Click 会话存储 |
| **消息队列** | Kafka | Latest | 事件和重试队列 |
| **开发语言** | Java | 11+ | 开发语言 |
| **构建工具** | Maven | 3.8+ | 项目构建 |

---

## 📁 项目结构

```
SimpleAttributeSystem/
├── docs/                          # 设计文档
│   ├── ARCHITECTURE-v2.1.md       # ⭐ 核心架构文档
│   ├── CODE-CLEANUP-SUMMARY.md    # 代码清理总结
│   └── ...
├── src/main/java/com/attribution/
│   ├── client/                    # KV 客户端抽象层
│   │   ├── KVClient.java          # KV 接口
│   │   ├── KVClientFactory.java   # KV 工厂
│   │   ├── RedisKVClient.java     # Redis 实现
│   │   └── FlussKVClient.java     # Fluss 实现
│   ├── config/                    # 配置类
│   ├── engine/                    # 归因引擎
│   ├── flink/                     # Flink 处理函数
│   ├── job/                       # Flink Jobs
│   │   ├── ClickWriterJob.java
│   │   ├── AttributionEngineJob.java
│   │   ├── RetryConsumerJob.java
│   │   └── LocalTestJob.java
│   ├── model/                     # 数据模型
│   ├── sink/                      # 数据输出
│   └── validator/                 # 数据验证
├── config/                        # 配置文件
│   ├── click-writer.yaml
│   ├── attribution-engine.yaml
│   └── retry-consumer.yaml
├── scripts/                       # 脚本文件
├── docker/                        # Docker 配置
├── pom.xml                        # Maven 配置
└── README.md                      # 项目说明
```

---

## 🚀 快速开始

### 环境要求

- Java 11+
- Maven 3.8+
- Docker & Docker Compose（本地开发）

### 1. 编译项目

```bash
cd SimpleAttributeSystem
mvn clean package -DskipTests
```

### 2. 启动依赖服务

```bash
cd docker
docker-compose up -d

# 验证服务
docker ps  # 检查 Flink, Redis, Kafka 是否运行
```

### 3. 提交 Flink Jobs

```bash
# 1. Click Writer Job
flink run -c com.attribution.job.ClickWriterJob \
  -d -p 1 \
  ../target/simple-attribute-system-2.0.0-SNAPSHOT.jar

# 2. Attribution Engine Job
flink run -c com.attribution.job.AttributionEngineJob \
  -d -p 1 \
  ../target/simple-attribute-system-2.0.0-SNAPSHOT.jar

# 3. Retry Consumer Job
flink run -c com.attribution.job.RetryConsumerJob \
  -d -p 2 \
  ../target/simple-attribute-system-2.0.0-SNAPSHOT.jar
```

### 4. 访问 Flink UI

```
http://localhost:8081
```

查看 3 个运行中的 Jobs:
- Click Writer Job
- Attribution Engine Job
- Retry Consumer Job

---

## 📚 设计文档

### 📚 核心文档

| 文档 | 说明 |
|------|------|
| **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** ⭐ | 核心架构设计 (多 KV 存储支持) |
| **[CHANGELOG.md](docs/CHANGELOG.md)** | 版本变更历史 |
| **[CODE-CLEANUP-SUMMARY.md](docs/CODE-CLEANUP-SUMMARY.md)** | 代码结构说明 |

### 🛠️ 运维文档

| 文档 | 说明 |
|------|------|
| [DEPLOYMENT-CHECKLIST.md](docs/DEPLOYMENT-CHECKLIST.md) | 部署检查清单 |
| [LOCAL-ENV-CHECK.md](docs/LOCAL-ENV-CHECK.md) | 本地环境检查 |
| [DOCKER-INSTALL-GUIDE.md](docs/DOCKER-INSTALL-GUIDE.md) | Docker 安装指南 |
| [REDIS-KV-IMPLEMENTATION.md](docs/REDIS-KV-IMPLEMENTATION.md) | Redis KV 实现 |

### 📋 其他文档

| 文档 | 说明 |
|------|------|
| [DATA-VIEW-GUIDE.md](docs/DATA-VIEW-GUIDE.md) | 数据查看指南 |
| [CR-PROCESS.md](docs/CR-PROCESS.md) | Code Review 流程 |

### 📋 推荐阅读顺序

**新开发者**:
1. README.md (本文档)
2. [ARCHITECTURE.md](docs/ARCHITECTURE.md) ⭐
3. [CODE-CLEANUP-SUMMARY.md](docs/CODE-CLEANUP-SUMMARY.md)
4. [LOCAL-ENV-CHECK.md](docs/LOCAL-ENV-CHECK.md)

**运维人员**:
1. [ARCHITECTURE.md](docs/ARCHITECTURE.md)
2. [DEPLOYMENT-CHECKLIST.md](docs/DEPLOYMENT-CHECKLIST.md)
3. [REDIS-KV-IMPLEMENTATION.md](docs/REDIS-KV-IMPLEMENTATION.md)

---

## 🔧 配置管理

### KV 存储配置

```yaml
# config/click-writer.yaml
click:
  store:
    type: redis  # redis | fluss-cluster
  redis:
    host: redis
    port: 6379
    ttl-seconds: 3600
```

### 环境变量覆盖

```bash
export CLICK_STORE_TYPE=redis
export REDIS_HOST=redis
export KAFKA_BOOTSTRAP_SERVERS=kafka:29092
```

---

## 🧪 运行测试

```bash
# 运行所有测试
mvn test

# 本地测试 Job
flink run -c com.attribution.job.LocalTestJob \
  target/simple-attribute-system-2.0.0-SNAPSHOT.jar
```

---

## 📦 部署

### 开发环境

使用 Docker Compose:

```bash
cd docker
docker-compose up -d
```

### 生产环境

参考 [部署检查清单](docs/DEPLOYMENT-CHECKLIST.md)

**建议配置**:
- Flink: JobManager × 2 (HA), TaskManager × 4+
- Redis: Cluster 模式，3 Master + 3 Slave
- Kafka: Broker × 3+, 分区数 3-6

---

## 🎯 核心优势

### 1. 多 KV 存储支持

通过 `KVClient` 抽象层，支持多种 KV 存储后端：

- ✅ **Redis**: 当前默认，低延迟，高吞吐
- 🔄 **Apache Fluss**: 未来切换，云原生支持
- 🔌 **其他 KV**: 易于扩展

### 2. 纯 Kafka 重试机制

- **AttributionEngineJob**: 失败结果发送到 Kafka retry topic
- **RetryConsumerJob**: 从 Kafka 消费重试消息（Flink 实现）
- **优势**: 架构简洁，易于维护

### 2. 灵活切换

通过配置切换 KV 后端，无需修改代码：

```yaml
# Redis 模式
click.store.type: redis

# Fluss 模式
click.store.type: fluss-cluster
```

### 3. 统一技术栈

所有组件基于 Flink 实现：

- Click Writer Job (Flink)
- Attribution Engine Job (Flink)
- Retry Consumer Job (Flink)

**优势**:
- 统一监控
- 统一运维
- 统一容错

---

## 🤝 贡献

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

---

## 📄 许可证

本项目采用 Apache License 2.0 - 查看 [LICENSE](LICENSE) 文件

---

## 📞 联系方式

如有问题，请提交 Issue 或联系维护者。

---

**Happy Coding!** 🐱💻

---

*v2.1 - 多 KV 存储支持，灵活可扩展*
