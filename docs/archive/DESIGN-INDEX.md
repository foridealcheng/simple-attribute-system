# SimpleAttributeSystem - 设计文档索引

> 所有设计文档的导航和版本追踪

**最后更新**: 2026-02-22 21:41  
**设计阶段**: ✅ 100% Complete  
**聚焦范围**: 数据归因核心（删除 API 设计）

---

## 📚 设计文档列表

| 编号 | 文档名称 | 状态 | 创建日期 | 最后更新 | 作者 |
|------|---------|------|---------|---------|------|
| 00 | [ARCHITECTURE.md](./ARCHITECTURE.md) | ✅ Approved | 2026-02-21 | 2026-02-22 | 🤖 AI |
| 01 | [DESIGN-01-DATA-INGESTION.md](./DESIGN-01-DATA-INGESTION.md) | ✅ Approved | 2026-02-21 | 2026-02-21 | 🤖 AI |
| 02 | [DESIGN-02-ATTRIBUTION-ENGINE.md](./DESIGN-02-ATTRIBUTION-ENGINE.md) | ✅ Approved | 2026-02-22 | 2026-02-22 | 🤖 AI |
| 03 | [DESIGN-03-RETRY-MECHANISM.md](./DESIGN-03-RETRY-MECHANISM.md) | ✅ Approved | 2026-02-22 | 2026-02-22 | 🤖 AI |
| 04 | [DESIGN-04-CONFIG-MANAGEMENT.md](./DESIGN-04-CONFIG-MANAGEMENT.md) | ✅ Approved | 2026-02-22 | 2026-02-22 | 🤖 AI |
| 05 | [DESIGN-05-DEPLOYMENT.md](./DESIGN-05-DEPLOYMENT.md) | ✅ Approved | 2026-02-22 | 2026-02-22 | 🤖 AI |
| 06 | [DESIGN-06-TEST-PLAN.md](./DESIGN-06-TEST-PLAN.md) | ✅ Approved | 2026-02-22 | 2026-02-22 | 🤖 AI |

---

## 📋 文档状态说明

| 状态 | 说明 |
|------|------|
| 📝 Draft | 草稿阶段，正在编写 |
| 🔍 Review | 审核中，等待反馈 |
| ✅ Approved | 已批准，可以实施 |
| 🚧 In Progress | 实施中 |
| ✅ Implemented | 已完成实施 |
| 🔄 Deprecated | 已废弃 |

---

## 📖 文档摘要

### ARCHITECTURE.md - 系统架构设计文档
**状态**: ✅ Approved  
**内容**: 完整的系统架构设计，包括：
- 系统概述和技术选型
- 整体架构图（Fluss + Flink + RocketMQ）
- 数据模型设计（Click/Conversion/Attribution）
- 核心处理逻辑（4 种归因模型）
- 部署架构和监控方案

**关键决策**:
- 使用 Apache Fluss 作为主消息中间件
- 使用 RocketMQ 作为重试队列（利用 delay consume）
- 支持 4 种归因模型：Last Click、Linear、Time Decay、Position Based
- 3 级重试策略：5 分钟 → 30 分钟 → 2 小时

---

### DESIGN-01-DATA-INGESTION.md - 数据接入与转换层设计
**状态**: ✅ Approved  
**内容**: 多源数据接入和标准化转换设计，包括：
- 多源接入架构（Kafka、RocketMQ、Fluss）
- 多格式支持（JSON、Protobuf、Avro）
- 标准化 Callback Data 格式定义
- 字段映射和转换引擎
- Flink 集成方案

**关键设计**:
1. **Source Adapter 模式**: 统一的数据源抽象接口
2. **Format Decoder 工厂**: 插件化的格式解码器
3. **Callback Data 标准**: 统一的转化数据格式（60+ 字段）
4. **Mapping Rules 配置**: YAML 配置化的字段映射
5. **数据质量评分**: 自动评估数据质量（0-100 分）

---

### DESIGN-02-ATTRIBUTION-ENGINE.md - 归因引擎详细设计
**状态**: ✅ Approved  
**审核通过**: 2026-02-22  
**审核意见**: 归因结果写入 Fluss 消息队列设计通过  
**性能优化**: 本地缓存 + 批量写入 + 异步 IO  
**内容**: 归因引擎函数级详细设计，包括：
- AttributionProcessFunction 完整实现（50+ 函数）
- 4 种归因模型策略实现（Last Click、Linear、Time Decay、Position Based）
- 状态管理函数设计
- 数据模型定义（ClickEvent、AttributedClick、AttributionResult 等）
- 指标上报器设计
- Flink 作业集成方案
- 异常处理设计

**关键函数**:
1. **processElement()**: 核心处理函数，分发 Click/Conversion 事件
2. **processClickEvent()**: 处理点击事件，存储到状态
3. **processConversionEvent()**: 处理转化事件，执行归因计算
4. **getValidClicks()**: 过滤归因窗口内的有效点击
5. **calculateAttribution()**: 各归因模型的核心计算函数
6. **emitSuccessResult()/emitRetryMessage()**: 结果输出函数

**函数数量**: 40+ 个详细设计的函数

---

### DESIGN-03-RETRY-MECHANISM.md - 重试机制详细设计
**状态**: ✅ Approved  
**审核通过**: 2026-02-22  
**审核意见**: RocketMQ 延迟消费重试机制设计通过  
**内容**: RocketMQ 延迟消费重试机制函数级详细设计，包括：
- RetryManager 重试策略管理（10+ 函数）
- RocketMQRetryProducer 生产者实现（15+ 函数）
- RetryConsumer 消费者实现（10+ 函数）
- RetryStateStore 状态存储接口与 Redis 实现
- Flink 集成（RetrySinkFunction、RetrySourceFunction）
- 指标上报器设计
- 配置参数定义

**关键函数**:
1. **shouldRetry()**: 判断是否应该重试
2. **calculateNextRetryTime()**: 计算下次重试时间
3. **sendRetryMessage()**: 发送重试消息到 RocketMQ
4. **sendWithDelayLevel()**: 发送带延迟级别的消息
5. **sendToDeadLetterQueue()**: 发送到死信队列
6. **consumeMessage()**: 消费重试消息
7. **handleRetryMessage()**: 处理并重试消息

**重试级别**:
- Level 1: 5 分钟延迟
- Level 2: 30 分钟延迟
- Level 3: 2 小时延迟

**函数数量**: 35+ 个详细设计的函数

---

## 🔄 变更历史

| 日期 | 文档 | 变更类型 | 描述 |
|------|------|---------|------|
| 2026-02-21 22:59 | DESIGN-01-DATA-INGESTION.md | New | 新增数据接入层设计文档 |
| 2026-02-21 22:59 | DESIGN-INDEX.md | Update | 创建设计文档索引 |
| 2026-02-21 22:46 | TASK-BOARD.md | Update | 更新任务看板，添加新任务 |
| 2026-02-21 22:46 | ARCHITECTURE.md | Approved | 架构设计文档审核通过 |

---

## 📌 设计文档摘要

### 核心设计（已批准）
| 编号 | 文档 | 大小 | 说明 |
|------|------|------|------|
| 00 | ARCHITECTURE.md | ~50KB | 系统架构 |
| 01 | DESIGN-01 | ~36KB | 数据接入 |
| 02 | DESIGN-02 | ~63KB | 归因引擎 |
| 03 | DESIGN-03 | ~54KB | 重试机制 |
| 04 | DESIGN-04 | ~15KB | 配置管理 |
| 05 | DESIGN-05 | ~8KB | 部署设计 |
| 06 | DESIGN-06 | ~22KB | 测试计划 |

**总计**: 7 个文档，248KB+，110+ 函数

---

## 🔗 相关文档

- [任务看板](./TASK-BOARD.md) - 项目任务追踪
- [项目 README](../README.md) - 项目概述

---

**维护者**: 🤖 AI Assistant  
**审核者**: 👤 User
