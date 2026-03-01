# v2.0.0 版本规划 - Fluss 优先版

**版本**: 2.0.0  
**规划日期**: 2026-02-23  
**策略**: Fluss 优先，集中突破

---

## 🎯 实施策略

**Phase 1 → Phase 2 (Fluss 全套) → Phase 3 → Phase 4**

优先完成所有 Fluss 相关代码，再进行 Kafka Sink 和 RocketMQ 重试。

---

## 📋 调整后的任务顺序

### 🚀 Sprint 1: Fluss 基础 (今天完成)

#### Task 1.1: 创建 v2.0.0 分支 (30min) ✅
```bash
git checkout -b feature/v2.0.0
git push -u origin feature/v2.0.0
```

**状态**: ⏳ 待执行

---

#### Task 1.2: 添加 Fluss 依赖 (1h) ✅

**pom.xml 更新**:
```xml
<!-- Fluss 核心依赖 -->
<dependency>
    <groupId>org.apache.fluss</groupId>
    <artifactId>fluss-client</artifactId>
    <version>0.4.0</version>
</dependency>

<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connector-fluss</artifactId>
    <version>1.17.1</version>
</dependency>
```

**状态**: ⏳ 待执行

---

#### Task 1.3: 添加 RocketMQ 依赖 (30min)

**pom.xml 更新**:
```xml
<!-- RocketMQ 依赖 -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>5.0.0</version>
</dependency>

<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-common</artifactId>
    <version>5.0.0</version>
</dependency>
```

**状态**: ⏳ 等待 Task 1.2 完成后执行

---

### 🎯 Sprint 2: Fluss Schema 设计 (2h)

#### Task 2.1: 设计 Fluss KV Schema ✅

**设计内容**:

1. **user-click-sessions 表** (KV Table)
```sql
CREATE TABLE user_click_sessions (
    user_id STRING PRIMARY KEY,
    clicks ARRAY<ROW<
        event_id STRING,
        timestamp BIGINT,
        campaign_id STRING,
        creative_id STRING,
        advertiser_id STRING,
        click_type STRING
    >>,
    session_start_time BIGINT,
    last_update_time BIGINT,
    click_count INT,
    ttl_timestamp BIGINT
) WITH (
    'connector' = 'fluss',
    'table-type' = 'PRIMARY KEY',
    'bucket.num' = '10'
);
```

2. **Schema 定义** (Java)
```java
public class FlussSchemas {
    public static final RowType USER_CLICK_SESSION_SCHEMA = DataTypes.ROW(
        DataTypes.FIELD("user_id", DataTypes.STRING()),
        DataTypes.FIELD("clicks", DataTypes.ARRAY(
            DataTypes.ROW(
                DataTypes.FIELD("event_id", DataTypes.STRING()),
                DataTypes.FIELD("timestamp", DataTypes.BIGINT()),
                DataTypes.FIELD("campaign_id", DataTypes.STRING()),
                DataTypes.FIELD("creative_id", DataTypes.STRING()),
                DataTypes.FIELD("advertiser_id", DataTypes.STRING()),
                DataTypes.FIELD("click_type", DataTypes.STRING())
            )
        )),
        DataTypes.FIELD("session_start_time", DataTypes.BIGINT()),
        DataTypes.FIELD("last_update_time", DataTypes.BIGINT()),
        DataTypes.FIELD("click_count", DataTypes.INT()),
        DataTypes.FIELD("ttl_timestamp", DataTypes.BIGINT())
    );
}
```

**交付物**:
- FlussSchemas.java (更新)
- DDL 脚本

**状态**: ⏳ 等待依赖完成后执行

---

### 🔧 Sprint 3: Fluss KV Client 实现 (4h)

#### Task 2.2: 实现 Fluss KV Client ✅

**接口设计**:
```java
public class FlussKVClient implements AutoCloseable {
    
    private final Connection connection;
    private final Table table;
    private final String databaseName;
    private final String tableName;
    
    public FlussKVClient(FlussSourceConfig config);
    
    /**
     * 获取用户点击会话
     */
    public FlussClickSession get(String userId);
    
    /**
     * 保存用户点击会话
     */
    public void put(String userId, FlussClickSession session);
    
    /**
     * 删除用户点击会话
     */
    public void delete(String userId);
    
    /**
     * 批量获取
     */
    public Map<String, FlussClickSession> batchGet(List<String> userIds);
    
    /**
     * 批量保存
     */
    public void batchPut(Map<String, FlussClickSession> sessions);
    
    /**
     * 清理过期数据
     */
    public int cleanupExpired(long currentTime);
    
    @Override
    public void close();
}
```

**实现要点**:
1. 连接管理（单例或连接池）
2. 异常处理（重试逻辑）
3. 性能优化（批量操作）
4. 资源清理

**交付物**:
- FlussKVClient.java
- 单元测试

**状态**: ⏳ 等待 Task 2.1 完成后执行

---

### ⭐ Sprint 4: 重构核心处理逻辑 (6h)

#### Task 2.3: 重构 AttributionProcessFunction ✅

**核心变更**:

**旧代码 (移除)**:
```java
public class AttributionProcessFunction 
    extends KeyedCoProcessFunction<String, ClickEvent, ConversionEvent, AttributionResult> {
    
    // ❌ 移除 Flink MapState
    private transient MapState<String, FlussClickSession> clickSessionState;
    private transient ValueState<String> lastProcessedEventId;
    
    @Override
    public void open(Configuration parameters) {
        // ❌ 移除 MapState 初始化
        MapStateDescriptor<String, FlussClickSession> descriptor = ...
        clickSessionState = getRuntimeContext().getMapState(descriptor);
    }
}
```

**新代码 (替换)**:
```java
public class AttributionProcessFunction 
    extends KeyedCoProcessFunction<String, ClickEvent, ConversionEvent, AttributionResult> {
    
    // ✅ 使用 Fluss KV Client
    private transient FlussKVClient flussKVClient;
    private transient ValueState<String> lastProcessedEventId;
    
    @Override
    public void open(Configuration parameters) {
        // ✅ 初始化 Fluss Client
        FlussSourceConfig config = getRuntimeContext()
            .getExecutionConfig()
            .getGlobalJobParameters()
            .get("fluss.config");
        
        this.flussKVClient = new FlussKVClient(config);
        
        // 保留去重状态
        ValueStateDescriptor<String> dedupDescriptor = ...
        lastProcessedEventId = getRuntimeContext().getState(dedupDescriptor);
    }
    
    @Override
    public void processElement1(ClickEvent click, Context ctx, Collector<AttributionResult> out) {
        // ✅ 从 Fluss 读取
        FlussClickSession session = flussKVClient.get(click.getUserId());
        
        if (session == null) {
            session = new FlussClickSession();
            session.setUserId(click.getUserId());
        }
        
        // 添加点击
        session.addClick(click, 50);
        
        // ✅ 写入 Fluss
        flussKVClient.put(click.getUserId(), session);
        
        // 去重
        lastProcessedEventId.update(click.getEventId());
    }
    
    @Override
    public void processElement2(ConversionEvent conversion, Context ctx, Collector<AttributionResult> out) {
        // ✅ 从 Fluss 读取
        FlussClickSession session = flussKVClient.get(conversion.getUserId());
        
        // 执行归因
        AttributionResult result = attributionEngine.attribute(conversion, session);
        
        // 输出结果
        out.collect(result);
        
        // 去重
        lastProcessedEventId.update(conversion.getEventId());
    }
}
```

**关键变化**:
- ❌ 移除 `MapState`
- ✅ 添加 `FlussKVClient`
- ✅ 状态读写改为 Fluss API
- ✅ 保留去重逻辑（Flink State）

**交付物**:
- AttributionProcessFunction.java (重构)
- 集成测试

**状态**: ⏳ 等待 Task 2.2 完成后执行

---

### 🧹 Sprint 5: TTL 和清理 (3h)

#### Task 2.4: 实现状态 TTL 和清理 ✅

**TTL 配置**:
```java
public class TTLConfig {
    // 默认 24 小时
    public static final long DEFAULT_TTL_HOURS = 24;
    
    // 清理间隔 60 分钟
    public static final long CLEANUP_INTERVAL_MINUTES = 60;
}
```

**实现逻辑**:
```java
public class FlussKVClient {
    
    public FlussClickSession get(String userId) {
        FlussClickSession session = table.get(userId);
        
        // 检查是否过期
        if (session != null && isExpired(session)) {
            delete(userId);
            return null;
        }
        
        return session;
    }
    
    public void put(String userId, FlussClickSession session) {
        // 设置 TTL 时间戳
        long ttlTimestamp = System.currentTimeMillis() + 
            (TTLConfig.DEFAULT_TTL_HOURS * 3600000L);
        session.setTtlTimestamp(ttlTimestamp);
        
        // 更新最后访问时间
        session.setLastUpdateTime(System.currentTimeMillis());
        
        table.put(userId, session);
    }
    
    public int cleanupExpired(long currentTime) {
        // 扫描并删除过期数据
        // 返回清理数量
        int count = 0;
        // ... 实现逻辑
        return count;
    }
    
    private boolean isExpired(FlussClickSession session) {
        return session.getTtlTimestamp() != null && 
               System.currentTimeMillis() > session.getTtlTimestamp();
    }
}
```

**定时清理任务**:
```java
public class TTLCleanupScheduler {
    
    private final FlussKVClient client;
    private final ScheduledExecutorService scheduler;
    
    public TTLCleanupScheduler(FlussKVClient client) {
        this.client = client;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 每 60 分钟执行一次清理
        scheduler.scheduleAtFixedRate(
            this::cleanup,
            60,
            60,
            TimeUnit.MINUTES
        );
    }
    
    private void cleanup() {
        int cleaned = client.cleanupExpired(System.currentTimeMillis());
        log.info("Cleaned {} expired sessions", cleaned);
    }
}
```

**交付物**:
- TTL 配置类
- 清理逻辑实现
- 定时任务

**状态**: ⏳ 等待 Task 2.3 完成后执行

---

### 🚀 Sprint 6: 性能优化 (4h)

#### Task 2.5: Fluss 状态性能优化 ✅

**优化方向**:

1. **批量操作**
```java
public class FlussKVClient {
    
    // 批量获取
    public Map<String, FlussClickSession> batchGet(List<String> userIds) {
        // 一次 RPC 获取多个
        return table.batchGet(userIds);
    }
    
    // 批量保存
    public void batchPut(Map<String, FlussClickSession> sessions) {
        // 一次 RPC 保存多个
        table.batchPut(sessions);
    }
}
```

2. **本地缓存**
```java
public class FlussKVClient {
    
    private final Cache<String, FlussClickSession> localCache;
    
    public FlussKVClient(FlussSourceConfig config) {
        this.localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }
    
    public FlussClickSession get(String userId) {
        // 先查本地缓存
        return localCache.get(userId, key -> table.get(key));
    }
    
    public void put(String userId, FlussClickSession session) {
        // 更新本地缓存
        localCache.put(userId, session);
        // 异步写入 Fluss
        table.put(userId, session);
    }
}
```

3. **异步写入**
```java
public class FlussKVClient {
    
    private final ExecutorService asyncExecutor;
    
    public FlussKVClient(FlussSourceConfig config) {
        this.asyncExecutor = Executors.newFixedThreadPool(4);
    }
    
    public void putAsync(String userId, FlussClickSession session) {
        asyncExecutor.submit(() -> {
            table.put(userId, session);
        });
    }
}
```

**性能目标**:
- P99 延迟 < 10ms
- 吞吐量 > 5000 ops/s
- 缓存命中率 > 80%

**交付物**:
- 批量操作实现
- 本地缓存实现
- 性能测试报告

**状态**: ⏳ 等待 Task 2.4 完成后执行

---

## 📊 新的实施顺序

### 阶段 1: 准备工作 (2h)
1. ✅ Task 1.1: 创建 v2.0.0 分支
2. ✅ Task 1.2: 添加 Fluss 依赖
3. ✅ Task 1.3: 添加 RocketMQ 依赖

### 阶段 2: Fluss Schema (2h)
4. ✅ Task 2.1: 设计 Fluss KV Schema

### 阶段 3: Fluss Client (4h)
5. ✅ Task 2.2: 实现 Fluss KV Client

### 阶段 4: 核心重构 (6h)
6. ✅ Task 2.3: 重构 AttributionProcessFunction ⭐

### 阶段 5: TTL 清理 (3h)
7. ✅ Task 2.4: 实现状态 TTL 和清理

### 阶段 6: 性能优化 (4h)
8. ✅ Task 2.5: Fluss 状态性能优化

### 阶段 7: Kafka Sink (后续)
9. ⏳ Phase 3: Kafka Sink 实现

### 阶段 8: RocketMQ Retry (后续)
10. ⏳ Phase 4: RocketMQ 重试机制

---

## 🎯 立即开始

**Task 1.1: 创建 v2.0.0 分支**

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem

# 1. 创建并切换分支
git checkout -b feature/v2.0.0

# 2. 更新 pom.xml 版本
# 1.0.0 → 2.0.0-SNAPSHOT

# 3. 提交
git add pom.xml
git commit -m "chore: bump version to 2.0.0-SNAPSHOT for v2.0.0 development"

# 4. 推送
git push -u origin feature/v2.0.0
```

---

## 📝 总结

**调整后的优先级**:

1. ⭐⭐⭐ **Fluss 全套** (Task 1.1 → 2.5)
   - 依赖配置
   - Schema 设计
   - Client 实现
   - 核心重构
   - TTL 清理
   - 性能优化

2. ⭐⭐ **Kafka Sink** (Phase 3)
   - Success/Failed 输出

3. ⭐ **RocketMQ Retry** (Phase 4)
   - 延迟重试机制

**优势**:
- ✅ 集中精力完成核心功能
- ✅ 状态外移是其他功能的基础
- ✅ 尽早验证 Fluss 集成
- ✅ 降低后续开发风险

---

**准备好开始 Task 1.1 了吗？** 🚀
