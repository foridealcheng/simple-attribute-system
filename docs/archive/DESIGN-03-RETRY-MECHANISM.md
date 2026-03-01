# 设计文档 03 - 重试机制详细设计

> RocketMQ 延迟消费重试机制函数级详细设计

**版本**: v1.0  
**创建时间**: 2026-02-22  
**状态**: 📝 Draft  
**关联任务**: T008

---

## 1. 概述

### 1.1 设计目标
本设计文档详细定义重试机制中**每一个函数**的实现细节，包括：
- RocketMQ 延迟消息发送与消费
- 重试策略管理
- 死信队列处理
- 重试状态追踪

### 1.2 核心组件
```
┌─────────────────────────────────────────────────────────────────┐
│                    Retry Mechanism                               │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              RetryManager                                 │  │
│  │  - shouldRetry()                                         │  │
│  │  - calculateNextRetryTime()                              │  │
│  │  - getRetryLevel()                                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │          RocketMQRetryProducer                            │  │
│  │  - sendRetryMessage()                                    │  │
│  │  - sendWithDelayLevel()                                  │  │
│  │  - sendToDeadLetterQueue()                               │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │          RetryConsumer                                    │  │
│  │  - consumeRetryMessage()                                 │  │
│  │  - processRetry()                                        │  │
│  │  - reInjectToFlux()                                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │          RetryStateStore                                  │  │
│  │  - getRetryHistory()                                     │  │
│  │  - updateRetryCount()                                    │  │
│  │  - markAsDeadLettered()                                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 重试流程
```
┌─────────────────────────────────────────────────────────────────┐
│                    Retry Flow                                    │
│                                                                  │
│  [Attribution Engine]                                           │
│         │                                                        │
│         │ Process Failed                                         │
│         ▼                                                        │
│  ┌─────────────────┐                                            │
│  │  RetryManager   │─────► shouldRetry? ────No──► Dead Letter   │
│  └────────┬────────┘                      │                      │
│           │                               │                      │
│           │ Yes                           │                      │
│           ▼                               │                      │
│  ┌─────────────────┐                     │                      │
│  │ RetryProducer   │                     │                      │
│  │ - Send to RMQ   │                     │                      │
│  │ - Delay Level N │                     │                      │
│  └────────┬────────┘                     │                      │
│           │                               │                      │
│           │ RocketMQ Delay Consume        │                      │
│           │ (5min / 30min / 2h)           │                      │
│           ▼                               │                      │
│  ┌─────────────────┐                     │                      │
│  │ RetryConsumer   │                     │                      │
│  │ - Receive       │                     │                      │
│  │ - Re-inject     │─────────────────────┘                      │
│  └─────────────────┘                                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 重试策略管理

### 2.1 RetryManager 类

#### 2.1.1 类定义
```java
/**
 * 重试管理器
 * 
 * 职责:
 * - 判断是否应该重试
 * - 计算下次重试时间
 * - 管理重试级别
 * - 追踪重试历史
 */
public class RetryManager {
    
    /**
     * 重试配置
     */
    private final RetryConfig config;
    
    /**
     * 重试级别配置
     * Level -> Delay Minutes
     */
    private final Map<Integer, Integer> delayLevelMap;
    
    /**
     * 重试状态存储
     */
    private final RetryStateStore stateStore;
    
    /**
     * 指标上报器
     */
    private final RetryMetricsReporter metricsReporter;
    
    /**
     * 构造函数
     * 
     * @param config 重试配置
     * @param stateStore 重试状态存储
     * @param metricsReporter 指标上报器
     */
    public RetryManager(
        RetryConfig config,
        RetryStateStore stateStore,
        RetryMetricsReporter metricsReporter
    ) {
        this.config = config;
        this.stateStore = stateStore;
        this.metricsReporter = metricsReporter;
        
        // 初始化延迟级别映射
        this.delayLevelMap = initializeDelayLevelMap();
    }
    
    /**
     * 初始化延迟级别映射
     * 
     * 默认配置:
     * - Level 1: 5 分钟
     * - Level 2: 30 分钟
     * - Level 3: 2 小时
     * 
     * @return 延迟级别映射
     */
    private Map<Integer, Integer> initializeDelayLevelMap() {
        Map<Integer, Integer> map = new HashMap<>();
        
        if (config.getRetryLevels() != null) {
            for (RetryLevelConfig level : config.getRetryLevels()) {
                map.put(level.getLevel(), level.getDelayMinutes());
            }
        } else {
            // 默认配置
            map.put(1, 5);
            map.put(2, 30);
            map.put(3, 120);
        }
        
        return Collections.unmodifiableMap(map);
    }
    
    // ========== 核心函数 ==========
    
    /**
     * 判断是否应该重试
     * 
     * 判断逻辑:
     * 1. 检查重试是否启用
     * 2. 检查当前重试次数是否超过最大限制
     * 3. 检查失败类型是否可重试
     * 
     * @param retryMessage 重试消息
     * @return true 如果应该重试
     */
    public boolean shouldRetry(RetryMessage retryMessage) {
        // 1. 检查重试是否启用
        if (!config.isEnabled()) {
            log.debug("Retry is disabled in configuration");
            metricsReporter.recordRetryDisabled();
            return false;
        }
        
        // 2. 检查重试次数
        int currentRetryCount = retryMessage.getRetryCount();
        int maxRetries = retryMessage.getMaxRetries() != null 
            ? retryMessage.getMaxRetries() 
            : config.getMaxAttempts();
        
        if (currentRetryCount >= maxRetries) {
            log.info("Max retry attempts reached: retryId={}, count={}", 
                retryMessage.getRetryId(), currentRetryCount);
            metricsReporter.recordMaxRetriesReached();
            return false;
        }
        
        // 3. 检查失败类型
        String failureReason = retryMessage.getFailureReason();
        if (isNonRetryableFailure(failureReason)) {
            log.info("Non-retryable failure: retryId={}, reason={}", 
                retryMessage.getRetryId(), failureReason);
            metricsReporter.recordNonRetryableFailure();
            return false;
        }
        
        return true;
    }
    
    /**
     * 判断失败类型是否不可重试
     * 
     * 不可重试的失败类型:
     * - DATA_FORMAT_ERROR: 数据格式错误
     * - MISSING_REQUIRED_FIELD: 缺失必填字段
     * - INVALID_BUSINESS_RULE: 业务规则无效
     * 
     * @param failureReason 失败原因
     * @return true 如果不可重试
     */
    private boolean isNonRetryableFailure(String failureReason) {
        if (failureReason == null) {
            return false;
        }
        
        String reason = failureReason.toUpperCase();
        return reason.contains("DATA_FORMAT_ERROR") ||
               reason.contains("MISSING_REQUIRED_FIELD") ||
               reason.contains("INVALID_BUSINESS_RULE") ||
               reason.contains("PERMANENT_FAILURE");
    }
    
    /**
     * 计算下次重试时间
     * 
     * 根据当前重试级别计算延迟时间
     * 
     * @param retryMessage 重试消息
     * @return 下次重试时间戳（毫秒）
     */
    public long calculateNextRetryTime(RetryMessage retryMessage) {
        int currentLevel = retryMessage.getRetryLevel() != null 
            ? retryMessage.getRetryLevel() 
            : 1;
        
        // 获取当前级别的延迟分钟数
        int delayMinutes = delayLevelMap.getOrDefault(currentLevel, 5);
        
        // 计算下次重试时间
        long nextRetryTime = System.currentTimeMillis() + (delayMinutes * 60 * 1000);
        
        log.debug("Calculated next retry time: retryId={}, level={}, delayMinutes={}, nextTime={}", 
            retryMessage.getRetryId(), currentLevel, delayMinutes, nextRetryTime);
        
        return nextRetryTime;
    }
    
    /**
     * 获取下次重试级别
     * 
     * 升级规则:
     * - 如果当前级别 < 最大级别，级别 +1
     * - 否则保持当前级别
     * 
     * @param retryMessage 重试消息
     * @return 下次重试级别
     */
    public int getNextRetryLevel(RetryMessage retryMessage) {
        int currentLevel = retryMessage.getRetryLevel() != null 
            ? retryMessage.getRetryLevel() 
            : 1;
        
        int maxLevel = delayLevelMap.keySet().stream()
            .max(Integer::compareTo)
            .orElse(3);
        
        int nextLevel = Math.min(currentLevel + 1, maxLevel);
        
        log.debug("Next retry level: retryId={}, current={}, next={}", 
            retryMessage.getRetryId(), currentLevel, nextLevel);
        
        return nextLevel;
    }
    
    /**
     * 准备重试消息
     * 
     * 更新重试消息的重试计数、级别、时间等信息
     * 
     * @param originalMessage 原始重试消息
     * @param errorMessage 新的错误信息（如果有）
     * @return 更新后的重试消息
     */
    public RetryMessage prepareRetryMessage(
        RetryMessage originalMessage,
        String errorMessage
    ) {
        // 增加重试计数
        int newRetryCount = originalMessage.getRetryCount() + 1;
        
        // 获取下次重试级别
        int nextLevel = getNextRetryLevel(originalMessage);
        
        // 计算下次重试时间
        long nextRetryTime = calculateNextRetryTime(
            originalMessage.toBuilder()
                .retryLevel(nextLevel)
                .build()
        );
        
        // 构建重试历史记录
        RetryAttempt newAttempt = RetryAttempt.builder()
            .attemptTime(System.currentTimeMillis())
            .failureReason(errorMessage != null ? errorMessage : originalMessage.getFailureReason())
            .retryLevel(originalMessage.getRetryLevel())
            .build();
        
        List<RetryAttempt> retryHistory = new ArrayList<>();
        if (originalMessage.getRetryHistory() != null) {
            retryHistory.addAll(originalMessage.getRetryHistory());
        }
        retryHistory.add(newAttempt);
        
        // 构建新的重试消息
        return RetryMessage.builder()
            .retryId(originalMessage.getRetryId())
            .originalEventType(originalMessage.getOriginalEventType())
            .originalEvent(originalMessage.getOriginalEvent())
            .failureReason(errorMessage)
            .failureTimestamp(System.currentTimeMillis())
            .retryCount(newRetryCount)
            .retryLevel(nextLevel)
            .maxRetries(originalMessage.getMaxRetries())
            .nextRetryTime(nextRetryTime)
            .retryHistory(retryHistory)
            .metadata(originalMessage.getMetadata())
            .build();
    }
    
    /**
     * 获取 RocketMQ 延迟级别
     * 
     * RocketMQ 延迟级别映射:
     * Level 1 -> RocketMQ Level 5 (5 分钟)
     * Level 2 -> RocketMQ Level 10 (30 分钟)
     * Level 3 -> RocketMQ Level 13 (2 小时)
     * 
     * @param retryLevel 重试级别
     * @return RocketMQ 延迟级别
     */
    public int getRocketMQDelayLevel(int retryLevel) {
        // RocketMQ 预定义的延迟级别
        Map<Integer, Integer> rocketMQLevelMap = Map.of(
            1, 5,   // 5 分钟
            2, 10,  // 30 分钟
            3, 13   // 2 小时
        );
        
        return rocketMQLevelMap.getOrDefault(retryLevel, 5);
    }
}
```

---

### 2.2 重试配置类

#### 2.2.1 RetryConfig
```java
/**
 * 重试配置
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RetryConfig {
    
    /**
     * 是否启用重试
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxAttempts = 3;
    
    /**
     * 重试级别配置
     */
    private List<RetryLevelConfig> retryLevels;
    
    /**
     * 死信队列配置
     */
    private DeadLetterConfig deadLetter;
    
    /**
     * RocketMQ 配置
     */
    private RocketMQConfig rocketMQ;
}

/**
 * 重试级别配置
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RetryLevelConfig {
    
    /**
     * 级别（1, 2, 3...）
     */
    private int level;
    
    /**
     * 延迟分钟数
     */
    private int delayMinutes;
    
    /**
     * 该级别最大重试次数
     */
    @Builder.Default
    private int maxAttemptsAtLevel = 2;
}

/**
 * 死信队列配置
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeadLetterConfig {
    
    /**
     * 是否启用死信队列
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 死信队列 Topic
     */
    private String topic;
    
    /**
     * 死信队列 Producer Group
     */
    private String producerGroup;
    
    /**
     * 是否发送告警
     */
    @Builder.Default
    private boolean sendAlert = true;
}

/**
 * RocketMQ 配置
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RocketMQConfig {
    
    /**
     * Name Server 地址
     */
    private String nameServer;
    
    /**
     * Producer Group
     */
    private String producerGroup;
    
    /**
     * Consumer Group
     */
    private String consumerGroup;
    
    /**
     * 重试 Topic
     */
    private String retryTopic;
    
    /**
     * Access Key
     */
    private String accessKey;
    
    /**
     * Secret Key
     */
    private String secretKey;
}
```

---

## 3. RocketMQ 重试生产者

### 3.1 RocketMQRetryProducer 类

#### 3.1.1 类定义
```java
/**
 * RocketMQ 重试消息生产者
 * 
 * 职责:
 * - 发送重试消息到 RocketMQ
 * - 支持延迟消息
 * - 发送死信消息
 * - 事务消息支持（可选）
 */
public class RocketMQRetryProducer {
    
    private static final Logger log = LoggerFactory.getLogger(RocketMQRetryProducer.class);
    
    /**
     * RocketMQ Producer
     */
    private DefaultMQProducer producer;
    
    /**
     * 重试配置
     */
    private final RetryConfig config;
    
    /**
     * 重试管理器
     */
    private final RetryManager retryManager;
    
    /**
     * 指标上报器
     */
    private final RetryMetricsReporter metricsReporter;
    
    /**
     * 构造函数
     */
    public RocketMQRetryProducer(
        RetryConfig config,
        RetryManager retryManager,
        RetryMetricsReporter metricsReporter
    ) {
        this.config = config;
        this.retryManager = retryManager;
        this.metricsReporter = metricsReporter;
    }
    
    // ========== 生命周期函数 ==========
    
    /**
     * 初始化生产者
     * 
     * 配置 RocketMQ Producer 参数并启动
     * 
     * @throws Exception 初始化失败异常
     */
    public void init() throws Exception {
        RocketMQConfig rmqConfig = config.getRocketMQ();
        
        // 创建 Producer 实例
        producer = new DefaultMQProducer(rmqConfig.getProducerGroup());
        
        // 配置 Name Server
        producer.setNamesrvAddr(rmqConfig.getNameServer());
        
        // 配置重试次数
        producer.setRetryTimesWhenSendFailed(3);
        producer.setRetryTimesWhenSendAsyncFailed(3);
        
        // 配置超时时间
        producer.setSendMsgTimeout(3000);
        
        // 配置消息体大小限制（4MB）
        producer.setMaxMessageSize(4 * 1024 * 1024);
        
        // 配置 VIP 通道（避免 VIP 通道问题）
        producer.setVipChannelEnabled(false);
        
        // 配置 ACL（如果提供了 AccessKey）
        if (rmqConfig.getAccessKey() != null && rmqConfig.getSecretKey() != null) {
            producer.setVipChannelEnabled(false);
            // 设置 ACL 凭证
            // producer.setAclRpcHook(...)
        }
        
        // 启动 Producer
        producer.start();
        
        log.info("RocketMQ Retry Producer started. NameServer={}, ProducerGroup={}", 
            rmqConfig.getNameServer(), rmqConfig.getProducerGroup());
        
        metricsReporter.recordProducerStarted();
    }
    
    /**
     * 关闭生产者
     */
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
            log.info("RocketMQ Retry Producer shutdown");
        }
    }
    
    // ========== 核心发送函数 ==========
    
    /**
     * 发送重试消息
     * 
     * 流程:
     * 1. 判断是否应该重试
     * 2. 准备重试消息（更新计数、级别等）
     * 3. 获取 RocketMQ 延迟级别
     * 4. 发送延迟消息
     * 
     * @param originalMessage 原始重试消息
     * @param errorMessage 错误信息
     * @return SendResult 发送结果
     */
    public SendResult sendRetryMessage(
        RetryMessage originalMessage,
        String errorMessage
    ) {
        try {
            // 1. 判断是否应该重试
            if (!retryManager.shouldRetry(originalMessage)) {
                log.info("Should not retry, sending to DLQ: retryId={}", 
                    originalMessage.getRetryId());
                return sendToDeadLetterQueue(originalMessage, errorMessage);
            }
            
            // 2. 准备重试消息
            RetryMessage retryMessage = retryManager.prepareRetryMessage(
                originalMessage,
                errorMessage
            );
            
            // 3. 获取 RocketMQ 延迟级别
            int rocketMQDelayLevel = retryManager.getRocketMQDelayLevel(
                retryMessage.getRetryLevel()
            );
            
            // 4. 发送延迟消息
            return sendWithDelayLevel(retryMessage, rocketMQDelayLevel);
            
        } catch (Exception e) {
            log.error("Failed to send retry message: retryId={}", 
                originalMessage.getRetryId(), e);
            metricsReporter.recordSendFailure();
            
            // 发送失败，直接发送到死信队列
            return sendToDeadLetterQueue(originalMessage, e.getMessage());
        }
    }
    
    /**
     * 发送带延迟级别的消息
     * 
     * @param retryMessage 重试消息
     * @param delayLevel RocketMQ 延迟级别
     * @return SendResult 发送结果
     */
    private SendResult sendWithDelayLevel(
        RetryMessage retryMessage,
        int delayLevel
    ) throws Exception {
        // 构建 RocketMQ 消息
        org.apache.rocketmq.common.message.Message rocketMQMessage = 
            buildRocketMQMessage(retryMessage);
        
        // 设置延迟级别
        rocketMQMessage.setDelayTimeLevel(delayLevel);
        
        // 设置消息 Key（用于去重和追踪）
        rocketMQMessage.setKeys(retryMessage.getRetryId());
        
        // 设置业务标签
        rocketMQMessage.setTags("RETRY_LEVEL_" + retryMessage.getRetryLevel());
        
        // 发送消息（同步）
        SendResult sendResult = producer.send(rocketMQMessage);
        
        log.info("Retry message sent: retryId={}, level={}, delayLevel={}, msgId={}", 
            retryMessage.getRetryId(), 
            retryMessage.getRetryLevel(),
            delayLevel,
            sendResult.getMsgId());
        
        metricsReporter.recordRetryMessageSent(retryMessage.getRetryLevel());
        
        return sendResult;
    }
    
    /**
     * 构建 RocketMQ 消息
     * 
     * @param retryMessage 重试消息
     * @return RocketMQ Message
     */
    private org.apache.rocketmq.common.message.Message buildRocketMQMessage(
        RetryMessage retryMessage
    ) {
        try {
            // 序列化重试消息为 JSON
            String messageBody = JsonUtil.toJson(retryMessage);
            
            // 创建消息
            org.apache.rocketmq.common.message.Message message = 
                new org.apache.rocketmq.common.message.Message(
                    config.getRocketMQ().getRetryTopic(),
                    messageBody.getBytes(StandardCharsets.UTF_8)
                );
            
            // 添加扩展属性
            message.putUserProperty("retry_id", retryMessage.getRetryId());
            message.putUserProperty("retry_count", String.valueOf(retryMessage.getRetryCount()));
            message.putUserProperty("retry_level", String.valueOf(retryMessage.getRetryLevel()));
            message.putUserProperty("original_event_type", retryMessage.getOriginalEventType());
            message.putUserProperty("business_key", retryMessage.getMetadata().getBusinessKey());
            
            return message;
            
        } catch (Exception e) {
            throw new RetryMessageBuildException("Failed to build RocketMQ message", e);
        }
    }
    
    /**
     * 发送到死信队列
     * 
     * @param retryMessage 重试消息
     * @param errorMessage 最终错误信息
     * @return SendResult 发送结果
     */
    public SendResult sendToDeadLetterQueue(
        RetryMessage retryMessage,
        String errorMessage
    ) {
        if (!config.getDeadLetter().isEnabled()) {
            log.warn("Dead letter queue is disabled, dropping message: retryId={}", 
                retryMessage.getRetryId());
            metricsReporter.recordDeadLetterDisabled();
            return null;
        }
        
        try {
            // 更新重试消息状态
            RetryMessage dlqMessage = retryMessage.toBuilder()
                .failureReason(errorMessage)
                .failureTimestamp(System.currentTimeMillis())
                .build();
            
            // 构建 DLQ 消息
            org.apache.rocketmq.common.message.Message dlqRocketMQMessage = 
                buildDeadLetterMessage(dlqMessage);
            
            // 发送到死信队列 Topic
            dlqRocketMQMessage.setTopic(config.getDeadLetter().getTopic());
            dlqRocketMQMessage.setTags("DEAD_LETTER");
            dlqRocketMQMessage.setKeys(dlqMessage.getRetryId());
            
            SendResult sendResult = producer.send(dlqRocketMQMessage);
            
            log.warn("Message sent to DLQ: retryId={}, reason={}, msgId={}", 
                dlqMessage.getRetryId(),
                errorMessage,
                sendResult.getMsgId());
            
            metricsReporter.recordDeadLetterSent();
            
            // 发送告警（如果配置）
            if (config.getDeadLetter().isSendAlert()) {
                sendDeadLetterAlert(dlqMessage, errorMessage);
            }
            
            return sendResult;
            
        } catch (Exception e) {
            log.error("Failed to send to DLQ: retryId={}", retryMessage.getRetryId(), e);
            metricsReporter.recordDeadLetterSendFailure();
            return null;
        }
    }
    
    /**
     * 构建死信消息
     */
    private org.apache.rocketmq.common.message.Message buildDeadLetterMessage(
        RetryMessage retryMessage
    ) {
        try {
            String messageBody = JsonUtil.toJson(retryMessage);
            
            org.apache.rocketmq.common.message.Message message = 
                new org.apache.rocketmq.common.message.Message(
                    config.getDeadLetter().getTopic(),
                    messageBody.getBytes(StandardCharsets.UTF_8)
                );
            
            message.putUserProperty("retry_id", retryMessage.getRetryId());
            message.putUserProperty("retry_count", String.valueOf(retryMessage.getRetryCount()));
            message.putUserProperty("final_failure_reason", retryMessage.getFailureReason());
            message.putUserProperty("business_key", retryMessage.getMetadata().getBusinessKey());
            
            return message;
            
        } catch (Exception e) {
            throw new RetryMessageBuildException("Failed to build DLQ message", e);
        }
    }
    
    /**
     * 发送死信告警
     */
    private void sendDeadLetterAlert(RetryMessage retryMessage, String errorMessage) {
        try {
            // 这里可以集成告警系统（如钉钉、企业微信、PagerDuty 等）
            log.warn("DLQ Alert: retryId={}, businessKey={}, reason={}", 
                retryMessage.getRetryId(),
                retryMessage.getMetadata().getBusinessKey(),
                errorMessage);
            
            metricsReporter.recordDeadLetterAlertSent();
            
        } catch (Exception e) {
            log.error("Failed to send DLQ alert", e);
        }
    }
    
    // ========== 异步发送函数 ==========
    
    /**
     * 异步发送重试消息
     * 
     * @param originalMessage 原始重试消息
     * @param errorMessage 错误信息
     * @param callback 发送回调
     */
    public void sendRetryMessageAsync(
        RetryMessage originalMessage,
        String errorMessage,
        SendCallback callback
    ) {
        try {
            if (!retryManager.shouldRetry(originalMessage)) {
                sendToDeadLetterQueueAsync(originalMessage, errorMessage, callback);
                return;
            }
            
            RetryMessage retryMessage = retryManager.prepareRetryMessage(
                originalMessage,
                errorMessage
            );
            
            int rocketMQDelayLevel = retryManager.getRocketMQDelayLevel(
                retryMessage.getRetryLevel()
            );
            
            sendWithDelayLevelAsync(retryMessage, rocketMQDelayLevel, callback);
            
        } catch (Exception e) {
            log.error("Failed to send retry message async: retryId={}", 
                originalMessage.getRetryId(), e);
            if (callback != null) {
                callback.onException(e);
            }
        }
    }
    
    /**
     * 异步发送带延迟级别的消息
     */
    private void sendWithDelayLevelAsync(
        RetryMessage retryMessage,
        int delayLevel,
        SendCallback callback
    ) throws Exception {
        org.apache.rocketmq.common.message.Message rocketMQMessage = 
            buildRocketMQMessage(retryMessage);
        
        rocketMQMessage.setDelayTimeLevel(delayLevel);
        rocketMQMessage.setKeys(retryMessage.getRetryId());
        rocketMQMessage.setTags("RETRY_LEVEL_" + retryMessage.getRetryLevel());
        
        producer.send(rocketMQMessage, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("Retry message sent async: retryId={}, msgId={}", 
                    retryMessage.getRetryId(), sendResult.getMsgId());
                metricsReporter.recordRetryMessageSent(retryMessage.getRetryLevel());
                
                if (callback != null) {
                    callback.onSuccess(sendResult);
                }
            }
            
            @Override
            public void onException(Throwable e) {
                log.error("Failed to send retry message async: retryId={}", 
                    retryMessage.getRetryId(), e);
                metricsReporter.recordSendFailure();
                
                if (callback != null) {
                    callback.onException(e);
                }
            }
        });
    }
    
    /**
     * 异步发送到死信队列
     */
    private void sendToDeadLetterQueueAsync(
        RetryMessage retryMessage,
        String errorMessage,
        SendCallback callback
    ) {
        try {
            SendResult result = sendToDeadLetterQueue(retryMessage, errorMessage);
            if (callback != null) {
                callback.onSuccess(result);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onException(e);
            }
        }
    }
}
```

---

## 4. RocketMQ 重试消费者

### 4.1 RetryConsumer 类

#### 4.1.1 类定义
```java
/**
 * RocketMQ 重试消息消费者
 * 
 * 职责:
 * - 消费 RocketMQ 重试消息
 * - 重新注入到 Flink 处理流程
 * - 处理消费失败
 */
public class RetryConsumer {
    
    private static final Logger log = LoggerFactory.getLogger(RetryConsumer.class);
    
    /**
     * RocketMQ Consumer
     */
    private DefaultMQPushConsumer consumer;
    
    /**
     * 重试配置
     */
    private final RetryConfig config;
    
    /**
     * 消息处理器
     */
    private final RetryMessageHandler messageHandler;
    
    /**
     * 指标上报器
     */
    private final RetryMetricsReporter metricsReporter;
    
    /**
     * 构造函数
     */
    public RetryConsumer(
        RetryConfig config,
        RetryMessageHandler messageHandler,
        RetryMetricsReporter metricsReporter
    ) {
        this.config = config;
        this.messageHandler = messageHandler;
        this.metricsReporter = metricsReporter;
    }
    
    // ========== 生命周期函数 ==========
    
    /**
     * 初始化消费者
     */
    public void init() throws Exception {
        RocketMQConfig rmqConfig = config.getRocketMQ();
        
        // 创建 Consumer 实例
        consumer = new DefaultMQPushConsumer(rmqConfig.getConsumerGroup());
        
        // 配置 Name Server
        consumer.setNamesrvAddr(rmqConfig.getNameServer());
        
        // 配置并发消费
        consumer.setConsumeThreadMin(20);
        consumer.setConsumeThreadMax(40);
        
        // 配置每次消费消息数量
        consumer.setConsumeMessageBatchMaxSize(32);
        
        // 配置消费失败重试
        consumer.setMaxReconsumeTimes(3);
        
        // 配置消费超时
        consumer.setConsumeTimeout(15);
        
        // 配置 VIP 通道
        consumer.setVipChannelEnabled(false);
        
        // 配置 ACL
        if (rmqConfig.getAccessKey() != null && rmqConfig.getSecretKey() != null) {
            consumer.setVipChannelEnabled(false);
        }
        
        // 注册消息监听器
        consumer.registerMessageListener((MessageListenerConcurrently) this::consumeMessage);
        
        // 订阅重试 Topic
        consumer.subscribe(rmqConfig.getRetryTopic(), "*");
        
        // 启动 Consumer
        consumer.start();
        
        log.info("RocketMQ Retry Consumer started. NameServer={}, ConsumerGroup={}", 
            rmqConfig.getNameServer(), rmqConfig.getConsumerGroup());
        
        metricsReporter.recordConsumerStarted();
    }
    
    /**
     * 关闭消费者
     */
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("RocketMQ Retry Consumer shutdown");
        }
    }
    
    // ========== 核心消费函数 ==========
    
    /**
     * 消费消息（并发模式）
     * 
     * @param messages 消息列表
     * @param context 消费上下文
     * @return 消费结果
     */
    private ConsumeConcurrentlyStatus consumeMessage(
        List<MessageExt> messages,
        ConsumeConcurrentlyContext context
    ) {
        for (MessageExt message : messages) {
            try {
                consumeSingleMessage(message);
            } catch (Exception e) {
                log.error("Failed to consume retry message: msgId={}", 
                    message.getMsgId(), e);
                metricsReporter.recordConsumeFailure();
                
                // 返回重试，让 RocketMQ 重新投递
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
    
    /**
     * 消费单条消息
     * 
     * @param message RocketMQ 消息
     */
    private void consumeSingleMessage(MessageExt message) throws Exception {
        // 1. 解析消息体
        RetryMessage retryMessage = parseMessage(message);
        
        // 2. 记录消费指标
        metricsReporter.recordMessageConsumed();
        
        // 3. 处理重试消息
        boolean success = messageHandler.handleRetryMessage(retryMessage);
        
        if (success) {
            log.info("Retry message processed successfully: retryId={}", 
                retryMessage.getRetryId());
            metricsReporter.recordRetryProcessed();
        } else {
            log.warn("Retry message processing failed: retryId={}", 
                retryMessage.getRetryId());
            metricsReporter.recordRetryProcessingFailed();
            
            // 处理失败，抛出异常触发 RocketMQ 重试
            throw new RetryProcessingException(
                "Failed to process retry message: " + retryMessage.getRetryId()
            );
        }
    }
    
    /**
     * 解析 RocketMQ 消息
     * 
     * @param message RocketMQ 消息
     * @return 重试消息对象
     */
    private RetryMessage parseMessage(MessageExt message) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            RetryMessage retryMessage = JsonUtil.fromJson(body, RetryMessage.class);
            
            // 验证消息完整性
            if (retryMessage.getRetryId() == null) {
                throw new InvalidRetryMessageException("Missing retry_id in message");
            }
            
            return retryMessage;
            
        } catch (Exception e) {
            throw new RetryMessageParseException(
                "Failed to parse retry message: msgId=" + message.getMsgId(),
                e
            );
        }
    }
}
```

---

### 4.2 重试消息处理器

#### 4.2.1 RetryMessageHandler 接口
```java
/**
 * 重试消息处理器接口
 */
public interface RetryMessageHandler {
    
    /**
     * 处理重试消息
     * 
     * @param retryMessage 重试消息
     * @return true 如果处理成功
     */
    boolean handleRetryMessage(RetryMessage retryMessage);
}
```

#### 4.2.2 FlinkReInjectHandler 实现
```java
/**
 * Flink 重新注入处理器
 * 
 * 将重试消息重新注入到 Flink 处理流程
 */
public class FlinkReInjectHandler implements RetryMessageHandler {
    
    private static final Logger log = LoggerFactory.getLogger(FlinkReInjectHandler.class);
    
    /**
     * Flink 数据注入器
     */
    private final FlinkDataInjector injector;
    
    /**
     * 重试状态存储
     */
    private final RetryStateStore stateStore;
    
    public FlinkReInjectHandler(
        FlinkDataInjector injector,
        RetryStateStore stateStore
    ) {
        this.injector = injector;
        this.stateStore = stateStore;
    }
    
    @Override
    public boolean handleRetryMessage(RetryMessage retryMessage) {
        try {
            // 1. 更新重试状态
            stateStore.updateRetryCount(retryMessage.getRetryId());
            
            // 2. 反序列化原始事件
            CallbackData callbackData = deserializeOriginalEvent(retryMessage);
            
            // 3. 重新注入到 Flink
            injector.inject(callbackData);
            
            log.info("Retry message re-injected to Flink: retryId={}, eventType={}", 
                retryMessage.getRetryId(),
                retryMessage.getOriginalEventType());
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to re-inject retry message: retryId={}", 
                retryMessage.getRetryId(), e);
            return false;
        }
    }
    
    /**
     * 反序列化原始事件
     */
    private CallbackData deserializeOriginalEvent(RetryMessage retryMessage) {
        try {
            return JsonUtil.fromJson(
                retryMessage.getOriginalEvent(),
                CallbackData.class
            );
        } catch (Exception e) {
            throw new DeserializationException(
                "Failed to deserialize original event: " + retryMessage.getRetryId(),
                e
            );
        }
    }
}
```

---

## 5. 重试状态存储

### 5.1 RetryStateStore 接口
```java
/**
 * 重试状态存储接口
 */
public interface RetryStateStore {
    
    /**
     * 获取重试历史
     * 
     * @param retryId 重试 ID
     * @return 重试历史记录
     */
    RetryHistory getRetryHistory(String retryId);
    
    /**
     * 更新重试计数
     * 
     * @param retryId 重试 ID
     */
    void updateRetryCount(String retryId);
    
    /**
     * 标记为已发送到死信队列
     * 
     * @param retryId 重试 ID
     */
    void markAsDeadLettered(String retryId);
    
    /**
     * 清理过期状态
     * 
     * @param olderThan 清理早于此时间的状态
     */
    void cleanupExpiredState(long olderThan);
}
```

### 5.2 RedisRetryStateStore 实现
```java
/**
 * 基于 Redis 的重试状态存储实现
 */
public class RedisRetryStateStore implements RetryStateStore {
    
    private static final String KEY_PREFIX = "retry:state:";
    private static final String HISTORY_PREFIX = "retry:history:";
    
    private final JedisPool jedisPool;
    private final long stateTtlSeconds;
    
    public RedisRetryStateStore(JedisPool jedisPool, long stateTtlSeconds) {
        this.jedisPool = jedisPool;
        this.stateTtlSeconds = stateTtlSeconds;
    }
    
    @Override
    public RetryHistory getRetryHistory(String retryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = HISTORY_PREFIX + retryId;
            String json = jedis.get(key);
            
            if (json == null) {
                return RetryHistory.builder()
                    .retryId(retryId)
                    .attempts(new ArrayList<>())
                    .build();
            }
            
            return JsonUtil.fromJson(json, RetryHistory.class);
        }
    }
    
    @Override
    public void updateRetryCount(String retryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = KEY_PREFIX + retryId;
            
            // 递增重试计数
            jedis.incr(key);
            
            // 设置 TTL
            jedis.expire(key, stateTtlSeconds);
        }
    }
    
    @Override
    public void markAsDeadLettered(String retryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = KEY_PREFIX + retryId;
            jedis.setex(key + ":dlq", stateTtlSeconds, "true");
        }
    }
    
    @Override
    public void cleanupExpiredState(long olderThan) {
        // Redis 会自动清理过期的 key
        log.debug("Redis will auto-cleanup expired state with TTL={}", stateTtlSeconds);
    }
}
```

---

## 6. 指标上报器

### 6.1 RetryMetricsReporter 接口
```java
/**
 * 重试指标上报器接口
 */
public interface RetryMetricsReporter {
    
    void recordProducerStarted();
    void recordConsumerStarted();
    void recordRetryMessageSent(int level);
    void recordSendFailure();
    void recordDeadLetterSent();
    void recordDeadLetterSendFailure();
    void recordDeadLetterDisabled();
    void recordDeadLetterAlertSent();
    void recordMessageConsumed();
    void recordConsumeFailure();
    void recordRetryProcessed();
    void recordRetryProcessingFailed();
    void recordRetryDisabled();
    void recordMaxRetriesReached();
    void recordNonRetryableFailure();
}
```

### 6.2 FlinkRetryMetricsReporter 实现
```java
/**
 * Flink 重试指标上报器
 */
public class FlinkRetryMetricsReporter implements RetryMetricsReporter {
    
    private MetricGroup metricGroup;
    
    // 计数器
    private Counter producerStartedCounter;
    private Counter consumerStartedCounter;
    private Counter retryMessageSentCounter;
    private Counter sendFailureCounter;
    private Counter deadLetterSentCounter;
    private Counter messageConsumedCounter;
    private Counter consumeFailureCounter;
    private Counter retryProcessedCounter;
    private Counter retryProcessingFailedCounter;
    private Counter maxRetriesReachedCounter;
    private Counter nonRetryableFailureCounter;
    
    // 按级别统计的计数器
    private Map<Integer, Counter> retryMessageSentByLevelCounter = new HashMap<>();
    
    @Override
    public void register(MetricGroup metricGroup) {
        this.metricGroup = metricGroup;
        
        // 注册计数器
        producerStartedCounter = metricGroup.counter("producer.started.total");
        consumerStartedCounter = metricGroup.counter("consumer.started.total");
        retryMessageSentCounter = metricGroup.counter("retry.sent.total");
        sendFailureCounter = metricGroup.counter("send.failure.total");
        deadLetterSentCounter = metricGroup.counter("dlq.sent.total");
        deadLetterSendFailureCounter = metricGroup.counter("dlq.send.failure.total");
        deadLetterDisabledCounter = metricGroup.counter("dlq.disabled.total");
        deadLetterAlertSentCounter = metricGroup.counter("dlq.alert.sent.total");
        messageConsumedCounter = metricGroup.counter("message.consumed.total");
        consumeFailureCounter = metricGroup.counter("consume.failure.total");
        retryProcessedCounter = metricGroup.counter("retry.processed.total");
        retryProcessingFailedCounter = metricGroup.counter("retry.processing.failed.total");
        retryDisabledCounter = metricGroup.counter("retry.disabled.total");
        maxRetriesReachedCounter = metricGroup.counter("retry.max_retries_reached.total");
        nonRetryableFailureCounter = metricGroup.counter("retry.non_retryable.total");
        
        // 注册按级别统计的计数器
        for (int level = 1; level <= 3; level++) {
            retryMessageSentByLevelCounter.put(
                level,
                metricGroup.counter("retry.sent.level." + level)
            );
        }
    }
    
    @Override
    public void recordProducerStarted() {
        producerStartedCounter.inc();
    }
    
    @Override
    public void recordConsumerStarted() {
        consumerStartedCounter.inc();
    }
    
    @Override
    public void recordRetryMessageSent(int level) {
        retryMessageSentCounter.inc();
        
        Counter levelCounter = retryMessageSentByLevelCounter.get(level);
        if (levelCounter != null) {
            levelCounter.inc();
        }
    }
    
    @Override
    public void recordSendFailure() {
        sendFailureCounter.inc();
    }
    
    @Override
    public void recordDeadLetterSent() {
        deadLetterSentCounter.inc();
    }
    
    @Override
    public void recordDeadLetterSendFailure() {
        deadLetterSendFailureCounter.inc();
    }
    
    @Override
    public void recordDeadLetterDisabled() {
        deadLetterDisabledCounter.inc();
    }
    
    @Override
    public void recordDeadLetterAlertSent() {
        deadLetterAlertSentCounter.inc();
    }
    
    @Override
    public void recordMessageConsumed() {
        messageConsumedCounter.inc();
    }
    
    @Override
    public void recordConsumeFailure() {
        consumeFailureCounter.inc();
    }
    
    @Override
    public void recordRetryProcessed() {
        retryProcessedCounter.inc();
    }
    
    @Override
    public void recordRetryProcessingFailed() {
        retryProcessingFailedCounter.inc();
    }
    
    @Override
    public void recordRetryDisabled() {
        retryDisabledCounter.inc();
    }
    
    @Override
    public void recordMaxRetriesReached() {
        maxRetriesReachedCounter.inc();
    }
    
    @Override
    public void recordNonRetryableFailure() {
        nonRetryableFailureCounter.inc();
    }
}
```

---

## 7. Flink 集成

### 7.1 重试 Sink 实现
```java
/**
 * Flink 重试 Sink
 * 
 * 将需要重试的消息发送到 RocketMQ
 */
public class RetrySinkFunction implements SinkFunction<AttributionOutput> {
    
    private static final Logger log = LoggerFactory.getLogger(RetrySinkFunction.class);
    
    private transient RocketMQRetryProducer retryProducer;
    
    private final RetryConfig config;
    private final RetryManager retryManager;
    private final RetryMetricsReporter metricsReporter;
    
    public RetrySinkFunction(
        RetryConfig config,
        RetryManager retryManager,
        RetryMetricsReporter metricsReporter
    ) {
        this.config = config;
        this.retryManager = retryManager;
        this.metricsReporter = metricsReporter;
    }
    
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // 初始化重试生产者
        retryProducer = new RocketMQRetryProducer(config, retryManager, metricsReporter);
        retryProducer.init();
    }
    
    @Override
    public void invoke(AttributionOutput output, Context context) throws Exception {
        // 只处理重试类型的输出
        if (output.getOutputType() != OutputType.RETRY) {
            return;
        }
        
        RetryMessage retryMessage = output.getRetryMessage();
        
        if (retryMessage == null) {
            log.warn("Retry message is null in RETRY output");
            return;
        }
        
        // 发送重试消息
        retryProducer.sendRetryMessage(
            retryMessage,
            retryMessage.getFailureReason()
        );
    }
    
    @Override
    public void close() throws Exception {
        if (retryProducer != null) {
            retryProducer.shutdown();
        }
        super.close();
    }
}
```

### 7.2 重试 Source 实现
```java
/**
 * Flink 重试 Source
 * 
 * 从 RocketMQ 消费重试消息并重新注入
 */
public class RetrySourceFunction implements SourceFunction<CallbackData> {
    
    private static final Logger log = LoggerFactory.getLogger(RetrySourceFunction.class);
    
    private transient RetryConsumer retryConsumer;
    
    private final RetryConfig config;
    private final RetryMessageHandler messageHandler;
    private final RetryMetricsReporter metricsReporter;
    
    private volatile boolean isRunning = true;
    
    public RetrySourceFunction(
        RetryConfig config,
        RetryMessageHandler messageHandler,
        RetryMetricsReporter metricsReporter
    ) {
        this.config = config;
        this.messageHandler = messageHandler;
        this.metricsReporter = metricsReporter;
    }
    
    @Override
    public void run(SourceContext<CallbackData> ctx) throws Exception {
        // 初始化重试消费者
        retryConsumer = new RetryConsumer(config, messageHandler, metricsReporter);
        retryConsumer.init();
        
        // 保持运行直到被取消
        while (isRunning) {
            Thread.sleep(1000);
        }
    }
    
    @Override
    public void cancel() {
        isRunning = false;
        
        if (retryConsumer != null) {
            retryConsumer.shutdown();
        }
    }
}
```

---

## 8. 配置示例

### 8.1 YAML 配置
```yaml
# retry-config.yaml
retry:
  enabled: true
  maxAttempts: 3
  
  levels:
    - level: 1
      delayMinutes: 5
      maxAttemptsAtLevel: 2
    - level: 2
      delayMinutes: 30
      maxAttemptsAtLevel: 2
    - level: 3
      delayMinutes: 120
      maxAttemptsAtLevel: 1
  
  deadLetter:
    enabled: true
    topic: attribution-dlq-topic
    producerGroup: attribution-dlq-producer
    sendAlert: true
  
  rocketMQ:
    nameServer: "rmq-1:9876;rmq-2:9876"
    producerGroup: attribution-retry-producer
    consumerGroup: attribution-retry-consumer
    retryTopic: attribution-retry-topic
    accessKey: "${RMQ_ACCESS_KEY}"
    secretKey: "${RMQ_SECRET_KEY}"
  
  stateStore:
    type: redis
    ttlSeconds: 604800  # 7 天
    redis:
      host: redis-master
      port: 6379
      password: "${REDIS_PASSWORD}"
```

---

## 9. 异常类型定义

```java
/**
 * 重试相关异常基类
 */
public class RetryException extends RuntimeException {
    public RetryException(String message) {
        super(message);
    }
    
    public RetryException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 重试消息构建异常
 */
public class RetryMessageBuildException extends RetryException {
    public RetryMessageBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 重试消息解析异常
 */
public class RetryMessageParseException extends RetryException {
    public RetryMessageParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 无效重试消息异常
 */
public class InvalidRetryMessageException extends RetryException {
    public InvalidRetryMessageException(String message) {
        super(message);
    }
}

/**
 * 重试处理异常
 */
public class RetryProcessingException extends RetryException {
    public RetryProcessingException(String message) {
        super(message);
    }
}

/**
 * 反序列化异常
 */
public class DeserializationException extends RetryException {
    public DeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## 10. 监控告警配置

### 10.1 关键指标
| 指标名称 | 描述 | 告警阈值 |
|---------|------|---------|
| retry.sent.total | 重试发送总数 | - |
| retry.sent.level.N | 各级别重试发送数 | - |
| dlq.sent.total | 死信队列发送数 | > 100/小时 |
| retry.processing.failed.total | 重试处理失败数 | > 50/小时 |
| retry.max_retries_reached.total | 达到最大重试次数 | > 10/小时 |

### 10.2 告警规则
```yaml
# alert-rules.yaml
groups:
  - name: retry-alerts
    rules:
      - alert: HighDeadLetterRate
        expr: rate(dlq_sent_total[1h]) > 100
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High dead letter rate detected"
          description: "DLQ rate is {{ $value }} per hour"
      
      - alert: RetryProcessingFailures
        expr: rate(retry_processing_failed_total[15m]) > 50
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High retry processing failure rate"
          description: "Failure rate is {{ $value }} per 15 minutes"
```

---

**文档结束**

*此文档详细定义了重试机制中每个函数的实现细节，可直接用于编码实施。*
