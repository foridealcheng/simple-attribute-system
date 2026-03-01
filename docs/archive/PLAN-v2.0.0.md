# v2.0.0 版本规划

**版本**: 2.0.0  
**规划日期**: 2026-02-23  
**目标**: 生产级增强 - 状态外移 + 多 Sink + 重试机制

---

## 🎯 核心目标

### Feature 1: 状态外移到 Fluss
- [ ] 使用 Fluss KV Store 替代 Flink MapState
- [ ] 支持跨作业状态共享
- [ ] 支持状态 TTL 和清理
- [ ] 支持大规模数据 (RocksDB)

### Feature 2: Kafka Sink 支持
- [ ] 归因成功结果 → Kafka (attribution-results-success)
- [ ] 归因失败结果 → Kafka (attribution-results-failed)
- [ ] 支持批量发送
- [ ] 支持 Exactly-Once 语义

### Feature 3: RocketMQ 重试机制
- [ ] 失败结果 → RocketMQ 延迟队列
- [ ] 3 级延迟：5min → 30min → 2h
- [ ] 死信队列支持
- [ ] 重试计数器

---

## 📋 Task 拆分

### Phase 1: 准备工作 (1 天)

#### Task 1.1: 创建 v2.0.0 分支
- [ ] 从 main 创建 `feature/v2.0.0` 分支
- [ ] 更新 pom.xml 版本为 `2.0.0-SNAPSHOT`
- [ ] 创建 Release Notes 模板

**交付物**:
- feature/v2.0.0 分支
- pom.xml 版本更新

**预计时间**: 30 分钟

---

#### Task 1.2: 添加 Fluss 依赖
- [ ] 更新 pom.xml 添加 Fluss 依赖
- [ ] 配置 Fluss Maven 仓库 (本地或 Apache Snapshots)
- [ ] 验证依赖解析

**依赖**:
```xml
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

**交付物**:
- pom.xml 更新
- 依赖验证通过

**预计时间**: 1 小时

---

#### Task 1.3: 添加 RocketMQ 依赖
- [ ] 更新 pom.xml 添加 RocketMQ 依赖
- [ ] 验证依赖解析

**依赖**:
```xml
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

**交付物**:
- pom.xml 更新
- 依赖验证通过

**预计时间**: 30 分钟

---

#### Task 1.4: 更新 Docker 环境
- [ ] 添加 Fluss 到 docker-compose.yml
- [ ] 添加 RocketMQ 到 docker-compose.yml
- [ ] 测试环境启动

**交付物**:
- docker-compose.yml 更新
- 本地测试环境可用

**预计时间**: 2 小时

---

### Phase 2: Feature 1 - 状态外移到 Fluss (3 天)

#### Task 2.1: 设计 Fluss KV Schema
- [ ] 设计 user-click-sessions 表结构
- [ ] 定义 Key-Value Schema
- [ ] 创建 Fluss DDL

**Schema 设计**:
```sql
-- Fluss KV Table
CREATE TABLE user_click_sessions (
    user_id STRING PRIMARY KEY,
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
    'table-type' = 'PRIMARY KEY'
);
```

**交付物**:
- FlussSchemas.java (更新)
- DDL 脚本

**预计时间**: 2 小时

---

#### Task 2.2: 实现 Fluss KV Client
- [ ] 创建 FlussKVClient 工具类
- [ ] 实现 get/put/delete 方法
- [ ] 实现批量操作
- [ ] 添加连接池支持

**接口设计**:
```java
public class FlussKVClient {
    public FlussClickSession get(String userId);
    public void put(String userId, FlussClickSession session);
    public void delete(String userId);
    public void close();
}
```

**交付物**:
- FlussKVClient.java
- 单元测试

**预计时间**: 4 小时

---

#### Task 2.3: 重构 AttributionProcessFunction
- [ ] 移除 MapState 使用
- [ ] 集成 FlussKVClient
- [ ] 实现状态读取
- [ ] 实现状态写入
- [ ] 实现状态清理 (TTL)

**代码变更**:
```java
// 旧代码 (移除)
private transient MapState<String, FlussClickSession> clickSessionState;

// 新代码 (添加)
private transient FlussKVClient flussKVClient;
```

**交付物**:
- AttributionProcessFunction.java (重构)
- 集成测试

**预计时间**: 6 小时

---

#### Task 2.4: 实现状态 TTL 和清理
- [ ] 配置 TTL (默认 24 小时)
- [ ] 实现过期检查逻辑
- [ ] 实现自动清理
- [ ] 添加监控指标

**TTL 配置**:
```yaml
state.ttl.hours: 24
state.cleanup.interval.minutes: 60
```

**交付物**:
- TTL 配置支持
- 清理逻辑实现

**预计时间**: 3 小时

---

#### Task 2.5: Fluss 状态性能优化
- [ ] 批量读取优化
- [ ] 批量写入优化
- [ ] 本地缓存 (可选)
- [ ] 性能测试

**优化目标**:
- P99 延迟 < 10ms
- 吞吐量 > 5000 ops/s

**交付物**:
- 性能优化代码
- 性能测试报告

**预计时间**: 4 小时

---

### Phase 3: Feature 2 - Kafka Sink 支持 (2 天)

#### Task 3.1: 设计 Kafka Sink 架构
- [ ] 设计 Success Sink
- [ ] 设计 Failed Sink
- [ ] 定义消息格式
- [ ] 定义 Topic 命名

**Topic 设计**:
```
attribution-results-success (3 partitions)
attribution-results-failed (3 partitions)
```

**消息格式**:
```json
{
  "resultId": "result_conv-001_xxx",
  "conversionId": "conv-001",
  "userId": "user-123",
  "status": "SUCCESS",
  "attributionModel": "LAST_CLICK",
  "attributedClicks": ["click-003"],
  "creditDistribution": {...},
  "totalConversionValue": 199.99,
  "timestamp": 1708678800000
}
```

**交付物**:
- 架构设计文档
- 消息格式定义

**预计时间**: 2 小时

---

#### Task 3.2: 实现 KafkaSink 实现类
- [ ] 创建 AttributionKafkaSink 类
- [ ] 实现 SinkFunction 接口
- [ ] 支持批量发送
- [ ] 支持异步发送
- [ ] 实现序列化

**代码结构**:
```java
public class AttributionKafkaSink extends RichSinkFunction<AttributionResult> {
    private KafkaProducer<String, String> producer;
    
    @Override
    public void invoke(AttributionResult result, Context context) {
        // 序列化
        // 发送到 Kafka
    }
}
```

**交付物**:
- AttributionKafkaSink.java
- 单元测试

**预计时间**: 4 小时

---

#### Task 3.3: 实现 Success/Failed 路由
- [ ] 根据状态路由到不同 Topic
- [ ] 实现条件判断逻辑
- [ ] 添加错误处理

**路由逻辑**:
```java
if (result.getStatus().equals("SUCCESS")) {
    sendTo(SUCCESS_TOPIC, result);
} else {
    sendTo(FAILED_TOPIC, result);
}
```

**交付物**:
- 路由逻辑实现
- 集成测试

**预计时间**: 2 小时

---

#### Task 3.4: 实现 Exactly-Once 语义
- [ ] 配置 Kafka Producer 幂等
- [ ] 集成 Flink Checkpoint
- [ ] 实现两阶段提交 (可选)
- [ ] 测试数据一致性

**配置**:
```java
props.put("enable.idempotence", "true");
props.put("acks", "all");
props.put("retries", "3");
```

**交付物**:
- Exactly-Once 配置
- 一致性测试报告

**预计时间**: 4 小时

---

#### Task 3.5: 性能优化和监控
- [ ] 批量发送优化
- [ ] 添加发送指标
- [ ] 添加延迟监控
- [ ] 添加失败率监控

**监控指标**:
- records_sent
- send_latency_p99
- send_error_rate
- batch_size_avg

**交付物**:
- 性能优化
- 监控指标

**预计时间**: 3 小时

---

### Phase 4: Feature 3 - RocketMQ 重试机制 (3 天)

#### Task 4.1: 设计重试架构
- [ ] 设计延迟队列层级
- [ ] 设计重试流程
- [ ] 设计死信队列
- [ ] 定义重试策略

**重试策略**:
```
失败 → Level 1 (5min) → Level 2 (30min) → Level 3 (2h) → 死信队列
```

**交付物**:
- 重试架构设计文档
- 流程图

**预计时间**: 2 小时

---

#### Task 4.2: 实现 RocketMQ Producer
- [ ] 创建 RocketMQProducer 类
- [ ] 实现发送延迟消息
- [ ] 实现发送死信消息
- [ ] 配置重试级别

**代码结构**:
```java
public class RetryProducer {
    public void sendRetryMessage(AttributionResult result, int retryLevel);
    public void sendDeadLetterMessage(AttributionResult result);
}
```

**延迟级别**:
```java
// RocketMQ 延迟级别
// 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
int[] delayLevel = {0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 0, 16}; // 5min, 30min, 2h
```

**交付物**:
- RetryProducer.java
- 单元测试

**预计时间**: 4 小时

---

#### Task 4.3: 实现重试 Consumer
- [ ] 创建 RetryConsumer 类
- [ ] 订阅延迟 Topic
- [ ] 实现重试逻辑
- [ ] 实现重试计数

**重试逻辑**:
```java
public class RetryConsumer {
    public void consume(RetryMessage message) {
        int retryCount = message.getRetryCount();
        if (retryCount < MAX_RETRY) {
            // 重新执行归因
            if (success) {
                sendToSuccessSink();
            } else {
                sendToNextLevel(retryCount + 1);
            }
        } else {
            sendToDeadLetter();
        }
    }
}
```

**交付物**:
- RetryConsumer.java
- 单元测试

**预计时间**: 6 小时

---

#### Task 4.4: 实现死信队列处理
- [ ] 创建 DeadLetterConsumer
- [ ] 实现死信消息处理
- [ ] 添加告警通知
- [ ] 实现手动重放

**死信处理**:
```java
public class DeadLetterHandler {
    public void handle(AttributionResult result) {
        // 记录日志
        // 发送告警
        // 存储到 DB (用于手动重放)
    }
}
```

**交付物**:
- DeadLetterHandler.java
- 告警集成

**预计时间**: 3 小时

---

#### Task 4.5: 重试监控和配置
- [ ] 实现重试配置化
- [ ] 添加重试指标
- [ ] 实现重试监控面板
- [ ] 添加告警规则

**配置项**:
```yaml
retry:
  max-levels: 3
  level-1-delay: 5m
  level-2-delay: 30m
  level-3-delay: 2h
  max-retry-count: 3
```

**监控指标**:
- retry_count_total
- retry_success_rate
- dead_letter_count
- retry_latency

**交付物**:
- 配置化支持
- 监控指标
- 告警规则

**预计时间**: 4 小时

---

### Phase 5: 集成测试和优化 (2 天)

#### Task 5.1: 端到端集成测试
- [ ] 测试完整数据流
- [ ] 测试状态外移
- [ ] 测试 Kafka Sink
- [ ] 测试重试机制
- [ ] 测试故障恢复

**测试场景**:
1. 正常流程测试
2. 失败重试测试
3. 状态恢复测试
4. 性能压力测试

**交付物**:
- 集成测试报告
- Bug 修复

**预计时间**: 8 小时

---

#### Task 5.2: 性能测试和优化
- [ ] 基准性能测试
- [ ] 压力测试
- [ ] 稳定性测试
- [ ] 性能瓶颈分析
- [ ] 优化实施

**性能目标**:
- 吞吐量：>5000 条/秒
- 延迟：P99 < 100ms
- 可用性：>99.9%

**交付物**:
- 性能测试报告
- 优化报告

**预计时间**: 8 小时

---

#### Task 5.3: 文档更新
- [ ] 更新架构文档
- [ ] 更新部署文档
- [ ] 更新 API 文档
- [ ] 编写迁移指南
- [ ] 编写运维手册

**交付物**:
- 完整文档更新
- 迁移指南

**预计时间**: 4 小时

---

#### Task 5.4: Code Review 和 Bug 修复
- [ ] 代码审查
- [ ] 修复 Review 意见
- [ ] 修复测试 Bug
- [ ] 代码格式化
- [ ] 最终验证

**交付物**:
- CR 通过
- Bug 清零

**预计时间**: 4 小时

---

### Phase 6: 发布准备 (1 天)

#### Task 6.1: 版本发布
- [ ] 更新版本号到 2.0.0
- [ ] 编写 Release Notes
- [ ] 创建 Git Tag
- [ ] 推送到远程仓库
- [ ] 创建 GitHub Release

**交付物**:
- v2.0.0 Release
- Release Notes

**预计时间**: 2 小时

---

#### Task 6.2: 部署验证
- [ ] 生产环境部署
- [ ] 功能验证
- [ ] 性能验证
- [ ] 监控验证
- [ ] 回滚方案测试

**交付物**:
- 部署报告
- 验证报告

**预计时间**: 4 小时

---

## 📅 时间计划

| Phase | 任务数 | 预计时间 | 开始日期 | 结束日期 |
|-------|--------|---------|---------|---------|
| **Phase 1** | 4 | 4 小时 | D1 | D1 |
| **Phase 2** | 5 | 19 小时 | D2 | D4 |
| **Phase 3** | 5 | 15 小时 | D5 | D6 |
| **Phase 4** | 5 | 19 小时 | D7 | D9 |
| **Phase 5** | 4 | 24 小时 | D10 | D11 |
| **Phase 6** | 2 | 6 小时 | D12 | D12 |

**总工期**: 12 个工作日  
**总任务数**: 25 个

---

## 🎯 里程碑

| 里程碑 | 预计日期 | 交付物 |
|--------|---------|--------|
| **M1: 准备工作完成** | D1 | 环境就绪 |
| **M2: 状态外移完成** | D4 | Fluss KV 集成 |
| **M3: Kafka Sink 完成** | D6 | 双 Topic 输出 |
| **M4: 重试机制完成** | D9 | RocketMQ Retry |
| **M5: 集成测试完成** | D11 | 测试报告 |
| **M6: v2.0.0 发布** | D12 | Production Release |

---

## 📊 任务优先级

### P0 (必须实现)
- Task 1.2: Fluss 依赖
- Task 2.1: Fluss Schema
- Task 2.2: Fluss KV Client
- Task 2.3: 重构 ProcessFunction
- Task 3.2: Kafka Sink
- Task 4.2: Retry Producer
- Task 4.3: Retry Consumer

### P1 (重要)
- Task 1.4: Docker 环境
- Task 2.4: TTL 清理
- Task 3.3: Success/Failed 路由
- Task 3.4: Exactly-Once
- Task 4.4: 死信队列
- Task 5.1: 集成测试

### P2 (优化)
- Task 2.5: 性能优化
- Task 3.5: 监控指标
- Task 4.5: 重试配置
- Task 5.2: 性能测试
- Task 5.3: 文档更新

---

## 🔗 依赖关系

```
Task 1.1 → Task 1.2 → Task 2.1 → Task 2.2 → Task 2.3 → Task 2.4
    ↓                                          ↓
Task 1.4 ──────────────────────────────────────┘
    ↓
Task 3.1 → Task 3.2 → Task 3.3 → Task 3.4 → Task 3.5
    ↓
Task 4.1 → Task 4.2 → Task 4.3 → Task 4.4 → Task 4.5
    ↓
Task 5.1 → Task 5.2 → Task 5.3 → Task 5.4
    ↓
Task 6.1 → Task 6.2
```

---

## 📝 开始实施

**准备就绪后，从 Task 1.1 开始逐个实施！**

**建议顺序**:
1. Phase 1 (准备工作) - 快速完成
2. Phase 2 (状态外移) - 核心功能
3. Phase 3 (Kafka Sink) - 核心功能
4. Phase 4 (重试机制) - 核心功能
5. Phase 5 (集成测试) - 质量保证
6. Phase 6 (发布) - 交付

---

**v2.0.0 规划完成！准备好开始实施了吗？** 🚀

**建议从 Task 1.1 开始：创建 v2.0.0 分支**
