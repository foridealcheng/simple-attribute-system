# v2.1 实现计划

**日期**: 2026-02-24  
**版本**: v2.1  
**状态**: 📝 规划中

---

## 🎯 实现目标

将现有的单体 AttributionJob 拆分为三个独立任务：
1. **Click Writer Job** - Click 数据写入 Fluss KV
2. **Attribution Engine Job** - 归因计算（查询 Fluss KV）
3. **Retry Consumer App** - 重试处理（已有基础）

---

## 📋 现有组件分析

### ✅ 已有组件（可复用）

| 组件 | 位置 | 状态 | 备注 |
|------|------|------|------|
| `FlussKVClient` | `client/` | ✅ 可用 | 当前使用本地缓存，支持 Fluss 切换 |
| `RocketMQRetrySink` | `sink/` | ✅ 可用 | 失败结果发送到 RocketMQ |
| `RocketMQRetryConsumer` | `consumer/` | ✅ 可用 | 重试消费者逻辑 |
| `RocketMQRetryConsumerApplication` | `consumer/` | ✅ 可用 | 独立进程启动类 |
| `KafkaAttributionSink` | `sink/` | ✅ 可用 | Kafka 结果输出 |
| `AttributionProcessFunction` | `flink/` | ⚠️ 需修改 | 需要改为查询 Fluss KV |

### 🔨 需要新建的组件

| 组件 | 描述 | 优先级 |
|------|------|--------|
| `ClickWriterJob` | Click 数据写入 Job 入口 | P0 |
| `FlussKVBatchWriterProcessFunction` | 批量写入 Fluss KV | P0 |
| `AttributionEngineJob` | 归因引擎 Job 入口（新） | P0 |
| `AttributionProcessFunction` (v2.1) | 查询 Fluss KV 的归因处理函数 | P0 |
| `KafkaSink` | 简化版 Kafka Sink | P1 |

### 🔄 需要修改的组件

| 组件 | 修改内容 | 优先级 |
|------|---------|--------|
| `AttributionJob` | 保留作为 v1.x 版本，或移除 | P2 |
| `AttributionProcessFunction` | 改为异步查询 Fluss KV | P0 |
| `FlussKVClient` | 增强异步支持 | P1 |

---

## 🚀 实现步骤

### Phase 1: Click Writer Job (预计 1 小时)

**目标**: 实现 Click 数据写入 Fluss KV 的独立 Job

**任务**:
1. [ ] 创建 `ClickWriterJob.java` - Job 入口
2. [ ] 创建 `FlussKVBatchWriterProcessFunction.java` - 批量写入处理器
3. [ ] 创建 `ClickValidator.java` - Click 数据验证
4. [ ] 配置文件：`click-writer.yaml`
5. [ ] 单元测试

**文件结构**:
```
src/main/java/com/attribution/
├── job/
│   └── ClickWriterJob.java (新建)
├── flink/
│   └── FlussKVBatchWriterProcessFunction.java (新建)
└── validator/
    └── ClickValidator.java (新建)
```

---

### Phase 2: Attribution Engine Job (预计 2 小时)

**目标**: 实现无状态归因引擎，查询 Fluss KV 进行归因计算

**任务**:
1. [ ] 创建 `AttributionEngineJob.java` - Job 入口（新建）
2. [ ] 修改 `AttributionProcessFunction.java` - 改为查询 Fluss KV
3. [ ] 增强 `FlussKVClient.java` - 支持异步查询
4. [ ] 创建 `AsyncFlussKVClient.java` - 异步客户端（可选）
5. [ ] 配置文件：`attribution-engine.yaml`
6. [ ] 单元测试

**文件结构**:
```
src/main/java/com/attribution/
├── job/
│   └── AttributionEngineJob.java (新建)
├── flink/
│   └── AttributionProcessFunction.java (修改)
└── client/
    └── FlussKVClient.java (增强)
```

---

### Phase 3: Retry Consumer 增强 (预计 1 小时)

**目标**: 完善重试消费者，支持重新查询 Fluss KV 进行归因

**任务**:
1. [ ] 检查 `RocketMQRetryConsumer.java` - 确认逻辑完整
2. [ ] 修改重试逻辑 - 查询 Fluss KV 重新归因
3. [ ] 配置文件：`retry-consumer.yaml`
4. [ ] 集成测试

**文件结构**:
```
src/main/java/com/attribution/
└── consumer/
    └── RocketMQRetryConsumer.java (修改)
```

---

### Phase 4: 配置与部署 (预计 1 小时)

**目标**: 准备部署配置和脚本

**任务**:
1. [ ] 创建配置文件目录 `config/`
2. [ ] 创建 `click-writer.yaml`
3. [ ] 创建 `attribution-engine.yaml`
4. [ ] 创建 `retry-consumer.yaml`
5. [ ] 创建启动脚本 `scripts/`
6. [ ] 更新 README.md

---

### Phase 5: 测试与验证 (预计 2 小时)

**目标**: 确保三个任务正常工作

**任务**:
1. [ ] 单元测试（所有新组件）
2. [ ] 集成测试（本地 Docker 环境）
3. [ ] 性能测试（吞吐量、延迟）
4. [ ] 故障恢复测试

---

## 📊 时间估算

| Phase | 内容 | 预计时间 |
|-------|------|---------|
| Phase 1 | Click Writer Job | 1 小时 |
| Phase 2 | Attribution Engine Job | 2 小时 |
| Phase 3 | Retry Consumer 增强 | 1 小时 |
| Phase 4 | 配置与部署 | 1 小时 |
| Phase 5 | 测试与验证 | 2 小时 |
| **总计** | | **7 小时** |

---

## 🎯 验收标准

### Click Writer Job
- [ ] 能从 Kafka 消费 Click 事件
- [ ] 能批量写入 Fluss KV（或本地缓存）
- [ ] 无效 Click 发送到 DLQ
- [ ] 吞吐量 > 10,000/s

### Attribution Engine Job
- [ ] 能从 Fluss 消费 Conversion 事件
- [ ] 能异步查询 Fluss KV 获取 Click
- [ ] 归因计算正确
- [ ] 结果输出到 Kafka
- [ ] 失败结果发送到 RocketMQ Retry

### Retry Consumer App
- [ ] 能消费 RocketMQ 重试消息
- [ ] 能重新查询 Fluss KV 进行归因
- [ ] 成功后输出到 Kafka
- [ ] 失败后根据重试次数处理

---

## ⚠️ 风险与缓解

### 风险 1: Fluss KV 客户端不支持异步
**影响**: 归因延迟增加  
**缓解**: 
- 短期：使用本地缓存模式
- 中期：实现异步 FlussKVClient
- 长期：等待 Fluss 官方支持

### 风险 2: 任务拆分后数据一致性
**影响**: Click 和 Conversion 时序问题  
**缓解**:
- Click Writer 优先部署
- 设置合理的归因窗口
- 监控 Click-Conversion 时间差

### 风险 3: 网络开销增加
**影响**: 延迟增加 10-50ms  
**缓解**:
- 部署在同一网络区域
- 使用连接池
- 批量查询优化

---

## 📝 下一步行动

1. **立即开始**: Phase 1 - Click Writer Job
2. **完成后 Review**: 检查代码质量和性能
3. **继续 Phase 2**: Attribution Engine Job
4. **最后测试**: 集成测试和性能测试

---

**准备就绪**: ✅  
**开始时间**: 2026-02-24 11:35  
**预计完成**: 2026-02-24 18:35

---

*让我们开始实现吧！*
