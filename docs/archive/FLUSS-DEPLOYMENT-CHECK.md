# Fluss 部署检查报告

**检查日期**: 2026-02-23 20:38  
**检查结果**: ⚠️ **Fluss 未部署** (使用本地缓存模式)

---

## 📊 检查摘要

| 检查项 | 状态 | 详情 |
|--------|------|------|
| **Fluss 容器** | ❌ 未运行 | 无 Fluss Docker 容器 |
| **Docker Compose** | ❌ 未配置 | docker-compose.yml 无 Fluss |
| **Fluss 配置** | ✅ 存在 | FlussSourceConfig.java |
| **FlussKVClient** | ⚠️ 本地缓存模式 | useFluss = false |
| **Flink 日志** | ⚠️ 无法访问 | 日志文件不存在 |

---

## 🔍 详细检查结果

### 1. Fluss 服务状态

```bash
docker ps | grep fluss
```

**结果**: ❌ 未发现运行中的 Fluss 容器

**说明**: Fluss 集群未部署

---

### 2. Docker Compose 配置

```bash
grep -i fluss docker/docker-compose.yml
```

**结果**: ❌ docker-compose.yml 中没有 Fluss 配置

**当前服务**:
- ✅ Zookeeper
- ✅ Kafka
- ✅ Flink JobManager
- ✅ Flink TaskManager
- ✅ Kafka UI
- ❌ Fluss (未配置)

---

### 3. Fluss 配置检查

**FlussSourceConfig.java**:
```java
// 默认配置
private String bootstrapServers = "localhost:9092";
private String database = "attribution_db";
private String table = "user_click_sessions";
```

**结果**: ✅ 配置文件存在

---

### 4. FlussKVClient 运行模式

**关键代码**:
```java
public class FlussKVClient {
    
    // 标记是否使用真正的 Fluss（当前为 false，使用本地缓存）
    private final boolean useFluss;
    
    public FlussKVClient(FlussSourceConfig config) {
        this.useFluss = false; // 暂时使用本地缓存
        
        // 初始化本地缓存
        this.localCache = Caffeine.newBuilder()
            .maximumSize(config.getCacheMaxSize())
            .expireAfterWrite(config.getCacheExpireMinutes(), TimeUnit.MINUTES)
            .build();
        
        log.warn("FlussKVClient running in LOCAL CACHE MODE (Fluss integration TODO)");
    }
}
```

**结果**: ⚠️ **本地缓存模式**

**当前行为**:
- ✅ 使用 Caffeine 本地缓存
- ✅ 缓存大小：10,000 entries
- ✅ 缓存过期：5 分钟
- ❌ 未连接真实 Fluss 集群
- ❌ 数据不持久化（重启丢失）
- ❌ 不支持跨实例共享

---

## 📝 问题分析

### 归因结果未生成的原因

**根本原因**: **与 Fluss 无关**

FlussKVClient 当前运行在**本地缓存模式**，归因结果未生成的原因可能是：

1. **Flink 作业未正确处理消息**
   - Checkpoint 问题
   - Consumer 未消费
   - 算子背压

2. **数据分区问题**
   - Click 和 Conversion 在不同 Partition
   - KeyBy 未使用 userId

3. **代码逻辑问题**
   - AttributionEngine 配置
   - 归因窗口设置
   - 序列化/反序列化

4. **Kafka Sink 问题**
   - Producer 未启动
   - Topic 未创建
   - 权限问题

---

## ✅ 当前架构

### 数据流

```
Kafka (click-events)
    ↓
Flink Source
    ↓
AttributionProcessFunction
    ├── FlussKVClient (本地缓存)
    │   └── Caffeine Cache (5min)
    └── AttributionEngine
        ↓
Kafka Sink (attribution-results-success)
```

### 状态管理

```
v1.0.0: Flink MapState (内存)
v2.0.0: FlussKVClient (本地缓存) ← 当前
未来：Fluss KV Store (外部化)
```

---

## 🎯 解决方案

### 方案 1: 排查归因逻辑（推荐）

**步骤**:
1. 检查 Flink 作业日志
2. 检查作业异常
3. 验证数据流
4. 测试 AttributionEngine

**命令**:
```bash
# 检查作业状态
curl http://localhost:8081/jobs/{jobId}/exceptions

# 检查背压
curl http://localhost:8081/jobs/{jobId}/backpressure

# 重启作业
./scripts/restart-local.sh
```

---

### 方案 2: 部署 Fluss 集群（未来）

**步骤**:
1. 添加 Fluss 到 docker-compose.yml
2. 创建 Fluss 数据库和表
3. 更新 FlussKVClient (useFluss = true)
4. 测试集成

**docker-compose.yml 添加**:
```yaml
services:
  fluss:
    image: apache/fluss:0.8.0-incubating
    container_name: fluss
    ports:
      - "9092:9092"
    environment:
      - FLUSS_MODE=standalone
```

**时间估算**: 2-3 小时

---

### 方案 3: 继续使用本地缓存（当前）

**优势**:
- ✅ 无需外部依赖
- ✅ 快速开发和测试
- ✅ 低延迟（亚毫秒）

**劣势**:
- ❌ 数据不持久化
- ❌ 不支持跨实例
- ❌ 重启丢失数据

**适用场景**:
- ✅ 本地开发
- ✅ 功能测试
- ❌ 生产环境

---

## 📋 检查清单

### Fluss 部署（未执行）

- [ ] 添加 Fluss 到 docker-compose
- [ ] 启动 Fluss 容器
- [ ] 创建数据库 attribution_db
- [ ] 创建表 user_click_sessions
- [ ] 配置 'log.ttl.ms' = '86400000'
- [ ] 更新 useFluss = true
- [ ] 测试连接

### 归因问题排查（待执行）

- [ ] 检查 Flink 日志
- [ ] 检查作业异常
- [ ] 验证 Checkpoint
- [ ] 测试 AttributionEngine
- [ ] 发送更多测试数据
- [ ] 验证 Kafka Sink

---

## 🎯 结论

### Fluss 状态

**Fluss 未部署，但这不是问题！**

当前代码设计为**本地缓存模式**， intentionally 不依赖 Fluss 集群：

```java
// FlussKVClient.java
private final boolean useFluss = false; // 暂时使用本地缓存

log.warn("FlussKVClient running in LOCAL CACHE MODE (Fluss integration TODO)");
```

### 归因问题

**归因结果未生成与 Fluss 无关**，需要排查：
1. Flink 作业处理逻辑
2. Kafka Sink 配置
3. 数据分区和 KeyBy
4. AttributionEngine 逻辑

---

## 📞 下一步

### 立即执行（推荐）

1. **检查 Flink 作业异常**
   ```bash
   curl http://localhost:8081/jobs/589a4358bbe3.../exceptions
   ```

2. **检查作业日志**
   ```bash
   docker exec flink-jobmanager ls /opt/flink/log/
   ```

3. **重启作业**
   ```bash
   ./scripts/restart-local.sh
   ```

### 未来计划

1. **部署 Fluss** - 生产环境需要
2. **启用 Fluss 模式** - useFluss = true
3. **性能测试** - 验证外部化状态

---

**检查人员**: SimpleAttributeSystem Team  
**检查时间**: 2026-02-23 20:38  
**Fluss 状态**: 未部署（使用本地缓存）  
**建议**: 排查 Flink 作业逻辑，与 Fluss 无关
