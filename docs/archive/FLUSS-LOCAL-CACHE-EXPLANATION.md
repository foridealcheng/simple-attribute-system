# Fluss 本地缓存模式说明

**日期**: 2026-02-24  
**问题**: 本地缓存模式无法跨 JVM 进程共享  
**状态**: ⚠️ 重要说明

---

## ❌ 问题描述

### 当前架构问题

在 v2.1 架构中，我们有三个**独立的 JVM 进程**：

1. **Click Writer Job** - Flink Job (JVM 1)
2. **Attribution Engine Job** - Flink Job (JVM 2)
3. **Retry Consumer App** - Java App (JVM 3)

当 `FlussKVClient` 使用**本地缓存模式**时：

```java
// FlussKVClient 内部使用 Caffeine 本地缓存
private final Cache<String, FlussClickSession> localCache;
```

**问题**: 每个 JVM 进程有自己的本地缓存，**无法共享数据**！

### 数据流问题

```
Click Writer Job (JVM 1)
  ↓ 写入
Caffeine Cache 1 (内存)
  
Attribution Engine Job (JVM 2)
  ↓ 读取
Caffeine Cache 2 (内存) ← ❌ 空的！无法访问 Cache 1
  
Retry Consumer App (JVM 3)
  ↓ 读取
Caffeine Cache 3 (内存) ← ❌ 空的！无法访问 Cache 1
```

**结果**: 归因任务查询不到 Click 数据，归因失败！

---

## ✅ 解决方案

### 方案 1: LocalTestJob（单 JVM 模式）⭐ 推荐用于本地测试

**原理**: 将 Click Writer 和 Attribution Engine 合并到一个 Flink Job 中，共享同一个 `FlussKVClient` 实例。

**架构**:
```
┌─────────────────────────────────────────────────────────┐
│  LocalTestJob (单一 Flink Job, 单一 JVM)                 │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Shared FlussKVClient (Caffeine Cache)            │  │
│  │  - Click Writer 写入                               │  │
│  │  - Attribution Engine 读取                         │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**使用方式**:
```bash
# 提交 LocalTestJob
docker exec flink-jobmanager flink run \
  -c com.attribution.job.LocalTestJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar
```

**优点**:
- ✅ 不需要额外服务
- ✅ 快速验证功能
- ✅ 数据可以共享

**缺点**:
- ❌ 不是真正的分布式架构
- ❌ 只能用于本地测试

---

### 方案 2: 使用 Redis（推荐用于本地/测试环境）⭐⭐

**原理**: 使用 Redis 作为共享的 KV 存储，替代本地缓存。

**架构**:
```
┌─────────────────────────────────────────────────────────┐
│              Redis (独立服务)                            │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Key: user_id, Value: Click List (JSON)         │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
           ▲                    ▲                    ▲
           │                    │                    │
    ┌──────┴──────┐      ┌──────┴──────┐      ┌──────┴──────┐
    │   Click     │      │ Attribution │      │   Retry     │
    │   Writer    │      │   Engine    │      │  Consumer   │
    └─────────────┘      └─────────────┘      └─────────────┘
```

**实现步骤**:

1. **启动 Redis**:
```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

2. **创建 RedisKVClient** (TODO):
```java
public class RedisKVClient implements AutoCloseable {
    private final Jedis jedis;
    
    public RedisKVClient(String host, int port) {
        this.jedis = new Jedis(host, port);
    }
    
    public void put(String userId, FlussClickSession session) {
        String json = toJson(session);
        jedis.setex("click:" + userId, 3600, json); // 1 小时过期
    }
    
    public FlussClickSession get(String userId) {
        String json = jedis.get("click:" + userId);
        return fromJson(json);
    }
}
```

3. **修改配置**:
```yaml
# config/*.yaml
fluss:
  mode: redis  # 改为 Redis 模式
  redis:
    host: localhost
    port: 6379
```

**优点**:
- ✅ 真正的共享存储
- ✅ 配置简单
- ✅ 数据持久化

**缺点**:
- ❌ 需要启动 Redis
- ❌ 需要实现 RedisKVClient

---

### 方案 3: 使用 Fluss 集群（推荐用于生产环境）⭐⭐⭐

**原理**: 使用真正的 Apache Fluss 集群作为 KV 存储。

**架构**:
```
┌─────────────────────────────────────────────────────────┐
│           Apache Fluss Cluster                           │
│  ┌─────────────────────────────────────────────────┐   │
│  │  KV Store: attribution-clicks                    │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

**启动 Fluss** (TODO):
```bash
docker run -d --name fluss-server \
  -p 9110:9110 \
  apache/fluss:0.8.0
```

**修改配置**:
```yaml
# config/*.yaml
fluss:
  mode: fluss  # Fluss 集群模式
  bootstrap.servers: fluss:9110
  kv.table.name: attribution-clicks
```

**优点**:
- ✅ 真正的分布式 KV 存储
- ✅ 符合 v2.1 架构设计
- ✅ 生产环境可用

**缺点**:
- ❌ 配置复杂
- ❌ 需要额外资源

---

## 🎯 当前建议

### 对于本地测试：使用 LocalTestJob（方案 1）

**原因**:
1. ✅ 不需要额外服务
2. ✅ 快速验证功能
3. ✅ 代码已经实现

**启动命令**:
```bash
cd SimpleAttributeSystem

# 1. 编译
mvn clean package -DskipTests

# 2. 复制 JAR
docker cp target/simple-attribute-system-2.1.0.jar flink-jobmanager:/opt/

# 3. 提交 LocalTestJob
docker exec flink-jobmanager flink run \
  -c com.attribution.job.LocalTestJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

# 4. 查看作业状态
docker exec flink-jobmanager flink list --running

# 5. 发送测试数据
docker exec kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic click-events

# 发送 Click
{"eventId":"click-001","userId":"user-001","timestamp":1708512000000,"advertiserId":"adv-001"}

# 发送 Conversion
docker exec kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic conversion-events

{"eventId":"conv-001","userId":"user-001","timestamp":1708515600000,"advertiserId":"adv-001","conversionType":"PURCHASE","conversionValue":299.99}
```

---

### 对于生产环境：使用 Fluss 集群（方案 3）

**原因**:
1. ✅ 符合 v2.1 架构设计
2. ✅ 真正的分布式
3. ✅ 数据持久化

**TODO**:
- [ ] 实现 Fluss 集群部署
- [ ] 创建 KV Table
- [ ] 性能测试

---

## 📝 总结

| 方案 | 适用场景 | 共享 | 持久化 | 复杂度 |
|------|---------|------|--------|--------|
| **LocalTestJob** | 本地测试 | ✅ | ❌ | 低 |
| **Redis** | 测试/开发 | ✅ | ✅ | 中 |
| **Fluss 集群** | 生产环境 | ✅ | ✅ | 高 |

**当前实现**: ✅ LocalTestJob  
**下一步**: 测试 LocalTestJob 功能

---

*重要：生产环境必须使用 Fluss 集群或 Redis，不能使用本地缓存模式！*
