# Maven 依赖说明

> T004 任务交付物 - pom.xml 配置说明

**更新时间**: 2026-02-23  
**状态**: ✅ 完成

---

## 📦 核心依赖

### Apache Flink (1.18.1)
- `flink-streaming-java` - Flink 流处理核心
- `flink-clients` - Flink 客户端（提交作业用）
- `flink-connector-kafka` - Kafka 连接器
- `flink-json` - JSON 格式支持
- `flink-avro` - Avro 格式支持
- `flink-connector-fluss` - Fluss 连接器

### Apache Fluss (0.4.0)
- `fluss-client` - Fluss 客户端 SDK

### RocketMQ (5.0.0)
- `rocketmq-client` - RocketMQ 客户端
- `rocketmq-common` - RocketMQ 公共组件

### 数据格式支持
- **Jackson** (2.15.3) - JSON 序列化/反序列化
- **Protobuf** (3.25.1) - Protocol Buffers 支持
- **Avro** (1.11.3) - Avro 序列化支持

### 工具库
- **Lombok** (1.18.30) - 简化 Java 代码
- **Commons Lang3** (3.14.0) - 字符串、数组等工具
- **Commons IO** (2.15.1) - IO 操作工具

### 日志
- **SLF4J** (2.0.9) - 日志接口
- **Log4j2** (2.20.0) - 日志实现

### 测试
- **JUnit 5** (5.9.3) - 单元测试框架
- **flink-test-utils** - Flink 测试工具

---

## 🔧 构建插件

### maven-compiler-plugin (3.11.0)
- Java 11 编译
- Lombok 注解处理

### protobuf-maven-plugin (0.6.1)
- 自动编译 `.proto` 文件
- 输出到 `target/generated-sources`

### avro-maven-plugin (1.11.3)
- 自动编译 `.avsc` 文件
- 输出到 `target/generated-sources`

### maven-shade-plugin (3.5.1)
- 打包可执行 JAR
- 主类：`com.attribution.AttributionJob`
- 排除签名文件，避免冲突

### os-maven-plugin (1.7.1)
- 检测操作系统分类器
- 用于 Protobuf 原生库

---

## 📁 目录结构

```
SimpleAttributeSystem/
├── src/
│   ├── main/
│   │   ├── java/          # Java 源代码
│   │   ├── proto/         # Protobuf 定义文件 (.proto)
│   │   ├── avro/          # Avro 模式文件 (.avsc)
│   │   └── resources/     # 配置文件
│   └── test/
│       └── java/          # 测试代码
├── target/
│   └── generated-sources/ # 自动生成的代码
│       ├── protobuf/      # Protobuf 生成的 Java 类
│       └── avro/          # Avro 生成的 Java 类
└── pom.xml
```

---

## 🚀 常用命令

```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包（跳过测试）
mvn clean package -DskipTests

# 打包（包含测试）
mvn clean package

# 生产环境打包
mvn clean package -Pprod -DskipTests

# 查看依赖树
mvn dependency:tree

# 分析依赖
mvn dependency:analyze
```

---

## ⚠️ 注意事项

### 依赖作用域
- `provided` - Flink 核心依赖（运行时由 Flink 提供）
- `compile` - 默认，打包时包含
- `test` - 仅测试时使用

### 版本兼容性
- Flink 1.18.1 + Fluss 0.4.0 ✅
- Flink 1.18.1 + Kafka Connector 3.0.1 ✅
- Java 11+ ✅

### 打包大小
- 开发环境：~50MB（包含所有依赖）
- 生产环境：~30MB（最小化后）

---

## 📋 依赖清单

| 组件 | 版本 | 用途 | 作用域 |
|------|------|------|--------|
| Flink | 1.18.1 | 流处理引擎 | provided |
| Fluss | 0.4.0 | 数据存储/消息 | compile |
| RocketMQ | 5.0.0 | 重试队列 | compile |
| Kafka | 3.6.1 | 数据源 | compile |
| Jackson | 2.15.3 | JSON 处理 | compile |
| Protobuf | 3.25.1 | PB 序列化 | compile |
| Avro | 1.11.3 | Avro 序列化 | compile |
| Lombok | 1.18.30 | 代码简化 | provided |
| Log4j2 | 2.20.0 | 日志 | compile |

---

**任务状态**: ✅ T004 完成
