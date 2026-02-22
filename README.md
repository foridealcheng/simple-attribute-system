# Simple Attribute System

> 基于 Apache Flink + Fluss + RocketMQ 的实时广告归因系统

**版本**: 1.0.0-SNAPSHOT  
**创建时间**: 2026-02-22

---

## 📖 项目简介

SimpleAttributeSystem 是一个实时广告归因系统，用于追踪广告点击与用户转化之间的关系，支持多种归因模型。

### 核心特性

- ✅ **实时处理**: 秒级处理广告点击和转化事件
- ✅ **多种归因模型**: Last Click、Linear、Time Decay、Position Based
- ✅ **高可靠性**: 多级重试机制，保证数据不丢失
- ✅ **可扩展**: 支持水平扩展，应对高并发场景

---

## 🏗️ 技术架构

```
数据源 → Apache Fluss → Flink 归因引擎 → Fluss MQ (结果)
                            ↓
                      RocketMQ (重试)
```

### 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 流处理引擎 | Apache Flink | 1.18.1 |
| 消息中间件 | Apache Fluss | 0.4.0 |
| 重试队列 | RocketMQ | 5.0.0 |
| 开发语言 | Java | 11+ |
| 构建工具 | Maven | 3.8+ |

---

## 📁 项目结构

```
SimpleAttributeSystem/
├── docs/                    # 设计文档
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/attribution/
│   │   │       ├── AttributionJob.java      # Flink 作业入口
│   │   │       ├── model/                   # 数据模型
│   │   │       ├── source/                  # 数据源
│   │   │       ├── function/                # Flink 函数
│   │   │       ├── sink/                    # 结果输出
│   │   │       └── retry/                   # 重试机制
│   │   └── resources/
│   │       ├── application.yaml             # 应用配置
│   │       └── log4j2.xml                   # 日志配置
│   └── test/
│       └── java/
│           └── com/attribution/
├── scripts/                   # 脚本文件
├── docker/                    # Docker 配置
├── pom.xml                    # Maven 配置
└── README.md                  # 项目说明
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

### 2. 本地开发

```bash
# 启动依赖服务
cd docker
docker-compose up -d

# 初始化 Topic
cd ../scripts
./init-fluss.sh
./init-rocketmq.sh

# 提交 Flink 作业
flink run -d -c com.attribution.AttributionJob \
  ../target/simple-attribute-system-1.0.0-SNAPSHOT.jar
```

### 3. 访问 Flink UI

```
http://localhost:8081
```

---

## 📚 设计文档

详细设计文档请参考 [docs/](docs/) 目录：

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统架构设计 |
| [DESIGN-01](docs/DESIGN-01-DATA-INGESTION.md) | 数据接入层 |
| [DESIGN-02](docs/DESIGN-02-ATTRIBUTION-ENGINE.md) | 归因引擎 |
| [DESIGN-03](docs/DESIGN-03-RETRY-MECHANISM.md) | 重试机制 |
| [DESIGN-04](docs/DESIGN-04-CONFIG-MANAGEMENT.md) | 配置管理 |
| [DESIGN-05](docs/DESIGN-05-DEPLOYMENT.md) | 部署设计 |
| [DESIGN-06](docs/DESIGN-06-TEST-PLAN.md) | 测试计划 |

---

## 🧪 运行测试

```bash
# 运行所有测试
mvn test

# 运行特定测试
mvn test -Dtest=AttributionEngineTest
```

---

## 📦 部署

### 开发环境

使用 Docker Compose：

```bash
cd docker
docker-compose up -d
```

### 生产环境

参考 [部署设计文档](docs/DESIGN-05-DEPLOYMENT.md)

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
