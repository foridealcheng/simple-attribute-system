# Fluss Schema 设计文档

> T005 任务交付物 - Apache Fluss Stream Schemas

**更新时间**: 2026-02-23  
**状态**: ✅ 完成  
**关联设计**: DESIGN-02-ATTRIBUTION-ENGINE.md

---

## 📊 概览

定义了归因系统中所有 Apache Fluss 数据流的 Schema，包括：

- **消息队列 Streams**: Click 事件、转化事件、归因结果
- **KV 存储 Tables**: 用户点击会话存储

---

## 📋 Schema 清单

### 1. Click Event Stream

**用途**: 存储广告点击事件  
**Stream 名称**: `click-events-stream`  
**分区键**: `user_id`  
**对应模型**: `ClickEvent.java`

| 字段名 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| event_id | STRING | 事件唯一 ID | click_123456 |
| user_id | STRING | 用户 ID（归因 Key） | user_789abc |
| timestamp | BIGINT | 点击时间戳（毫秒） | 1708512000000 |
| advertiser_id | STRING | 广告主 ID | adv_001 |
| campaign_id | STRING | 广告系列 ID | camp_001 |
| creative_id | STRING | 广告创意 ID | cre_001 |
| placement_id | STRING | 广告位 ID | place_001 |
| media_id | STRING | 媒体渠道 ID | media_001 |
| click_type | STRING | 点击类型 | impression/click/view |
| ip_address | STRING | IP 地址 | 192.168.1.1 |
| user_agent | STRING | 用户代理 | Mozilla/5.0... |
| device_type | STRING | 设备类型 | mobile/desktop/tablet |
| os | STRING | 操作系统 | iOS/Android/Windows |
| app_version | STRING | 应用版本 | 1.2.3 |
| attributes | STRING | 额外属性（JSON） | {"key":"value"} |
| create_time | BIGINT | 创建时间 | 1708512000000 |
| source | STRING | 数据来源 | kafka/rocketmq/fluss |

**DDL 示例**:
```sql
CREATE TABLE click_events_stream (
    event_id STRING,
    user_id STRING,
    timestamp BIGINT,
    advertiser_id STRING,
    campaign_id STRING,
    creative_id STRING,
    placement_id STRING,
    media_id STRING,
    click_type STRING,
    ip_address STRING,
    user_agent STRING,
    device_type STRING,
    os STRING,
    app_version STRING,
    attributes STRING,
    create_time BIGINT,
    source STRING
) WITH (
    'connector' = 'fluss',
    'partition.key' = 'user_id'
);
```

---

### 2. Conversion Event Stream

**用途**: 存储用户转化事件  
**Stream 名称**: `conversion-events-stream`  
**分区键**: `user_id`  
**对应模型**: `ConversionEvent.java`

| 字段名 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| event_id | STRING | 事件唯一 ID | conv_123456 |
| user_id | STRING | 用户 ID | user_789abc |
| timestamp | BIGINT | 转化时间戳 | 1708512000000 |
| advertiser_id | STRING | 广告主 ID | adv_001 |
| campaign_id | STRING | 广告系列 ID | camp_001 |
| conversion_type | STRING | 转化类型 | purchase/signup/download |
| conversion_value | DOUBLE | 转化价值 | 99.99 |
| currency | STRING | 货币类型 | USD/CNY |
| transaction_id | STRING | 交易 ID | txn_123456 |
| product_id | STRING | 产品 ID | prod_789 |
| quantity | INT | 购买数量 | 2 |
| ip_address | STRING | IP 地址 | 192.168.1.1 |
| user_agent | STRING | 用户代理 | Mozilla/5.0... |
| device_type | STRING | 设备类型 | mobile |
| os | STRING | 操作系统 | iOS |
| app_version | STRING | 应用版本 | 1.2.3 |
| attributes | STRING | 额外属性（JSON） | {"coupon":"SAVE10"} |
| create_time | BIGINT | 创建时间 | 1708512000000 |
| source | STRING | 数据来源 | kafka/rocketmq/fluss |

---

### 3. Attribution Result Stream

**用途**: 存储归因结果（成功/失败）  
**Stream 名称**: `attribution-results-success`, `attribution-results-failed`  
**分区键**: `user_id`  
**对应模型**: `AttributionResult.java`

| 字段名 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| result_id | STRING | 结果唯一 ID | result_123456 |
| user_id | STRING | 用户 ID | user_789abc |
| conversion_id | STRING | 转化事件 ID | conv_123456 |
| attribution_model | STRING | 归因模型 | LAST_CLICK/LINEAR/TIME_DECAY |
| attributed_clicks | ARRAY<STRING> | 归因点击 ID 列表 | ["click_1","click_2"] |
| credit_distribution | MAP<STRING,DOUBLE> | 信用分配 | {"click_1":1.0} |
| total_conversion_value | DOUBLE | 总转化价值 | 99.99 |
| currency | STRING | 货币类型 | USD |
| advertiser_id | STRING | 广告主 ID | adv_001 |
| campaign_id | STRING | 广告系列 ID | camp_001 |
| attribution_timestamp | BIGINT | 归因时间戳 | 1708512000000 |
| lookback_window_hours | INT | 回溯窗口（小时） | 24 |
| status | STRING | 状态 | SUCCESS/FAILED/RETRY |
| error_message | STRING | 错误信息 | null |
| retry_count | INT | 重试次数 | 0 |
| create_time | BIGINT | 创建时间 | 1708512000000 |

---

### 4. User Click Session KV Table

**用途**: KV 存储用户点击会话（Fluss KV Store）  
**Table 名称**: `user-click-sessions`  
**主键**: `user_id`  
**对应设计**: DESIGN-02-ATTRIBUTION-ENGINE.md

| 字段名 | 类型 | 说明 |
|--------|------|------|
| user_id | STRING | 用户 ID（Key） |
| clicks | ARRAY<ROW> | 点击事件列表 |
| session_start_time | BIGINT | 会话开始时间 |
| last_update_time | BIGINT | 最后更新时间 |
| click_count | INT | 点击数量 |

**Click Row 结构**:
```java
ROW(
    event_id STRING,
    timestamp BIGINT,
    campaign_id STRING,
    creative_id STRING,
    advertiser_id STRING
)
```

**DDL 示例**:
```sql
CREATE TABLE user_click_sessions (
    user_id STRING,
    clicks ARRAY<ROW<
        event_id STRING,
        timestamp BIGINT,
        campaign_id STRING,
        creative_id STRING,
        advertiser_id STRING
    >>,
    session_start_time BIGINT,
    last_update_time BIGINT,
    click_count INT
) WITH (
    'connector' = 'fluss',
    'table.kind' = 'PRIMARY KEY',
    'primary.key' = 'user_id'
);
```

---

## 🗂️ 文件结构

```
SimpleAttributeSystem/
└── src/main/java/com/attribution/
    └── schema/
        ├── FlussSchemas.java     # Schema 定义（RowType）
        ├── SchemaUtils.java      # 模型↔Row 转换工具
        └── README.md             # 本文档
```

---

## 🔧 使用示例

### 1. 获取 Schema

```java
import com.attribution.schema.FlussSchemas;
import org.apache.fluss.types.RowType;

// 获取 Click Event Schema
RowType clickSchema = FlussSchemas.CLICK_EVENT_SCHEMA;

// 获取 Conversion Event Schema
RowType conversionSchema = FlussSchemas.CONVERSION_EVENT_SCHEMA;

// 获取 Attribution Result Schema
RowType resultSchema = FlussSchemas.ATTRIBUTION_RESULT_SCHEMA;
```

### 2. 生成 DDL

```java
import com.attribution.schema.FlussSchemas;

// 生成 Stream 表 DDL
String ddl = FlussSchemas.getTableDDL(
    "click_events_stream",
    FlussSchemas.CLICK_EVENT_SCHEMA,
    "user_id"
);

// 生成 KV 表 DDL
String kvDdl = FlussSchemas.getKVTableDDL(
    "user_click_sessions",
    FlussSchemas.USER_CLICK_SESSION_SCHEMA,
    "user_id"
);
```

### 3. 模型转 Row

```java
import com.attribution.schema.SchemaUtils;
import com.attribution.model.ClickEvent;
import org.apache.fluss.row.GenericRow;

ClickEvent click = ClickEvent.builder()
    .eventId("click_123")
    .userId("user_456")
    .timestamp(System.currentTimeMillis())
    // ... 其他字段
    .build();

GenericRow row = SchemaUtils.toClickEventRow(click);
```

---

## 📝 设计决策

### 为什么使用 RowType？

**优点**:
- ✅ 类型安全：编译时检查字段类型
- ✅ 与 Flink/Fluss 原生集成
- ✅ 支持自动生成 DDL
- ✅ 便于序列化和反序列化

**替代方案**:
- ❌ 纯字符串 DDL：容易出错，难以维护
- ❌ JSON Schema：类型检查弱，性能差

### 为什么分区键是 user_id？

**原因**:
1. **归因需求**: 同一用户的点击和转化需要在同一分区处理
2. **数据局部性**: 减少跨分区数据传输
3. **性能优化**: 提高归因计算效率

**注意事项**:
- 确保 user_id 分布均匀，避免数据倾斜
- 对于没有 user_id 的事件，使用默认值或哈希

### 为什么 attributes 字段是 STRING？

**原因**:
1. **灵活性**: 支持任意 JSON 结构
2. **扩展性**: 新增字段无需修改 Schema
3. **兼容性**: 不同广告主的字段差异大

**缺点**:
- 需要在应用层解析 JSON
- 无法在 SQL 层直接查询内部字段

**解决方案**:
- 使用 Flink SQL 的 JSON 函数提取字段
- 对于常用字段，建议提升到顶层

---

## 🎯 最佳实践

### 1. Schema 演进

**向后兼容**:
- ✅ 只添加新字段（放在末尾）
- ✅ 新字段设置默认值或允许 NULL
- ❌ 不要删除已有字段
- ❌ 不要修改字段类型

**示例**:
```java
// ✅ 好的做法：添加新字段
public static final RowType CLICK_EVENT_SCHEMA_V2 = DataTypes.ROW(
    // ... 原有字段
    DataTypes.FIELD("new_field", DataTypes.STRING())  // 新增
);

// ❌ 坏的做法：删除字段
public static final RowType CLICK_EVENT_SCHEMA_BAD = DataTypes.ROW(
    // 删除了某个字段
);
```

### 2. 字段命名规范

- 使用 `snake_case`（下划线分隔）
- 保持命名一致性
- 避免保留字（如 `timestamp`, `value` 等）

### 3. 数据类型选择

| 场景 | 推荐类型 | 说明 |
|------|----------|------|
| 时间戳 | BIGINT | 毫秒精度 |
| 金额 | DOUBLE | 支持小数 |
| 数量 | INT | 整数 |
| ID | STRING | 灵活，支持各种格式 |
| 布尔值 | BOOLEAN | true/false |
| JSON | STRING | 序列化后的 JSON |

---

## 📊 数据量估算

### Click Event Stream

假设：
- 日均点击量：1 亿次
- 平均每条大小：500 bytes
- 保留期：30 天

**存储需求**:
```
1 亿 * 500 bytes * 30 天 = 1.5 TB
```

### Conversion Event Stream

假设：
- 日均转化量：1000 万次（转化率 10%）
- 平均每条大小：600 bytes
- 保留期：30 天

**存储需求**:
```
1000 万 * 600 bytes * 30 天 = 180 GB
```

### Attribution Result Stream

假设：
- 日均归因结果：1000 万条
- 平均每条大小：800 bytes
- 保留期：90 天（需要更长时间用于对账）

**存储需求**:
```
1000 万 * 800 bytes * 90 天 = 720 GB
```

---

## 🔍 监控指标

### Schema 相关

- **Schema 版本**: 当前使用的 Schema 版本
- **字段使用率**: 哪些字段经常被查询
- **数据倾斜**: 分区键分布是否均匀

### 性能相关

- **写入吞吐量**: 条/秒
- **读取延迟**: 毫秒
- **存储使用率**: GB/TB

---

## 📚 参考资料

- [Apache Fluss Documentation](https://fluss.apache.org/docs/)
- [Fluss Table & Schema](https://fluss.apache.org/docs/dev/table/api/)
- DESIGN-02-ATTRIBUTION-ENGINE.md
- ARCHITECTURE.md

---

**任务状态**: ✅ T005 完成  
**下一步**: T015/T016 - 实现数据源适配器和格式解码器
