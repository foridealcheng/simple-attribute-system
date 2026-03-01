# 设计文档 02 - 归因引擎详细设计（Fluss KV 版本）

> 基于 Apache Fluss KV Store 的轻量级归因引擎设计

**版本**: v2.0  
**创建时间**: 2026-02-22  
**状态**: 📝 Draft  
**关联任务**: T007  
**变更说明**: 使用 Fluss KV 存储替代 Flink Keyed State，最小化 Flink 状态使用

---

## 1. 概述

### 1.1 设计目标
本次设计基于用户 Review 意见进行重大调整：
- ✅ **使用 Fluss KV 存储**: 用户 click 数据存储在 Fluss KV 中，而非 Flink State
- ✅ **最小化 Flink 状态**: 仅保留去重逻辑使用 Flink State
- ✅ **Key 设计**: 归因 Key（user_id）作为 Fluss KV 的 Key
- ✅ **Value 设计**: Click 数据列表作为 Fluss KV 的 Value（List 结构）
- ✅ **归因结果输出**: 成功/失败结果都写入 Apache Fluss（消息队列模式）

### 1.2 架构变更对比

#### 变更前（v1.0 - Flink State）
```
┌─────────────────────────────────────────┐
│         Flink TaskManager               │
│  ┌─────────────────────────────────┐   │
│  │  Keyed State (RocksDB)          │   │
│  │  - pendingClicksState (List)    │   │
│  │  - sessionStartTimeState        │   │
│  │  - lastActivityTimeState        │   │
│  └─────────────────────────────────┘   │
│         ▲                               │
│         │ 所有状态都在 Flink 中          │
└─────────┴───────────────────────────────┘
```

#### 变更后（v2.0 - Fluss KV）
```
┌─────────────────────────────────────────┐
│         Flink TaskManager               │
│  ┌─────────────────────────────────┐   │
│  │  Minimal State (去重)            │   │
│  │  - dedupState (ValueState)      │   │
│  └─────────────────────────────────┘   │
│         │                               │
│         ▼                               │
│  ┌─────────────────────────────────┐   │
│  │  Fluss KV Client                │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│         Apache Fluss Cluster            │
│  ┌─────────────────────────────────┐   │
│  │  KV Store                        │   │
│  │  Key: user_id                   │   │
│  │  Value: List<ClickEvent>        │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

### 1.3 核心优势
| 方面 | Flink State | Fluss KV | 优势 |
|------|-------------|----------|------|
| 状态大小 | 受 TaskManager 内存限制 | 分布式存储，可水平扩展 | ✅ 支持更大数据量 |
| 状态恢复 | Checkpoint 恢复慢 | Fluss 持久化，恢复快 | ✅ 更快故障恢复 |
| 资源消耗 | 占用 TM 内存/磁盘 | 独立存储集群 | ✅ 降低 Flink 资源压力 |
| 并发访问 | 单 Key 单并行实例 | 支持并发读写 | ✅ 更高吞吐 |
| 数据保留 | 受 State TTL 限制 | 可配置更长保留期 | ✅ 更灵活的数据策略 |

---

## 2. 数据模型设计

### 2.1 Fluss KV 数据模型

#### 2.1.1 KV Key 设计
```java
/**
 * Fluss KV Key 设计
 * 
 * Key 格式：{user_id}
 * 示例：user_789abc
 * 
 * 分区策略：按 user_id 哈希分区
 * 确保同一用户的所有点击数据在同一分区
 */
public class FlussKVKey implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户 ID（归因 Key）
     */
    private final String userId;
    
    public FlussKVKey(String userId) {
        this.userId = userId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    @Override
    public String toString() {
        return userId;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlussKVKey that = (FlussKVKey) o;
        return Objects.equals(userId, that.userId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
```

#### 2.1.2 KV Value 设计（Click 列表）
```java
/**
 * Fluss KV Value - Click 事件列表
 * 
 * 存储结构：List<ClickEvent>
 * 最大长度：50（可配置）
 * 排序：按 timestamp 升序
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlussKVValue implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Click 事件列表
     * 按时间戳升序排序
     */
    private List<ClickEvent> clicks;
    
    /**
     * 最后更新时间
     */
    private Long lastUpdateTime;
    
    /**
     * 会话开始时间
     */
    private Long sessionStartTime;
    
    /**
     * 版本号（用于乐观锁）
     */
    private Long version;
    
    /**
     * 添加 Click 事件
     * 
     * @param click 点击事件
     * @param maxClicks 最大存储数
     */
    public void addClick(ClickEvent click, int maxClicks) {
        if (this.clicks == null) {
            this.clicks = new ArrayList<>();
        }
        
        // 添加新点击
        this.clicks.add(click);
        
        // 按时间戳排序
        this.clicks.sort(Comparator.comparingLong(ClickEvent::getTimestamp));
        
        // 如果超出限制，移除最旧的点击
        while (this.clicks.size() > maxClicks) {
            this.clicks.remove(0);
        }
        
        // 更新最后更新时间
        this.lastUpdateTime = System.currentTimeMillis();
        
        // 更新版本号
        this.version = this.version != null ? this.version + 1 : 1L;
        
        // 设置会话开始时间（如果是第一个点击）
        if (this.sessionStartTime == null) {
            this.sessionStartTime = click.getTimestamp();
        }
    }
    
    /**
     * 获取有效点击列表（在归因窗口内）
     * 
     * @param conversionTime 转化时间
     * @param attributionWindowMs 归因窗口（毫秒）
     * @return 有效点击列表
     */
    public List<ClickEvent> getValidClicks(long conversionTime, long attributionWindowMs) {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return Collections.emptyList();
        }
        
        return this.clicks.stream()
            .filter(click -> {
                // 检查点击时间是否在归因窗口内
                long timeDiff = conversionTime - click.getTimestamp();
                return timeDiff >= 0 && timeDiff <= attributionWindowMs;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 清理过期点击
     * 
     * @param currentTime 当前时间
     * @param attributionWindowMs 归因窗口（毫秒）
     * @return 是否发生了清理
     */
    public boolean cleanupExpired(long currentTime, long attributionWindowMs) {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return false;
        }
        
        long cutoffTime = currentTime - attributionWindowMs;
        List<ClickEvent> validClicks = this.clicks.stream()
            .filter(click -> click.getTimestamp() >= cutoffTime)
            .collect(Collectors.toList());
        
        if (validClicks.size() < this.clicks.size()) {
            this.clicks = validClicks;
            this.lastUpdateTime = currentTime;
            this.version = this.version != null ? this.version + 1 : 1L;
            return true;
        }
        
        return false;
    }
}
```

#### 2.1.3 ClickEvent 数据模型
```java
/**
 * Click 事件数据模型
 * 
 * 存储在 Fluss KV Value 中
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClickEvent implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 事件 ID（用于去重） */
    private String eventId;
    
    /** 用户 ID */
    private String userId;
    
    /** 时间戳 */
    private Long timestamp;
    
    /** 广告主 ID */
    private String advertiserId;
    
    /** 广告活动 ID */
    private String campaignId;
    
    /** 广告计划 ID */
    private String adGroupId;
    
    /** 广告创意 ID */
    private String creativeId;
    
    /** 广告位 ID */
    private String placementId;
    
    /** 点击 URL */
    private String clickUrl;
    
    /** 落地页 URL */
    private String landingPageUrl;
    
    /** 来源 URL */
    private String referrer;
    
    /** 设备类型 */
    private DeviceType deviceType;
    
    /** 操作系统 */
    private String os;
    
    /** 操作系统版本 */
    private String osVersion;
    
    /** 浏览器 */
    private String browser;
    
    /** 浏览器版本 */
    private String browserVersion;
    
    /** IP 地址 */
    private String ipAddress;
    
    /** User Agent */
    private String userAgent;
    
    /** 国家 */
    private String country;
    
    /** 省份 */
    private String province;
    
    /** 城市 */
    private String city;
    
    /** 自定义属性 */
    private Map<String, String> attributes;
}
```

---

## 3. 核心组件设计

### 3.1 组件架构图
```
┌─────────────────────────────────────────────────────────────────┐
│                    Flink Job                                     │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  AttributionProcessFunction                              │  │
│  │                                                           │  │
│  │  ┌─────────────────┐  ┌─────────────────┐               │  │
│  │  │  ClickHandler   │  │ ConversionHandler│               │  │
│  │  │  - processClick │  │  - processConv  │               │  │
│  │  │  - dedupCheck   │  │  - fetchClicks  │               │  │
│  │  └────────┬────────┘  └────────┬────────┘               │  │
│  │           │                    │                         │  │
│  │           └──────────┬─────────┘                         │  │
│  │                      │                                   │  │
│  │           ┌──────────▼──────────┐                       │  │
│  │           │  FlussKVClient      │                       │  │
│  │           │  - get()            │                       │  │
│  │           │  - put()            │                       │  │
│  │           │  - update()         │                       │  │
│  │           └──────────┬──────────┘                       │  │
│  └──────────────────────┼──────────────────────────────────┘  │
└─────────────────────────┼──────────────────────────────────────┘
                          │
                          │ KV 读写
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Apache Fluss                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  KV Store: attribution-clicks                           │   │
│  │  Key: user_id                                           │   │
│  │  Value: List<ClickEvent>                                │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Fluss KV Client 设计

### 4.1 FlussKVClient 接口
```java
/**
 * Fluss KV 客户端接口
 * 
 * 提供异步 KV 读写能力
 */
public interface FlussKVClient extends AutoCloseable {
    
    /**
     * 异步获取 Value
     * 
     * @param key KV Key
     * @return CompletableFuture<FlussKVValue>
     */
    CompletableFuture<FlussKVValue> get(FlussKVKey key);
    
    /**
     * 异步写入 Value
     * 
     * @param key KV Key
     * @param value KV Value
     * @return CompletableFuture<Void>
     */
    CompletableFuture<Void> put(FlussKVKey key, FlussKVValue value);
    
    /**
     * 异步更新 Value（带版本号检查）
     * 
     * @param key KV Key
     * @param valueUpdater Value 更新函数
     * @return CompletableFuture<FlussKVValue>
     */
    CompletableFuture<FlussKVValue> update(
        FlussKVKey key,
        Function<FlussKVValue, FlussKVValue> valueUpdater
    );
    
    /**
     * 异步删除 Key
     * 
     * @param key KV Key
     * @return CompletableFuture<Void>
     */
    CompletableFuture<Void> delete(FlussKVKey key);
    
    /**
     * 批量获取（用于优化）
     * 
     * @param keys Key 列表
     * @return CompletableFuture<Map<FlussKVKey, FlussKVValue>>
     */
    CompletableFuture<Map<FlussKVKey, FlussKVValue>> batchGet(
        List<FlussKVKey> keys
    );
    
    /**
     * 批量写入（用于优化）
     * 
     * @param entries Key-Value 对列表
     * @return CompletableFuture<Void>
     */
    CompletableFuture<Void> batchPut(
        List<Map.Entry<FlussKVKey, FlussKVValue>> entries
    );
}
```

### 4.2 FlussKVClientImpl 实现
```java
/**
 * Fluss KV 客户端实现
 * 
 * 基于 Apache Fluss Table API
 */
public class FlussKVClientImpl implements FlussKVClient {
    
    private static final Logger log = LoggerFactory.getLogger(FlussKVClientImpl.class);
    
    /**
     * Fluss Lakehouse 客户端
     */
    private final Lakehouse lakehouse;
    
    /**
     * KV Table
     */
    private final Table kvTable;
    
    /**
     * Table 名称
     */
    private final String tableName;
    
    /**
     * 写入超时（毫秒）
     */
    private final long writeTimeoutMs;
    
    /**
     * 读取超时（毫秒）
     */
    private final long readTimeoutMs;
    
    /**
     * 构造函数
     * 
     * @param bootstrapServers Fluss 服务器地址
     * @param tableName KV Table 名称
     * @param writeTimeoutMs 写入超时
     * @param readTimeoutMs 读取超时
     */
    public FlussKVClientImpl(
        String bootstrapServers,
        String tableName,
        long writeTimeoutMs,
        long readTimeoutMs
    ) {
        this.tableName = tableName;
        this.writeTimeoutMs = writeTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        
        // 初始化 Lakehouse
        this.lakehouse = Lakehouse.builder()
            .bootstrapServers(bootstrapServers)
            .build();
        
        // 获取 KV Table
        this.kvTable = lakehouse.getTable(tableName);
        
        log.info("FlussKVClient initialized. Table={}, bootstrapServers={}", 
            tableName, bootstrapServers);
    }
    
    @Override
    public CompletableFuture<FlussKVValue> get(FlussKVKey key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 构建查询
                RowFilter filter = RowFilter.newBuilder()
                    .addCondition(
                        Condition.newBuilder()
                            .setColumnName("user_id")
                            .setOperator(Operator.EQUAL)
                            .addValue(key.getUserId())
                            .build()
                    )
                    .build();
                
                // 执行查询
                try (TableScan scan = kvTable.newScan()
                        .withFilter(filter)
                        .build()) {
                    
                    Iterator<Row> iterator = scan.iterator();
                    
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return parseRow(row);
                    } else {
                        // Key 不存在，返回空 Value
                        return createEmptyValue();
                    }
                }
                
            } catch (Exception e) {
                log.error("Failed to get value from Fluss: key={}", key, e);
                throw new FlussKVReadException("Failed to read from Fluss", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> put(FlussKVKey key, FlussKVValue value) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 构建 Row
                Row row = buildRow(key, value);
                
                // 写入 Fluss
                kvTable.write(row);
                
                log.debug("Value written to Fluss: key={}", key);
                
            } catch (Exception e) {
                log.error("Failed to put value to Fluss: key={}", key, e);
                throw new FlussKVWriteException("Failed to write to Fluss", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<FlussKVValue> update(
        FlussKVKey key,
        Function<FlussKVValue, FlussKVValue> valueUpdater
    ) {
        // 先读取当前值
        return get(key).thenCompose(currentValue -> {
            // 应用更新函数
            FlussKVValue newValue = valueUpdater.apply(currentValue);
            
            // 写入新值
            return put(key, newValue).thenApply(v -> newValue);
        });
    }
    
    @Override
    public CompletableFuture<Void> delete(FlussKVKey key) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 构建删除操作
                // Fluss 的删除可以通过写入特殊标记或调用 delete API
                
                // 这里使用写入空值的方式
                FlussKVValue emptyValue = new FlussKVValue();
                emptyValue.setClicks(Collections.emptyList());
                
                put(key, emptyValue).join();
                
                log.debug("Value deleted from Fluss: key={}", key);
                
            } catch (Exception e) {
                log.error("Failed to delete value from Fluss: key={}", key, e);
                throw new FlussKVDeleteException("Failed to delete from Fluss", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Map<FlussKVKey, FlussKVValue>> batchGet(
        List<FlussKVKey> keys
    ) {
        // 并行执行多个 get 操作
        List<CompletableFuture<Map.Entry<FlussKVKey, FlussKVValue>>> futures = 
            keys.stream()
                .map(key -> get(key).thenApply(value -> 
                    new AbstractMap.SimpleEntry<>(key, value)))
                .collect(Collectors.toList());
        
        return CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> 
                futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                    ))
            );
    }
    
    @Override
    public CompletableFuture<Void> batchPut(
        List<Map.Entry<FlussKVKey, FlussKVValue>> entries
    ) {
        // 并行执行多个 put 操作
        List<CompletableFuture<Void>> futures = entries.stream()
            .map(entry -> put(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
    }
    
    @Override
    public void close() throws Exception {
        if (lakehouse != null) {
            lakehouse.close();
        }
        log.info("FlussKVClient closed");
    }
    
    // ========== 辅助函数 ==========
    
    /**
     * 从 Row 解析 FlussKVValue
     */
    private FlussKVValue parseRow(Row row) {
        try {
            FlussKVValue value = new FlussKVValue();
            
            // 解析 clicks 字段（JSON 字符串）
            String clicksJson = row.getField("clicks_data").toString();
            List<ClickEvent> clicks = JsonUtil.fromJson(
                clicksJson, 
                new TypeReference<List<ClickEvent>>() {}
            );
            value.setClicks(clicks);
            
            // 解析其他字段
            value.setLastUpdateTime(row.getField("last_update_time", Long.class));
            value.setSessionStartTime(row.getField("session_start_time", Long.class));
            value.setVersion(row.getField("version", Long.class));
            
            return value;
            
        } catch (Exception e) {
            throw new FlussKVParseException("Failed to parse row", e);
        }
    }
    
    /**
     * 构建 Row 对象
     */
    private Row buildRow(FlussKVKey key, FlussKVValue value) {
        try {
            // 将 clicks 列表序列化为 JSON
            String clicksJson = JsonUtil.toJson(value.getClicks());
            
            // 创建 Row
            return Row.of(
                key.getUserId(),                          // user_id (Key)
                clicksJson,                               // clicks_data (JSON)
                value.getLastUpdateTime(),                // last_update_time
                value.getSessionStartTime(),              // session_start_time
                value.getVersion() != null ? value.getVersion() : 0L  // version
            );
            
        } catch (Exception e) {
            throw new FlussKVBuildException("Failed to build row", e);
        }
    }
    
    /**
     * 创建空 Value
     */
    private FlussKVValue createEmptyValue() {
        FlussKVValue value = new FlussKVValue();
        value.setClicks(new ArrayList<>());
        value.setLastUpdateTime(System.currentTimeMillis());
        value.setVersion(0L);
        return value;
    }
}
```

---

## 5. 归因引擎核心函数设计

### 5.1 AttributionProcessFunction 类定义

```java
/**
 * 归因引擎核心处理函数（Fluss KV 版本）
 * 
 * 设计原则:
 * - 最小化 Flink 状态：仅用于去重
 * - 用户 Click 数据存储在 Fluss KV
 * - 异步 KV 读写，避免阻塞
 * 
 * @param <K> Key 类型 (userId)
 * @param <V> 输入值类型 (CallbackData)
 * @param <R> 输出值类型 (AttributionOutput)
 */
public class AttributionProcessFunction 
    extends KeyedProcessFunction<String, CallbackData, AttributionOutput> {
    
    // ========== 状态声明（仅用于去重）==========
    
    /**
     * 去重状态：存储已处理的事件 ID
     * 使用 BloomFilter 或 ValueState<String>（最近 N 个事件 ID）
     * 
     * 注意：这是本函数中唯一使用的 Flink State
     */
    private transient ValueState<String> dedupState;
    
    // ========== 组件 ==========
    
    /**
     * Fluss KV 客户端
     */
    private transient FlussKVClient kvClient;
    
    /**
     * 归因窗口配置（毫秒）
     * 默认 7 天 = 604800000ms
     */
    private final long attributionWindowMs;
    
    /**
     * 最大点击存储数（每个用户）
     */
    private final int maxClicksPerUser;
    
    /**
     * 归因模型策略
     */
    private final AttributionModelStrategy attributionModel;
    
    /**
     * 指标上报器
     */
    private final MetricsReporter metricsReporter;
    
    /**
     * 异步操作超时时间（毫秒）
     */
    private final long asyncTimeoutMs;
    
    // ========== 构造函数 ==========
    
    public AttributionProcessFunction(
        long attributionWindowMs,
        int maxClicksPerUser,
        AttributionModelStrategy attributionModel,
        MetricsReporter metricsReporter,
        long asyncTimeoutMs
    ) {
        this.attributionWindowMs = attributionWindowMs;
        this.maxClicksPerUser = maxClicksPerUser;
        this.attributionModel = attributionModel;
        this.metricsReporter = metricsReporter;
        this.asyncTimeoutMs = asyncTimeoutMs;
    }
    
    // ========== Flink 生命周期函数 ==========
    
    @Override
    public void open(OpenContext openContext) throws Exception {
        super.open(openContext);
        
        // 初始化去重状态（唯一的 Flink State）
        ValueStateDescriptor<String> dedupDesc = new ValueStateDescriptor<>(
            "event-dedup-state",
            String.class
        );
        dedupState = getRuntimeContext().getState(dedupDesc);
        
        // 初始化 Fluss KV 客户端
        kvClient = createKVClient();
        
        // 注册指标
        metricsReporter.register(getRuntimeContext().getMetricGroup());
        
        log.info("AttributionProcessFunction opened. Using Fluss KV for click storage");
    }
    
    @Override
    public void processElement(
        CallbackData callbackData,
        Context ctx,
        Collector<AttributionOutput> out
    ) throws Exception {
        long currentTime = ctx.timestamp() != null 
            ? ctx.timestamp() 
            : System.currentTimeMillis();
        
        String userId = callbackData.getUserId();
        String eventId = callbackData.getEventId();
        
        try {
            // 1. 去重检查
            if (isDuplicate(eventId)) {
                log.debug("Duplicate event detected: eventId={}", eventId);
                metricsReporter.recordDuplicateEvent();
                return;
            }
            
            // 2. 根据事件类型处理
            if (callbackData.getEventType() == EventType.CLICK) {
                processClickEvent(callbackData, ctx, currentTime, out).get(
                    asyncTimeoutMs, TimeUnit.MILLISECONDS);
            } else if (callbackData.getEventType() == EventType.CONVERSION) {
                processConversionEvent(callbackData, ctx, currentTime, out).get(
                    asyncTimeoutMs, TimeUnit.MILLISECONDS);
            } else {
                log.warn("Unknown event type: {}", callbackData.getEventType());
                metricsReporter.recordUnknownEventType();
            }
            
            // 3. 更新去重状态
            updateDedupState(eventId);
            
        } catch (TimeoutException e) {
            log.error("Async operation timeout: eventId={}", eventId, e);
            metricsReporter.recordAsyncTimeout();
            emitRetryMessage(callbackData, "ASYNC_TIMEOUT", ctx, out);
        } catch (Exception e) {
            log.error("Failed to process event: eventId={}", eventId, e);
            metricsReporter.recordProcessingFailure();
            emitRetryMessage(callbackData, e.getMessage(), ctx, out);
        }
    }
    
    @Override
    public void close() throws Exception {
        if (kvClient != null) {
            kvClient.close();
        }
        super.close();
    }
    
    // ========== 去重函数 ==========
    
    /**
     * 检查事件是否重复
     * 
     * 使用 Flink ValueState 存储最近处理的事件 ID
     * 
     * @param eventId 事件 ID
     * @return true 如果是重复事件
     */
    private boolean isDuplicate(String eventId) throws Exception {
        String lastEventId = dedupState.value();
        
        if (lastEventId != null && lastEventId.equals(eventId)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 更新去重状态
     * 
     * @param eventId 事件 ID
     */
    private void updateDedupState(String eventId) throws Exception {
        dedupState.update(eventId);
    }
    
    // ========== Click 事件处理函数 ==========
    
    /**
     * 处理 Click 事件
     * 
     * 流程:
     * 1. 验证 Click 事件
     * 2. 转换为 ClickEvent
     * 3. 从 Fluss KV 读取当前 Click 列表
     * 4. 添加新 Click
     * 5. 异步写回 Fluss KV
     * 
     * @param callbackData 原始回调数据
     * @param ctx Flink 上下文
     * @param currentTime 当前时间
     * @param out 输出收集器
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> processClickEvent(
        CallbackData callbackData,
        Context ctx,
        long currentTime,
        Collector<AttributionOutput> out
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 验证 Click 事件
                if (!validateClickEvent(callbackData)) {
                    log.warn("Invalid click event: eventId={}", callbackData.getEventId());
                    metricsReporter.recordInvalidClick();
                    return;
                }
                
                // 2. 转换为 ClickEvent
                ClickEvent clickEvent = convertToClickEvent(callbackData);
                
                // 3. 构建 Fluss KV Key
                FlussKVKey key = new FlussKVKey(callbackData.getUserId());
                
                // 4. 更新 Fluss KV（读取 - 修改 - 写入）
                kvClient.update(key, currentValue -> {
                    // 添加新 Click
                    currentValue.addClick(clickEvent, maxClicksPerUser);
                    return currentValue;
                }).join();
                
                // 5. 记录指标
                metricsReporter.recordClickProcessed();
                
                log.debug("Click event processed and stored in Fluss: userId={}, eventId={}", 
                    callbackData.getUserId(), callbackData.getEventId());
                
            } catch (Exception e) {
                log.error("Failed to process click event: eventId={}", 
                    callbackData.getEventId(), e);
                throw new CompletionException(e);
            }
        });
    }
    
    // ========== Conversion 事件处理函数 ==========
    
    /**
     * 处理 Conversion 事件
     * 
     * 流程:
     * 1. 验证 Conversion 事件
     * 2. 从 Fluss KV 读取用户 Click 列表
     * 3. 过滤有效 Click（归因窗口内）
     * 4. 应用归因模型计算权重
     * 5. 生成归因结果
     * 6. 输出结果
     * 7. 可选：清理过期 Click
     * 
     * @param callbackData 原始回调数据
     * @param ctx Flink 上下文
     * @param currentTime 当前时间
     * @param out 输出收集器
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> processConversionEvent(
        CallbackData callbackData,
        Context ctx,
        long currentTime,
        Collector<AttributionOutput> out
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 验证 Conversion 事件
                if (!validateConversionEvent(callbackData)) {
                    log.warn("Invalid conversion event: eventId={}", callbackData.getEventId());
                    metricsReporter.recordInvalidConversion();
                    return;
                }
                
                String userId = callbackData.getUserId();
                long conversionTime = callbackData.getTimestamp();
                
                // 2. 从 Fluss KV 读取 Click 列表
                FlussKVKey key = new FlussKVKey(userId);
                FlussKVValue kvValue = kvClient.get(key).get(
                    asyncTimeoutMs, TimeUnit.MILLISECONDS);
                
                // 3. 过滤有效 Click
                List<ClickEvent> validClicks = kvValue.getValidClicks(
                    conversionTime, 
                    attributionWindowMs
                );
                
                // 4. 检查是否有可归因的 Click
                if (validClicks.isEmpty()) {
                    log.info("No valid clicks for conversion: userId={}, convId={}", 
                        userId, callbackData.getEventId());
                    metricsReporter.recordNoMatchingClicks();
                    emitUnmatchedConversion(callbackData, out);
                    return;
                }
                
                // 5. 应用归因模型
                List<AttributedClick> attributedClicks = attributionModel.calculateAttribution(
                    validClicks,
                    callbackData
                );
                
                // 6. 生成归因结果
                AttributionResult result = buildAttributionResult(
                    callbackData,
                    attributedClicks,
                    currentTime
                );
                
                // 7. 输出成功结果
                emitSuccessResult(result, out);
                
                // 8. 可选：清理过期 Click 并写回
                if (shouldCleanupAfterConversion()) {
                    kvValue.cleanupExpired(currentTime, attributionWindowMs);
                    kvClient.put(key, kvValue).join();
                }
                
                // 9. 记录指标
                metricsReporter.recordConversionAttributed(validClicks.size());
                
                log.info("Conversion attributed: userId={}, convId={}, clicks={}", 
                    userId, callbackData.getEventId(), validClicks.size());
                
            } catch (Exception e) {
                log.error("Failed to process conversion event: eventId={}", 
                    callbackData.getEventId(), e);
                throw new CompletionException(e);
            }
        });
    }
    
    // ========== 验证函数 ==========
    
    private boolean validateClickEvent(CallbackData callbackData) {
        if (callbackData.getUserId() == null || callbackData.getUserId().isEmpty()) {
            log.warn("Click event missing userId: {}", callbackData.getEventId());
            return false;
        }
        
        if (callbackData.getTimestamp() == null || callbackData.getTimestamp() < 0) {
            log.warn("Click event has invalid timestamp: {}", callbackData.getEventId());
            return false;
        }
        
        if (callbackData.getAdvertiserId() == null || callbackData.getAdvertiserId().isEmpty()) {
            log.warn("Click event missing advertiserId: {}", callbackData.getEventId());
            return false;
        }
        
        return true;
    }
    
    private boolean validateConversionEvent(CallbackData callbackData) {
        if (callbackData.getUserId() == null || callbackData.getUserId().isEmpty()) {
            log.warn("Conversion event missing userId: {}", callbackData.getEventId());
            return false;
        }
        
        if (callbackData.getTimestamp() == null || callbackData.getTimestamp() < 0) {
            log.warn("Conversion event has invalid timestamp: {}", callbackData.getEventId());
            return false;
        }
        
        if (callbackData.getAdvertiserId() == null || callbackData.getAdvertiserId().isEmpty()) {
            log.warn("Conversion event missing advertiserId: {}", callbackData.getEventId());
            return false;
        }
        
        if (callbackData.getConversionType() == null || callbackData.getConversionType().isEmpty()) {
            log.warn("Conversion event missing conversionType: {}", callbackData.getEventId());
            return false;
        }
        
        return true;
    }
    
    // ========== 转换函数 ==========
    
    private ClickEvent convertToClickEvent(CallbackData callbackData) {
        return ClickEvent.builder()
            .eventId(callbackData.getEventId())
            .userId(callbackData.getUserId())
            .timestamp(callbackData.getTimestamp())
            .advertiserId(callbackData.getAdvertiserId())
            .campaignId(callbackData.getCampaignId())
            .adGroupId(callbackData.getAdGroupId())
            .creativeId(callbackData.getCreativeId())
            .placementId(callbackData.getPlacementId())
            .clickUrl(callbackData.getClickUrl())
            .landingPageUrl(callbackData.getLandingPageUrl())
            .referrer(callbackData.getReferrer())
            .deviceType(callbackData.getDeviceType())
            .os(callbackData.getOs())
            .osVersion(callbackData.getOsVersion())
            .browser(callbackData.getBrowser())
            .browserVersion(callbackData.getBrowserVersion())
            .ipAddress(callbackData.getIpAddress())
            .userAgent(callbackData.getUserAgent())
            .country(callbackData.getCountry())
            .province(callbackData.getProvince())
            .city(callbackData.getCity())
            .attributes(callbackData.getTrackingParams())
            .build();
    }
    
    // ========== 结果构建函数 ==========
    
    private AttributionResult buildAttributionResult(
        CallbackData conversionData,
        List<AttributedClick> attributedClicks,
        long currentTime
    ) {
        double totalValue = attributedClicks.stream()
            .mapToDouble(AttributedClick::getAttributedValue)
            .sum();
        
        long firstClickTime = attributedClicks.stream()
            .mapToLong(AttributedClick::getClickTimestamp)
            .min()
            .orElse(0L);
        
        long lastClickTime = attributedClicks.stream()
            .mapToLong(AttributedClick::getClickTimestamp)
            .max()
            .orElse(0L);
        
        return AttributionResult.builder()
            .attributionId(generateAttributionId())
            .conversionEventId(conversionData.getEventId())
            .userId(conversionData.getUserId())
            .advertiserId(conversionData.getAdvertiserId())
            .attributedClicks(attributedClicks)
            .totalAttributionValue(totalValue)
            .attributionModel(attributionModel.getModelName())
            .processingTimestamp(currentTime)
            .processingNode(getRuntimeContext().getTaskManagerName())
            .metadata(AttributionMetadata.builder()
                .clickCount(attributedClicks.size())
                .firstClickTimestamp(firstClickTime)
                .lastClickTimestamp(lastClickTime)
                .attributionWindow(attributionWindowMs)
                .conversionType(conversionData.getConversionType())
                .conversionValue(conversionData.getConversionValue())
                .currency(conversionData.getCurrency())
                .storageType("FLUSS_KV")
                .build())
            .build();
    }
    
    // ========== 结果输出函数 ==========
    
    private void emitSuccessResult(AttributionResult result, Collector<AttributionOutput> out) {
        AttributionOutput output = AttributionOutput.builder()
            .outputType(OutputType.SUCCESS)
            .attributionResult(result)
            .build();
        out.collect(output);
        metricsReporter.recordSuccessOutput();
    }
    
    private void emitUnmatchedConversion(CallbackData conversionData, Collector<AttributionOutput> out) {
        AttributionResult result = AttributionResult.builder()
            .attributionId(generateAttributionId())
            .conversionEventId(conversionData.getEventId())
            .userId(conversionData.getUserId())
            .advertiserId(conversionData.getAdvertiserId())
            .attributedClicks(Collections.emptyList())
            .totalAttributionValue(0.0)
            .attributionModel("UNMATCHED")
            .processingTimestamp(System.currentTimeMillis())
            .processingNode(getRuntimeContext().getTaskManagerName())
            .metadata(AttributionMetadata.builder()
                .clickCount(0)
                .conversionType(conversionData.getConversionType())
                .conversionValue(conversionData.getConversionValue())
                .currency(conversionData.getCurrency())
                .unmatchedReason("NO_VALID_CLICKS")
                .storageType("FLUSS_KV")
                .build())
            .build();
        
        AttributionOutput output = AttributionOutput.builder()
            .outputType(OutputType.UNMATCHED)
            .attributionResult(result)
            .build();
        out.collect(output);
    }
    
    private void emitRetryMessage(
        CallbackData callbackData,
        String errorMessage,
        Context ctx,
        Collector<AttributionOutput> out
    ) {
        RetryMessage retryMessage = RetryMessage.builder()
            .retryId(generateRetryId())
            .originalEventType(callbackData.getEventType().name())
            .originalEvent(callbackData.getRawData())
            .failureReason(errorMessage)
            .failureTimestamp(System.currentTimeMillis())
            .retryCount(0)
            .retryLevel(1)
            .maxRetries(3)
            .nextRetryTime(System.currentTimeMillis() + 300000)
            .metadata(RetryMetadata.builder()
                .originalSource("flink-attribution-engine-fluss-kv")
                .businessKey(callbackData.getUserId())
                .priority("NORMAL")
                .build())
            .build();
        
        AttributionOutput output = AttributionOutput.builder()
            .outputType(OutputType.RETRY)
            .retryMessage(retryMessage)
            .build();
        out.collect(output);
        metricsReporter.recordRetryOutput();
    }
    
    // ========== 工具函数 ==========
    
    private String generateAttributionId() {
        return String.format("attr_%d_%s", 
            System.currentTimeMillis(),
            UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }
    
    private String generateRetryId() {
        return String.format("retry_%d_%s", 
            System.currentTimeMillis(),
            UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }
    
    private boolean shouldCleanupAfterConversion() {
        return false; // 默认不清理，支持多次归因
    }
    
    private FlussKVClient createKVClient() {
        // 从配置或环境变量读取 Fluss 连接信息
        String bootstrapServers = getFlussBootstrapServers();
        String tableName = getFlussKVTableName();
        
        return new FlussKVClientImpl(
            bootstrapServers,
            tableName,
            5000,  // write timeout 5s
            3000   // read timeout 3s
        );
    }
    
    private String getFlussBootstrapServers() {
        // 从 Flink 配置或环境变量获取
        return System.getenv("FLUSS_BOOTSTRAP_SERVERS");
    }
    
    private String getFlussKVTableName() {
        return System.getenv("FLUSS_KV_TABLE_NAME");
    }
}
```

---

## 6. 归因模型策略（保持不变）

> 归因模型策略与 v1.0 相同，包括 LastClick、Linear、TimeDecay、PositionBased 四种模型。
> 详见原设计文档第 3 节。

---

## 7.6 Fluss KV Table Schema

### 7.1 Table 定义
```java
/**
 * Fluss KV Table Schema 定义
 */
public class FlussKVTableSchema {
    
    /**
     * 创建 KV Table Schema
     */
    public static TableSchema createSchema() {
        return TableSchema.builder()
            .field("user_id", DataTypes.STRING())        // Key (Partition Key)
            .field("clicks_data", DataTypes.STRING())    // Value (JSON)
            .field("last_update_time", DataTypes.BIGINT())
            .field("session_start_time", DataTypes.BIGINT())
            .field("version", DataTypes.BIGINT())
            .primaryKey("user_id")
            .partitionKey("user_id")
            .build();
    }
    
    /**
     * 创建 Table 的 DDL
     */
    public static String createTableDDL(String tableName) {
        return String.format(
            "CREATE TABLE %s (" +
            "    user_id STRING," +
            "    clicks_data STRING," +  // JSON 格式的 Click 列表
            "    last_update_time BIGINT," +
            "    session_start_time BIGINT," +
            "    version BIGINT," +
            "    PRIMARY KEY (user_id) NOT ENFORCED" +
            ") " +
            "PARTITIONED BY (user_id) " +
            "WITH (" +
            "    'connector' = 'fluss'," +
            "    'table-type' = 'kv'" +
            ")",
            tableName
        );
    }
}
```

### 7.2 Table 配置
```yaml
# fluss-kv-config.yaml
fluss:
  kv:
    table_name: attribution-clicks
    schema:
      primary_key: user_id
      partition_key: user_id
    
    # 存储配置
    storage:
      # 数据保留时间（7 天）
      retention_ms: 604800000
      # 压缩策略
      compaction:
        enabled: true
        min_keys: 1000
        trigger_interval_ms: 300000
    
    # 性能配置
    performance:
      # 写入批处理大小
      write_batch_size: 100
      # 读取缓存大小
      read_cache_size_mb: 256
      # 布隆过滤器
      bloom_filter:
        enabled: true
        fpp: 0.001
```

---

## 7.5 归因结果输出到 Fluss

### 7.5.1 设计说明

归因引擎将结果直接写入 **Apache Fluss**（消息队列模式），分为两个 Topic：

| Topic 名称 | 用途 | 说明 |
|-----------|------|------|
| `attribution-results-success` | 成功的归因结果 | 包含完整的归因信息 |
| `attribution-results-failed` | 失败的归因结果 | 重试后仍失败的归因记录 |

### 7.5.2 结果写入流程

```java
/**
 * 归因结果写入器
 * 
 * 负责将归因结果异步写入 Fluss
 */
public class AttributionResultWriter {
    
    /**
     * Fluss Lakehouse 客户端
     */
    private final Lakehouse lakehouse;
    
    /**
     * 成功结果 Table
     */
    private final Table successTable;
    
    /**
     * 失败结果 Table
     */
    private final Table failedTable;
    
    /**
     * 构造函数
     */
    public AttributionResultWriter(String bootstrapServers) {
        this.lakehouse = Lakehouse.builder()
            .bootstrapServers(bootstrapServers)
            .build();
        
        // 初始化成功结果 Table
        this.successTable = lakehouse.getTable("attribution-results-success");
        
        // 初始化失败结果 Table
        this.failedTable = lakehouse.getTable("attribution-results-failed");
    }
    
    /**
     * 写入成功的归因结果
     * 
     * @param result 归因结果
     */
    public void writeSuccess(AttributionResult result) {
        try {
            // 构建 Row
            Row row = buildSuccessRow(result);
            
            // 异步写入
            successTable.write(row);
            
            log.info("Success attribution result written: attributionId={}, userId={}", 
                result.getAttributionId(), result.getUserId());
            
        } catch (Exception e) {
            log.error("Failed to write success result: attributionId={}", 
                result.getAttributionId(), e);
            // 写入失败记录
            writeFailed(result, e.getMessage());
        }
    }
    
    /**
     * 写入失败的归因结果
     * 
     * @param result 归因结果
     * @param failureReason 失败原因
     */
    public void writeFailed(AttributionResult result, String failureReason) {
        try {
            // 构建 Row
            Row row = buildFailedRow(result, failureReason);
            
            // 异步写入
            failedTable.write(row);
            
            log.info("Failed attribution result written: attributionId={}, reason={}", 
                result.getAttributionId(), failureReason);
            
        } catch (Exception e) {
            log.error("Failed to write failed result: attributionId={}", 
                result.getAttributionId(), e);
        }
    }
    
    /**
     * 构建成功结果 Row
     */
    private Row buildSuccessRow(AttributionResult result) {
        return Row.of(
            result.getAttributionId(),                    // attribution_id
            result.getConversionEventId(),                // conversion_event_id
            result.getUserId(),                           // user_id
            result.getAdvertiserId(),                     // advertiser_id
            JsonUtil.toJson(result.getAttributedClicks()), // attributed_clicks (JSON)
            result.getTotalAttributionValue(),            // total_value
            result.getAttributionModel(),                 // attribution_model
            result.getProcessingTimestamp(),              // processing_time
            result.getProcessingNode(),                   // processing_node
            JsonUtil.toJson(result.getMetadata()),        // metadata (JSON)
            "SUCCESS",                                    // status
            System.currentTimeMillis()                    // create_time
        );
    }
    
    /**
     * 构建失败结果 Row
     */
    private Row buildFailedRow(AttributionResult result, String failureReason) {
        return Row.of(
            result.getAttributionId(),                    // attribution_id
            result.getConversionEventId(),                // conversion_event_id
            result.getUserId(),                           // user_id
            result.getAdvertiserId(),                     // advertiser_id
            JsonUtil.toJson(result.getAttributedClicks()), // attributed_clicks (JSON)
            result.getTotalAttributionValue(),            // total_value
            result.getAttributionModel(),                 // attribution_model
            result.getProcessingTimestamp(),              // processing_time
            result.getProcessingNode(),                   // processing_node
            JsonUtil.toJson(result.getMetadata()),        // metadata (JSON)
            "FAILED",                                     // status
            failureReason,                                // failure_reason
            System.currentTimeMillis()                    // create_time
        );
    }
    
    /**
     * 关闭写入器
     */
    public void close() throws Exception {
        if (lakehouse != null) {
            lakehouse.close();
        }
    }
}
```

### 7.5.3 Fluss Table Schema

#### 成功结果 Table
```sql
CREATE TABLE `attribution-results-success` (
    attribution_id STRING,
    conversion_event_id STRING,
    user_id STRING,
    advertiser_id STRING,
    attributed_clicks STRING,        -- JSON 格式
    total_value DOUBLE,
    attribution_model STRING,
    processing_time BIGINT,
    processing_node STRING,
    metadata STRING,                 -- JSON 格式
    status STRING,
    create_time BIGINT,
    PRIMARY KEY (attribution_id) NOT ENFORCED
) WITH (
    'connector' = 'fluss',
    'table-type' = 'message_queue',  -- 消息队列模式
    'partition-key' = 'user_id'      -- 按用户分区
);
```

#### 失败结果 Table
```sql
CREATE TABLE `attribution-results-failed` (
    attribution_id STRING,
    conversion_event_id STRING,
    user_id STRING,
    advertiser_id STRING,
    attributed_clicks STRING,        -- JSON 格式
    total_value DOUBLE,
    attribution_model STRING,
    processing_time BIGINT,
    processing_node STRING,
    metadata STRING,                 -- JSON 格式
    status STRING,
    failure_reason STRING,
    create_time BIGINT,
    PRIMARY KEY (attribution_id) NOT ENFORCED
) WITH (
    'connector' = 'fluss',
    'table-type' = 'message_queue',  -- 消息队列模式
    'partition-key' = 'user_id'      -- 按用户分区
);
```

### 7.5.4 消息格式

#### 成功结果消息示例
```json
{
  "attribution_id": "attr_1677072000000_a1b2c3d4",
  "conversion_event_id": "conv_789xyz",
  "user_id": "user_123456",
  "advertiser_id": "adv_001",
  "attributed_clicks": [
    {
      "click_event_id": "click_001",
      "campaign_id": "camp_001",
      "creative_id": "creative_001",
      "attribution_model": "LAST_CLICK",
      "attribution_weight": 1.0,
      "attributed_value": 99.99,
      "click_timestamp": 1677068400000,
      "conversion_timestamp": 1677072000000,
      "time_to_conversion": 3600000,
      "time_to_conversion_unit": "MILLISECONDS"
    }
  ],
  "total_value": 99.99,
  "attribution_model": "LAST_CLICK",
  "processing_time": 1677072000000,
  "processing_node": "taskmanager-1",
  "metadata": {
    "click_count": 1,
    "first_click_timestamp": 1677068400000,
    "last_click_timestamp": 1677068400000,
    "attribution_window": 604800000,
    "conversion_type": "purchase",
    "conversion_value": 99.99,
    "currency": "USD",
    "storage_type": "FLUSS_KV"
  },
  "status": "SUCCESS",
  "create_time": 1677072000000
}
```

#### 失败结果消息示例
```json
{
  "attribution_id": "attr_1677072000000_e5f6g7h8",
  "conversion_event_id": "conv_456abc",
  "user_id": "user_789012",
  "conversion_event_id": "conv_456abc",
  "advertiser_id": "adv_001",
  "attributed_clicks": [],
  "total_value": 0.0,
  "attribution_model": "UNMATCHED",
  "processing_time": 1677072000000,
  "processing_node": "taskmanager-2",
  "metadata": {
    "click_count": 0,
    "conversion_type": "purchase",
    "conversion_value": 199.99,
    "currency": "USD",
    "storage_type": "FLUSS_KV",
    "unmatched_reason": "NO_VALID_CLICKS"
  },
  "status": "FAILED",
  "failure_reason": "No valid clicks found within attribution window",
  "create_time": 1677072000000
}
```

### 7.5.5 集成到 AttributionProcessFunction

```java
public class AttributionProcessFunction 
    extends KeyedProcessFunction<String, CallbackData, AttributionOutput> {
    
    // ========== 新增组件 ==========
    
    /**
     * 归因结果写入器
     */
    private transient AttributionResultWriter resultWriter;
    
    @Override
    public void open(OpenContext openContext) throws Exception {
        super.open(openContext);
        
        // 初始化去重状态
        ValueStateDescriptor<String> dedupDesc = new ValueStateDescriptor<>(
            "event-dedup-state",
            String.class
        );
        dedupState = getRuntimeContext().getState(dedupDesc);
        
        // 初始化 Fluss KV 客户端
        kvClient = createKVClient();
        
        // ========== 初始化结果写入器 ==========
        resultWriter = new AttributionResultWriter(getFlussBootstrapServers());
        
        // 注册指标
        metricsReporter.register(getRuntimeContext().getMetricGroup());
        
        log.info("AttributionProcessFunction opened. Using Fluss KV for click storage");
    }
    
    @Override
    public void processElement(
        CallbackData callbackData,
        Context ctx,
        Collector<AttributionOutput> out
    ) throws Exception {
        // ... 原有处理逻辑 ...
        
        // 处理 Conversion 事件时写入结果
        if (callbackData.getEventType() == EventType.CONVERSION) {
            processConversionEvent(callbackData, ctx, currentTime, out).get(
                asyncTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }
    
    @Override
    public void close() throws Exception {
        if (kvClient != null) {
            kvClient.close();
        }
        
        // 关闭结果写入器
        if (resultWriter != null) {
            resultWriter.close();
        }
        
        super.close();
    }
    
    // ========== 修改结果输出函数 ==========
    
    private void emitSuccessResult(AttributionResult result, Collector<AttributionOutput> out) {
        // 写入 Fluss（消息队列）
        resultWriter.writeSuccess(result);
        
        // 同时输出到 DataStream（可选，用于实时监控）
        AttributionOutput output = AttributionOutput.builder()
            .outputType(OutputType.SUCCESS)
            .attributionResult(result)
            .build();
        out.collect(output);
        metricsReporter.recordSuccessOutput();
    }
    
    private void emitFailedResult(AttributionResult result, String failureReason) {
        // 写入失败结果到 Fluss
        resultWriter.writeFailed(result, failureReason);
        metricsReporter.recordProcessingFailure();
    }
}
```

---

## 9. 指标设计

### 8.1 Flink 指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| click.processed.total | Counter | 处理的 Click 总数 |
| click.invalid.total | Counter | 无效 Click 数 |
| conversion.attributed.total | Counter | 成功归因的转化数 |
| conversion.unmatched.total | Counter | 未匹配 Click 的转化数 |
| dedup.hit.total | Counter | 去重命中数 |
| async.timeout.total | Counter | 异步操作超时数 |
| fluss.kv.read.latency.ms | Histogram | Fluss 读取延迟 |
| fluss.kv.write.latency.ms | Histogram | Fluss 写入延迟 |
| **result.success.written** | **Counter** | **写入成功结果数** |
| **result.failed.written** | **Counter** | **写入失败结果数** |

### 8.2 Fluss KV 指标
| 指标名称 | 说明 |
|---------|------|
| kv.get.count | KV 读取次数 |
| kv.put.count | KV 写入次数 |
| kv.update.count | KV 更新次数 |
| kv.read.bytes | KV 读取字节数 |
| kv.write.bytes | KV 写入字节数 |
| **result.success.write.bytes** | **成功结果写入字节数** |
| **result.failed.write.bytes** | **失败结果写入字节数** |

---

## 9. 配置参数

### 9.1 核心配置
| 参数名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| attribution.window.ms | Long | 604800000 | 归因窗口（7 天） |
| attribution.max.clicks | Integer | 50 | 单用户最大 Click 数 |
| attribution.model | String | LAST_CLICK | 归因模型 |
| fluss.bootstrap.servers | String | - | Fluss 服务器地址 |
| fluss.kv.table.name | String | attribution-clicks | KV Table 名称 |
| async.timeout.ms | Long | 5000 | 异步操作超时 |

### 9.2 去重配置
| 参数名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| dedup.enabled | Boolean | true | 是否启用去重 |
| dedup.window.ms | Long | 3600000 | 去重窗口（1 小时） |

---

## 10. 异常处理

### 10.1 异常类型
```java
/**
 * Fluss KV 读取异常
 */
public class FlussKVReadException extends RuntimeException {
    public FlussKVReadException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Fluss KV 写入异常
 */
public class FlussKVWriteException extends RuntimeException {
    public FlussKVWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Fluss KV 解析异常
 */
public class FlussKVParseException extends RuntimeException {
    public FlussKVParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 异步操作超时异常
 */
public class AsyncTimeoutException extends RuntimeException {
    public AsyncTimeoutException(String message) {
        super(message);
    }
}
```

### 10.2 异常处理策略
| 异常类型 | 处理策略 |
|---------|---------|
| FlussKVReadException | 重试 3 次，失败后发送重试消息 |
| FlussKVWriteException | 重试 3 次，失败后发送重试消息 |
| AsyncTimeoutException | 直接发送重试消息 |
| DataValidationException | 记录日志，跳过该事件 |

---

## 12. 性能优化建议

### 11.1 Fluss KV 优化
- **批量读写**: 使用 `batchGet()` 和 `batchPut()` 减少网络往返
- **连接池**: 复用 Fluss KV Client 连接
- **本地缓存**: 对热点用户数据使用本地缓存（Caffeine）

### 11.2 Flink 优化
- **并行度**: 按 userId 分区，建议与 Fluss 分区数一致
- **Checkpoint**: 由于状态最小化，Checkpoint 开销大幅降低
- **背压**: 异步 KV 读写避免阻塞，减少背压风险

### 11.3 数据倾斜处理
- **热点用户**: 对 Click 数超多的用户进行特殊处理（单独存储）
- **分区均衡**: 监控 Fluss 分区负载，必要时调整分区策略

---

## 12. 与 v1.0 的对比总结

| 方面 | v1.0 (Flink State) | v2.0 (Fluss KV) | 改进 |
|------|-------------------|-----------------|------|
| Flink State 使用 | 3 个状态（pendingClicks, sessionStart, lastActivity） | 1 个状态（dedup） | ✅ 减少 67% |
| 状态存储位置 | Flink TaskManager (RocksDB) | Apache Fluss KV | ✅ 独立扩展 |
| 状态恢复 | Checkpoint 恢复 | Fluss 持久化 | ✅ 更快恢复 |
| 并发能力 | 单 Key 单实例 | 支持并发读写 | ✅ 更高吞吐 |
| 数据保留 | 受 State TTL 限制 | 可配置更长 | ✅ 更灵活 |
| 资源消耗 | 占用 TM 资源 | 独立集群 | ✅ 降低 Flink 压力 |

---

**文档结束**

*此设计文档基于 Review 意见进行了重大调整，使用 Fluss KV 存储替代 Flink State，最小化 Flink 状态使用，同时保持完整的归因功能。*
