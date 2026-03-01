# Fluss 部署说明

**状态**: ⚠️ Fluss 还在 Apache 孵化阶段，下载和部署可能比较复杂

---

## 当前问题

Fluss 0.8.0 的官方下载链接可能不可用。Fluss 是一个较新的项目，还在快速迭代中。

---

## 替代方案

### 方案 1: 使用本地 Maven 仓库中的 Fluss（推荐）⭐

由于您的项目已经配置了 Fluss 依赖，说明 Maven 仓库中有 Fluss：

```xml
<!-- pom.xml -->
<fluss.version>0.8.0-incubating</fluss.version>
```

**我们可以：**

1. **从 Maven 仓库下载 Fluss JAR**
2. **手动启动 Fluss Coordinator 和 Server**
3. **或者使用 Fluss 的嵌入式模式进行测试**

---

### 方案 2: 使用 Redis 作为临时替代（立即可用）⭐⭐

在 Fluss 部署好之前，先用 Redis 作为共享 KV 存储：

```bash
# 1. 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 2. 创建 RedisKVClient
# (我可以帮您实现，约 30 分钟)

# 3. 修改配置使用 Redis
# config/*.yaml:
#   fluss.mode: redis
#   redis.host: localhost
#   redis.port: 6379

# 4. 使用标准架构测试
# Click Writer → Redis
# Attribution Engine ← Redis
# Retry Consumer ← Redis
```

**优点**:
- ✅ 立即可用（5 分钟）
- ✅ 真正的共享存储
- ✅ 架构与 Fluss 一致

**缺点**:
- ❌ 不是 Fluss（但架构相同）

---

### 方案 3: 继续使用 LocalTestJob（当前可用）

先用 LocalTestJob 验证基本功能，同时部署 Fluss：

```bash
# 当前可以立即测试
docker exec flink-jobmanager flink run \
  -c com.attribution.job.LocalTestJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar
```

---

## 🎯 我的建议

**立即执行（按顺序）：**

1. **先用 Redis 方案**（5 分钟准备，30 分钟实现）
   - 启动 Redis
   - 实现 RedisKVClient
   - 使用标准架构测试

2. **同时部署 Fluss**（可能需要几小时到几天）
   - 联系 Fluss 社区获取正确的部署方式
   - 或等待 Fluss 发布稳定版本

3. **Fluss 部署好后切换**
   - 配置切换到 Fluss
   - 重新测试

---

## 📝 Redis 方案实现计划

### Step 1: 启动 Redis（1 分钟）

```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

### Step 2: 实现 RedisKVClient（30 分钟）

创建 `src/main/java/com/attribution/client/RedisKVClient.java`:

```java
public class RedisKVClient implements AutoCloseable {
    private final Jedis jedis;
    
    public RedisKVClient(String host, int port) {
        this.jedis = new Jedis(host, port);
    }
    
    public void put(String userId, FlussClickSession session) {
        String json = toJson(session);
        jedis.setex("click:" + userId, 3600, json); // 1 小时
    }
    
    public FlussClickSession get(String userId) {
        String json = jedis.get("click:" + userId);
        return fromJson(json);
    }
}
```

### Step 3: 修改配置（5 分钟）

```yaml
# config/*.yaml
fluss:
  mode: redis
  redis:
    host: localhost
    port: 6379
```

### Step 4: 使用标准架构测试（10 分钟）

```bash
# 提交 Click Writer Job
docker exec flink-jobmanager flink run \
  -c com.attribution.job.ClickWriterJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

# 提交 Attribution Engine Job
docker exec flink-jobmanager flink run \
  -c com.attribution.job.AttributionEngineJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

# 启动 Retry Consumer
java -jar target/simple-attribute-system-2.1.0.jar \
  com.attribution.consumer.RocketMQRetryConsumerApplication
```

---

## ⚠️ 总结

**当前情况**:
- ❌ Fluss 部署复杂，下载链接不可用
- ✅ Redis 方案立即可用
- ✅ LocalTestJob 可以验证基本功能

**建议**:
1. **立即**: 使用 Redis 方案（标准架构）
2. **同时**: 尝试部署 Fluss
3. **未来**: Fluss 部署好后切换

**您想**:
1. 实现 Redis 方案（我帮您写代码，约 30 分钟）
2. 继续尝试部署 Fluss
3. 先用 LocalTestJob 测试

请告诉我您的选择！
