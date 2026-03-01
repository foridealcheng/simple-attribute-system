# 设计文档 04 - 配置管理设计

> 统一配置中心与热更新设计

**版本**: v1.0  
**创建时间**: 2026-02-22  
**状态**: ✅ Approved  
**关联任务**: T020

---

## 1. 概述

### 1.1 设计目标
- **统一配置**: 所有配置集中管理
- **热更新**: 支持运行时配置变更
- **版本管理**: 配置版本可追溯
- **环境隔离**: 开发/测试/生产环境隔离

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 配置中心 | Nacos | 支持配置推送、版本管理 |
| 配置格式 | YAML | 人类可读、结构清晰 |
| 版本控制 | Git | 配置版本追溯 |
| 加密 | Jasypt | 敏感配置加密 |

---

## 2. 配置分类

### 2.1 应用配置

```yaml
# application.yaml
application:
  name: simple-attribute-system
  version: 1.0.0
  environment: ${ENV:production}
  
  # 日志配置
  logging:
    level: INFO
    pattern:
      console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
      file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
    file:
      name: /var/log/attribution/application.log
      max-size: 100MB
      max-history: 30
```

### 2.2 Flink 作业配置

```yaml
# flink.yaml
flink:
  job:
    name: attribution-engine
    parallelism: 4
    max-parallelism: 10
    
  # Checkpoint 配置
  checkpoint:
    interval: 60000          # 1 分钟
    mode: EXACTLY_ONCE
    timeout: 600000          # 10 分钟
    min-pause: 500           # 最小暂停 500ms
    max-concurrent: 1        # 最多 1 个并发 checkpoint
    
  # 状态后端
  state:
    backend: rocksdb
    checkpoints.dir: s3://flink-checkpoints/
    savepoints.dir: s3://flink-savepoints/
    ttl: 604800000           # 7 天
    
  # 重启策略
  restart-strategy:
    type: fixed-delay
    attempts: 3
    delay: 10000             # 10 秒
```

### 2.3 业务配置

```yaml
# attribution.yaml
attribution:
  # 归因窗口
  window:
    days: 7
    ms: 604800000
    
  # 归因模型
  model: LAST_CLICK  # LAST_CLICK | LINEAR | TIME_DECAY | POSITION_BASED
  
  # 点击存储
  click:
    max-per-user: 50        # 单用户最大点击数
    cleanup-after-conv: false  # 转化后是否清理
    
  # 去重配置
  dedup:
    enabled: true
    window-ms: 3600000      # 1 小时去重窗口
    
  # 时间衰减模型配置
  time-decay:
    decay-factor: 0.5
    half-life-ms: 259200000  # 3 天
    
  # 位置归因模型配置
  position-based:
    first-last-weight: 0.4   # 首末点击各 40%
```

### 2.4 Fluss 配置

```yaml
# fluss.yaml
fluss:
  # 连接配置
  bootstrap.servers: fluss-1:9110,fluss-2:9110,fluss-3:9110
  
  # KV 存储
  kv:
    table: attribution-clicks
    read-timeout-ms: 3000
    write-timeout-ms: 5000
    
  # 结果队列
  result:
    success-topic: attribution-results-success
    failed-topic: attribution-results-failed
    
  # 性能优化
  performance:
    batch-size: 100
    batch-interval-ms: 1000
    cache-size: 10000
    cache-expire-minutes: 5
```

### 2.5 RocketMQ 配置

```yaml
# rocketmq.yaml
rocketmq:
  # NameServer
  name-server: rmq-ns-1:9876;rmq-ns-2:9876
  
  # 生产者配置
  producer:
    group: attribution-producer
    retry-times: 3
    send-timeout-ms: 3000
    max-message-size: 4194304  # 4MB
    
  # 重试配置
  retry:
    topic: attribution-retry-topic
    enabled: true
    max-attempts: 3
    
    # 延迟级别
    levels:
      - level: 1
        delay-minutes: 5
        max-attempts: 2
      - level: 2
        delay-minutes: 30
        max-attempts: 2
      - level: 3
        delay-minutes: 120
        max-attempts: 1
    
    # 死信队列
    dead-letter:
      enabled: true
      topic: attribution-dlq-topic
      send-alert: true
      
  # 消费者配置
  consumer:
    group: attribution-retry-consumer
    thread-min: 20
    thread-max: 40
    batch-size: 32
    timeout-minutes: 15
```

### 2.6 监控配置

```yaml
# monitoring.yaml
monitoring:
  # Prometheus
  prometheus:
    enabled: true
    port: 9249
    path: /metrics
    
  # 指标上报
  metrics:
    jvm: true
    flink: true
    business: true
    
  # 告警配置
  alert:
    enabled: true
    webhook: http://alertmanager:9093/api/v1/alerts
    
  # 日志收集
  logging:
    collector: fluentd
    host: fluentd:24224
```

---

## 3. 配置热更新

### 3.1 支持热更新的配置

| 配置项 | 热更新 | 说明 | 生效时间 |
|--------|--------|------|---------|
| attribution.model | ✅ | 归因模型切换 | 立即 |
| attribution.window.ms | ✅ | 归因窗口调整 | 新事件生效 |
| attribution.max-clicks | ✅ | 最大点击数 | 新点击生效 |
| flink.parallelism | ❌ | 需要重启作业 | 重启后 |
| fluss.bootstrap.servers | ❌ | 需要重启 | 重启后 |
| logging.level | ✅ | 日志级别 | 立即 |

### 3.2 热更新流程

```
┌─────────────────────────────────────────────────────────┐
│              配置热更新流程                              │
│                                                          │
│  1. 配置中心                                             │
│     Nacos 控制台更新配置                                 │
│          │                                               │
│          ▼                                               │
│  2. 配置推送                                             │
│     Nacos 推送变更通知                                   │
│          │                                               │
│          ▼                                               │
│  3. 配置监听器                                           │
│     @NacosConfigListener                                 │
│          │                                               │
│          ▼                                               │
│  4. 配置更新                                             │
│     更新运行时配置对象                                   │
│          │                                               │
│          ▼                                               │
│  5. 生效                                                 │
│     新配置立即/逐步生效                                  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### 3.3 代码实现

```java
/**
 * 配置更新监听器
 */
@Component
public class AttributionConfigListener {
    
    @NacosConfigListener(dataId = "attribution.yaml")
    public void onAttributionConfigChanged(AttributionConfig newConfig) {
        log.info("Attribution config changed: {}", newConfig);
        
        // 更新运行时配置
        AttributionContextHolder.update(newConfig);
        
        // 记录变更日志
        ConfigChangeLog.log("attribution", newConfig);
    }
    
    @NacosConfigListener(dataId = "logging.yaml")
    public void onLoggingConfigChanged(LoggingConfig newConfig) {
        log.info("Logging config changed: level={}", newConfig.getLevel());
        
        // 动态调整日志级别
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.toLevel(newConfig.getLevel()));
    }
}

/**
 * 配置上下文（线程安全）
 */
public class AttributionContextHolder {
    
    private static final AtomicReference<AttributionConfig> CONFIG = 
        new AtomicReference<>();
    
    public static void update(AttributionConfig newConfig) {
        CONFIG.set(newConfig);
    }
    
    public static AttributionConfig get() {
        return CONFIG.get();
    }
    
    public static long getWindowMs() {
        return CONFIG.get().getWindow().getMs();
    }
    
    public static AttributionModel getModel() {
        return AttributionModel.valueOf(CONFIG.get().getModel());
    }
}
```

---

## 4. 配置版本管理

### 4.1 Git 目录结构

```
config-repo/
├── README.md
├── base.yaml              # 基础配置
├── application.yaml       # 应用配置
├── flink.yaml            # Flink 配置
├── attribution.yaml      # 业务配置
├── fluss.yaml            # Fluss 配置
├── rocketmq.yaml         # RocketMQ 配置
├── monitoring.yaml       # 监控配置
├── environments/
│   ├── dev.yaml          # 开发环境
│   ├── test.yaml         # 测试环境
│   └── prod.yaml         # 生产环境
└── history/              # 配置变更历史
    ├── 2026-02-22-001.yaml
    ├── 2026-02-22-002.yaml
    └── ...
```

### 4.2 配置变更流程

```
┌─────────────────────────────────────────────────────────┐
│              配置变更审批流程                            │
│                                                          │
│  开发环境：                                              │
│  开发者 → 提交配置 → Git → 自动部署                      │
│                                                          │
│  测试环境：                                              │
│  开发者 → 提交配置 → Git → TL 审批 → 自动部署            │
│                                                          │
│  生产环境：                                              │
│  开发者 → 提交配置 → Git → TL 审批 → 变更委员会 → 手动部署│
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### 4.3 配置回滚

```bash
# 1. 查看配置历史
nacos config history --dataId attribution.yaml

# 2. 回滚到指定版本
nacos config rollback --dataId attribution.yaml --version 123

# 3. 验证回滚
nacos config get --dataId attribution.yaml
```

---

## 5. 敏感配置加密

### 5.1 加密配置

```yaml
# 使用 Jasypt 加密
fluss:
  username: ENC(AES256Base64:xK8vN2mL9pQ3rT6sU1wY4zA=)
  password: ENC(AES256Base64:bC7dE2fG5hI8jK1lM4nO9pQ=)

rocketmq:
  access-key: ENC(AES256Base64:rS3tU6vW9xY2zA5bC8dE1fG=)
  secret-key: ENC(AES256Base64:hI4jK7lM0nO3pQ6rS9tU2vW=)
```

### 5.2 加密工具

```java
/**
 * 配置加密工具
 */
public class ConfigEncryptor {
    
    private static final String SECRET_KEY = System.getenv("CONFIG_SECRET_KEY");
    private static final PooledPBEStringEncryptor encryptor;
    
    static {
        encryptor = new PooledPBEStringEncryptor();
        encryptor.setAlgorithm("PBEWithHMACSHA512AndAES_256");
        encryptor.setPassword(SECRET_KEY);
        encryptor.setPoolSize(10);
    }
    
    public static String encrypt(String plainText) {
        return encryptor.encrypt(plainText);
    }
    
    public static String decrypt(String encryptedText) {
        return encryptor.decrypt(encryptedText);
    }
}
```

---

## 6. 配置校验

### 6.1 配置校验器

```java
/**
 * 配置校验器
 */
public class ConfigValidator {
    
    /**
     * 校验配置
     */
    public static ValidationResult validate(AttributionConfig config) {
        ValidationResult result = new ValidationResult();
        
        // 校验归因窗口
        if (config.getWindow().getMs() <= 0) {
            result.addError("归因窗口必须大于 0");
        }
        
        // 校验最大点击数
        if (config.getClick().getMaxPerUser() <= 0) {
            result.addError("最大点击数必须大于 0");
        }
        
        // 校验归因模型
        if (!isValidModel(config.getModel())) {
            result.addError("无效的归因模型：" + config.getModel());
        }
        
        return result;
    }
    
    private static boolean isValidModel(String model) {
        return Arrays.asList("LAST_CLICK", "LINEAR", "TIME_DECAY", "POSITION_BASED")
            .contains(model.toUpperCase());
    }
}
```

---

## 7. 配置中心集成

### 7.1 Nacos 配置

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.alibaba.boot</groupId>
    <artifactId>nacos-config-spring-boot-starter</artifactId>
    <version>0.2.12</version>
</dependency>
```

```yaml
# application.yml
nacos:
  config:
    server-addr: nacos:8848
    namespace: attribution-system
    group: DEFAULT_GROUP
    file-extension: yaml
    auto-refresh: true
    
    # 共享配置
    shared-configs:
      - data-id: common.yaml
        group: COMMON
        refresh: true
```

---

## 8. 监控与告警

### 8.1 配置变更监控

```yaml
# 配置变更指标
config:
  changes:
    total: Counter      # 总变更次数
    by-user: Counter    # 按用户统计
    by-env: Counter     # 按环境统计
    rollback: Counter   # 回滚次数
```

### 8.2 配置一致性检查

```java
/**
 * 配置一致性检查
 */
@Component
public class ConfigConsistencyChecker {
    
    @Scheduled(fixedRate = 300000)  // 每 5 分钟检查一次
    public void checkConsistency() {
        // 获取 Nacos 配置
        Config nacosConfig = nacosConfigServer.getConfig("attribution.yaml");
        
        // 获取本地配置
        Config localConfig = localConfigCache.get();
        
        // 比较配置
        if (!nacosConfig.equals(localConfig)) {
            log.warn("配置不一致！Nacos: {}, Local: {}", nacosConfig, localConfig);
            
            // 发送告警
            alertService.send("配置不一致告警");
        }
    }
}
```

---

## 9. 最佳实践

### 9.1 配置命名规范

```yaml
# ✅ 好的命名
attribution.window.ms
fluss.bootstrap.servers
rocketmq.producer.group

# ❌ 不好的命名
window
servers
group
```

### 9.2 配置分层

```
Level 1: base.yaml (基础配置，所有环境共享)
    ↓
Level 2: application.yaml (应用配置)
    ↓
Level 3: environments/{env}.yaml (环境特定配置)
```

### 9.3 配置文档化

```yaml
# 每个配置项都应有注释
attribution:
  # 归因窗口大小（毫秒）
  # 默认：7 天 (604800000ms)
  # 范围：1 小时 - 30 天
  window:
    ms: 604800000
```

---

**文档结束**

*此文档定义了配置管理的完整方案，支持热更新、版本管理和安全加密。*
