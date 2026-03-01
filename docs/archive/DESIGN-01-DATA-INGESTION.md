# 设计文档 01 - 数据接入与转换层

> 多源数据接入 + 标准化转换设计

**版本**: v1.0  
**创建时间**: 2026-02-21  
**状态**: 📝 Design Review  
**关联任务**: T002

---

## 1. 概述

### 1.1 设计目标
- **多源接入**: 支持 Kafka、RocketMQ、Fluss 等多种消息中间件
- **多格式支持**: 兼容 JSON、Protobuf、Avro 等数据格式
- **统一转换**: 将不同格式转换为系统标准的 Callback Data 格式
- **可扩展**: 插件化设计，易于添加新的数据源和格式

### 1.2 核心组件
```
┌─────────────────────────────────────────────────────────────────┐
│                     数据接入层 (Data Ingestion)                  │
│                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │   Kafka     │  │  RocketMQ   │  │   Fluss     │             │
│  │   Source    │  │   Source    │  │   Source    │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         │                │                │                     │
│         └────────────────┴────────────────┘                     │
│                          │                                      │
│                          ▼                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Source Adapter Layer                        │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │ Kafka Adapter│  │RMQ Adapter   │  │Fluss Adapter │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          │                                      │
│                          ▼                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Format Decoder Layer                        │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │ JSON Decoder │  │  PB Decoder  │  │Avro Decoder  │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                   数据转换层 (Data Converter)                    │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Raw Event Normalizer                        │  │
│  │  - 字段映射                                               │  │
│  │  - 类型转换                                               │  │
│  │  - 数据校验                                               │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          │                                      │
│                          ▼                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │           Callback Data Converter                        │  │
│  │  - 统一字段命名                                           │  │
│  │  - 统一数据类型                                           │  │
│  │  - 补充默认值                                             │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          │                                      │
│                          ▼                                      │
│            ┌─────────────────────────┐                         │
│            │   Callback Data Output  │                         │
│            │   (Standard Format)     │                         │
│            └─────────────────────────┘                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 多源数据接入设计

### 2.1 数据源抽象接口

#### 2.1.1 SourceConfig 接口
```java
/**
 * 数据源配置接口
 */
public interface SourceConfig {
    String getSourceType();
    String getSourceName();
    Map<String, String> getProperties();
    boolean validate();
}
```

#### 2.1.2 Source 接口
```java
/**
 * 数据源抽象接口
 */
public interface Source<T> {
    /**
     * 初始化数据源连接
     */
    void init(SourceConfig config);
    
    /**
     * 消费消息
     */
    Stream<T> consume();
    
    /**
     * 提交偏移量
     */
    void commitOffset(Map<String, Long> offsets);
    
    /**
     * 关闭连接
     */
    void close();
}
```

### 2.2 Kafka 数据源实现

#### 2.2.1 KafkaSourceConfig
```java
@Data
@Builder
public class KafkaSourceConfig implements SourceConfig {
    private static final String SOURCE_TYPE = "KAFKA";
    
    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }
    
    private String sourceName;
    private String bootstrapServers;
    private String topic;
    private String groupId;
    private String format; // JSON, PB, AVRO
    private Map<String, String> properties;
    
    // Kafka 特定配置
    private String securityProtocol;
    private String saslMechanism;
    private String saslJaasConfig;
    private String schemaRegistryUrl;
    private int partitionCount;
}
```

#### 2.2.2 KafkaSource 实现
```java
public class KafkaSource implements Source<RawEvent> {
    private KafkaConsumer<String, byte[]> consumer;
    private KafkaSourceConfig config;
    private FormatDecoder decoder;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (KafkaSourceConfig) config;
        
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
                  StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                  ByteArrayDeserializer.class);
        props.putAll(config.getProperties());
        
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(config.getTopic()));
        
        // 初始化格式解码器
        this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
    }
    
    @Override
    public Stream<RawEvent> consume() {
        return Stream.generate(() -> {
            ConsumerRecords<String, byte[]> records = 
                consumer.poll(Duration.ofMillis(100));
            
            List<RawEvent> events = new ArrayList<>();
            for (ConsumerRecord<String, byte[]> record : records) {
                try {
                    RawEvent event = decoder.decode(record.value());
                    event.setMetadata(extractMetadata(record));
                    events.add(event);
                } catch (Exception e) {
                    log.error("Failed to decode message", e);
                }
            }
            return events.stream();
        }).flatMap(s -> s);
    }
    
    private EventMetadata extractMetadata(ConsumerRecord<String, byte[]> record) {
        return EventMetadata.builder()
            .sourceType("KAFKA")
            .topic(record.topic())
            .partition(record.partition())
            .offset(record.offset())
            .timestamp(record.timestamp())
            .key(record.key())
            .build();
    }
}
```

### 2.3 RocketMQ 数据源实现

#### 2.3.1 RocketMQSourceConfig
```java
@Data
@Builder
public class RocketMQSourceConfig implements SourceConfig {
    private static final String SOURCE_TYPE = "ROCKETMQ";
    
    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }
    
    private String sourceName;
    private String nameServer;
    private String topic;
    private String consumerGroup;
    private String format;
    private Map<String, String> properties;
    
    // RocketMQ 特定配置
    private String accessKey;
    private String secretKey;
    private String instanceId;
    private String messageModel; // CLUSTERING or BROADCASTING
    private String consumeFromWhere;
}
```

#### 2.3.2 RocketMQSource 实现
```java
public class RocketMQSource implements Source<RawEvent> {
    private DefaultLitePullConsumer consumer;
    private RocketMQSourceConfig config;
    private FormatDecoder decoder;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (RocketMQSourceConfig) config;
        
        try {
            this.consumer = new DefaultLitePullConsumer(config.getConsumerGroup());
            this.consumer.setNamesrvAddr(config.getNameServer());
            
            if (config.getAccessKey() != null) {
                this.consumer.setVipChannelEnabled(false);
                // 设置 ACL
            }
            
            this.consumer.start();
            this.consumer.subscribe(config.getTopic(), "*");
            
            this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
        } catch (Exception e) {
            throw new SourceInitException("Failed to init RocketMQ source", e);
        }
    }
    
    @Override
    public Stream<RawEvent> consume() {
        return Stream.generate(() -> {
            try {
                List<MessageExt> messages = consumer.poll();
                
                List<RawEvent> events = new ArrayList<>();
                for (MessageExt message : messages) {
                    try {
                        RawEvent event = decoder.decode(message.getBody());
                        event.setMetadata(extractMetadata(message));
                        events.add(event);
                    } catch (Exception e) {
                        log.error("Failed to decode RocketMQ message", e);
                    }
                }
                return events.stream();
            } catch (Exception e) {
                log.error("Failed to poll messages", e);
                return Stream.empty();
            }
        }).flatMap(s -> s);
    }
    
    private EventMetadata extractMetadata(MessageExt message) {
        return EventMetadata.builder()
            .sourceType("ROCKETMQ")
            .topic(message.getTopic())
            .queueId(message.getQueueId())
            .offset(message.getQueueOffset())
            .timestamp(message.getStoreTimestamp())
            .messageId(message.getMsgId())
            .tags(message.getTags())
            .keys(message.getKeys())
            .build();
    }
}
```

### 2.4 Fluss 数据源实现

#### 2.4.1 FlussSourceConfig
```java
@Data
@Builder
public class FlussSourceConfig implements SourceConfig {
    private static final String SOURCE_TYPE = "FLUSS";
    
    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }
    
    private String sourceName;
    private String gatewayUrl;
    private String streamName;
    private String format;
    private Map<String, String> properties;
    
    // Fluss 特定配置
    private String bootstrapServers;
    private long scanStartupOffset;
}
```

#### 2.4.2 FlussSource 实现
```java
public class FlussSource implements Source<RawEvent> {
    private Lakehouse lakehouse;
    private Table table;
    private FlussSourceConfig config;
    private FormatDecoder decoder;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (FlussSourceConfig) config;
        
        this.lakehouse = Lakehouse.builder()
            .bootstrapServers(config.getBootstrapServers())
            .build();
        
        this.table = lakehouse.getTable(config.getStreamName());
        this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
    }
    
    @Override
    public Stream<RawEvent> consume() {
        return Stream.generate(() -> {
            try (TableScan scan = table.newScan()
                    .withOffset(config.getScanStartupOffset())
                    .build()) {
                
                List<RawEvent> events = new ArrayList<>();
                for (Row row : scan) {
                    try {
                        RawEvent event = decoder.decode(row.toBytes());
                        event.setMetadata(extractMetadata(row));
                        events.add(event);
                    } catch (Exception e) {
                        log.error("Failed to decode Fluss row", e);
                    }
                }
                return events.stream();
            } catch (Exception e) {
                log.error("Failed to scan Fluss table", e);
                return Stream.empty();
            }
        }).flatMap(s -> s);
    }
}
```

---

## 3. 多格式解码器设计

### 3.1 格式解码器接口

#### 3.1.1 FormatDecoder 接口
```java
/**
 * 数据格式解码器接口
 */
public interface FormatDecoder {
    /**
     * 解码原始字节为 RawEvent
     */
    RawEvent decode(byte[] data) throws DecodeException;
    
    /**
     * 获取支持的格式类型
     */
    String getFormatType();
}
```

#### 3.1.2 DecoderFactory 工厂类
```java
public class DecoderFactory {
    private static final Map<String, Class<? extends FormatDecoder>> DECODERS = 
        Map.of(
            "JSON", JsonDecoder.class,
            "PROTOBUF", ProtobufDecoder.class,
            "AVRO", AvroDecoder.class,
            "THRIFT", ThriftDecoder.class
        );
    
    public static FormatDecoder createDecoder(String format, SourceConfig config) {
        Class<? extends FormatDecoder> decoderClass = 
            DECODERS.get(format.toUpperCase());
        
        if (decoderClass == null) {
            throw new IllegalArgumentException(
                "Unsupported format: " + format);
        }
        
        try {
            FormatDecoder decoder = decoderClass.getDeclaredConstructor().newInstance();
            decoder.init(config);
            return decoder;
        } catch (Exception e) {
            throw new DecoderException("Failed to create decoder", e);
        }
    }
}
```

### 3.2 JSON 解码器

#### 3.2.1 JsonDecoder 实现
```java
public class JsonDecoder implements FormatDecoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonSourceConfig config;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (JsonSourceConfig) config;
    }
    
    @Override
    public RawEvent decode(byte[] data) throws DecodeException {
        try {
            JsonNode node = MAPPER.readTree(data);
            return parseJsonNode(node);
        } catch (Exception e) {
            throw new DecodeException("Failed to decode JSON", e);
        }
    }
    
    private RawEvent parseJsonNode(JsonNode node) {
        return RawEvent.builder()
            .eventType(node.path("eventType").asText())
            .timestamp(node.path("timestamp").asLong())
            .userId(extractField(node, config.getUserIdField()))
            .advertiserId(extractField(node, config.getAdvertiserIdField()))
            .rawData(node.toString())
            .format("JSON")
            .build();
    }
    
    private String extractField(JsonNode node, String fieldPath) {
        String[] paths = fieldPath.split("\\.");
        JsonNode current = node;
        for (String path : paths) {
            current = current.path(path);
            if (current.isMissingNode()) {
                return null;
            }
        }
        return current.asText();
    }
}
```

### 3.3 Protobuf 解码器

#### 3.3.1 ProtobufDecoder 实现
```java
public class ProtobufDecoder implements FormatDecoder {
    private Descriptor descriptor;
    private ProtobufSourceConfig config;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (ProtobufSourceConfig) config;
        
        // 从 Schema Registry 或本地文件加载 descriptor
        this.descriptor = loadDescriptor(config.getProtoSchema());
    }
    
    @Override
    public RawEvent decode(byte[] data) throws DecodeException {
        try {
            DynamicMessage message = DynamicMessage.parseFrom(descriptor, data);
            return parseProtobufMessage(message);
        } catch (Exception e) {
            throw new DecodeException("Failed to decode Protobuf", e);
        }
    }
    
    private RawEvent parseProtobufMessage(DynamicMessage message) {
        return RawEvent.builder()
            .eventType(getField(message, "event_type"))
            .timestamp(getField(message, "timestamp", Long.class))
            .userId(getField(message, "user_id"))
            .advertiserId(getField(message, "advertiser_id"))
            .rawData(message.toString())
            .format("PROTOBUF")
            .build();
    }
}
```

### 3.4 Avro 解码器

#### 3.4.1 AvroDecoder 实现
```java
public class AvroDecoder implements FormatDecoder {
    private Schema schema;
    private DatumReader<GenericRecord> reader;
    private AvroSourceConfig config;
    
    @Override
    public void init(SourceConfig config) {
        this.config = (AvroSourceConfig) config;
        this.schema = loadSchema(config.getAvroSchema());
        this.reader = new GenericDatumReader<>(schema);
    }
    
    @Override
    public RawEvent decode(byte[] data) throws DecodeException {
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
            GenericRecord record = reader.read(null, decoder);
            return parseAvroRecord(record);
        } catch (Exception e) {
            throw new DecodeException("Failed to decode Avro", e);
        }
    }
}
```

---

## 4. Callback Data 标准化设计

### 4.1 Callback Data 定义

#### 4.1.1 标准 Callback Data 结构
```java
/**
 * 标准化的转化回调数据
 * 所有外部数据源经过转换后都使用此统一格式
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CallbackData {
    
    // ========== 基础信息 ==========
    
    /**
     * 事件唯一标识
     * 格式：{source_type}_{event_type}_{timestamp}_{uuid}
     */
    private String eventId;
    
    /**
     * 事件类型
     * CLICK | CONVERSION | IMPRESSION | VIEW
     */
    private EventType eventType;
    
    /**
     * 事件时间戳（毫秒）
     */
    private Long timestamp;
    
    /**
     * 数据处理时间戳（毫秒）
     */
    private Long processTimestamp;
    
    // ========== 用户标识 ==========
    
    /**
     * 用户 ID（主要标识）
     */
    private String userId;
    
    /**
     * 设备 ID
     */
    private String deviceId;
    
    /**
     * ID 类型：IMEI | IDFA | OAID | UUID
     */
    private String idType;
    
    // ========== 广告主信息 ==========
    
    /**
     * 广告主 ID
     */
    private String advertiserId;
    
    /**
     * 广告主名称
     */
    private String advertiserName;
    
    /**
     * 应用 ID
     */
    private String appId;
    
    /**
     * 应用名称
     */
    private String appName;
    
    // ========== 广告活动信息 ==========
    
    /**
     * 广告活动 ID
     */
    private String campaignId;
    
    /**
     * 广告计划 ID
     */
    private String adGroupId;
    
    /**
     * 广告创意 ID
     */
    private String creativeId;
    
    /**
     * 广告位 ID
     */
    private String placementId;
    
    // ========== 转化信息 ==========
    
    /**
     * 转化类型
     * PURCHASE | REGISTER | DOWNLOAD | ADD_TO_CART | etc.
     */
    private String conversionType;
    
    /**
     * 转化子类型
     */
    private String conversionSubtype;
    
    /**
     * 转化值（金额）
     */
    private Double conversionValue;
    
    /**
     * 货币类型
     */
    private String currency;
    
    /**
     * 商品 ID
     */
    private String productId;
    
    /**
     * 商品类别
     */
    private String productCategory;
    
    /**
     * 数量
     */
    private Integer quantity;
    
    /**
     * 订单 ID
     */
    private String orderId;
    
    // ========== 设备与环境信息 ==========
    
    /**
     * 设备类型
     * MOBILE | DESKTOP | TABLET | TV
     */
    private DeviceType deviceType;
    
    /**
     * 操作系统
     */
    private String os;
    
    /**
     * 操作系统版本
     */
    private String osVersion;
    
    /**
     * 浏览器
     */
    private String browser;
    
    /**
     * 浏览器版本
     */
    private String browserVersion;
    
    /**
     * IP 地址
     */
    private String ipAddress;
    
    /**
     * User Agent
     */
    private String userAgent;
    
    // ========== 地理位置信息 ==========
    
    /**
     * 国家代码
     */
    private String country;
    
    /**
     * 省份
     */
    private String province;
    
    /**
     * 城市
     */
    private String city;
    
    // ========== 追踪信息 ==========
    
    /**
     * 点击追踪 URL
     */
    private String clickUrl;
    
    /**
     * 落地页 URL
     */
    private String landingPageUrl;
    
    /**
     * 来源 URL
     */
    private String referrer;
    
    /**
     * 追踪参数（自定义）
     */
    private Map<String, String> trackingParams;
    
    // ========== 元数据 ==========
    
    /**
     * 原始数据源类型
     */
    private String sourceType;
    
    /**
     * 原始数据格式
     */
    private String sourceFormat;
    
    /**
     * 原始数据（用于调试和重放）
     */
    private String rawData;
    
    /**
     * 数据质量分数（0-100）
     */
    private Integer dataQualityScore;
    
    /**
     * 校验状态
     */
    private ValidationStatus validationStatus;
    
    /**
     * 校验错误信息
     */
    private List<String> validationErrors;
}
```

#### 4.1.2 枚举类型定义
```java
/**
 * 事件类型枚举
 */
public enum EventType {
    CLICK("点击"),
    CONVERSION("转化"),
    IMPRESSION("展示"),
    VIEW("浏览"),
    UNKNOWN("未知");
    
    private final String description;
}

/**
 * 设备类型枚举
 */
public enum DeviceType {
    MOBILE("手机"),
    DESKTOP("桌面"),
    TABLET("平板"),
    TV("电视"),
    UNKNOWN("未知");
}

/**
 * 校验状态枚举
 */
public enum ValidationStatus {
    VALID("有效"),
    INVALID("无效"),
    WARNING("警告"),
    PENDING("待校验");
}
```

### 4.2 数据转换器设计

#### 4.2.1 CallbackDataConverter 接口
```java
/**
 * Callback 数据转换器接口
 */
public interface CallbackDataConverter {
    
    /**
     * 将 RawEvent 转换为 CallbackData
     */
    CallbackData convert(RawEvent rawEvent);
    
    /**
     * 批量转换
     */
    List<CallbackData> convertAll(List<RawEvent> rawEvents);
    
    /**
     * 获取转换器支持的源类型
     */
    String getSourceType();
}
```

#### 4.2.2 通用转换器实现
```java
public class GenericCallbackDataConverter implements CallbackDataConverter {
    
    private final MappingRules mappingRules;
    private final DataValidator validator;
    
    public GenericCallbackDataConverter(MappingRules mappingRules) {
        this.mappingRules = mappingRules;
        this.validator = new DataValidator();
    }
    
    @Override
    public CallbackData convert(RawEvent rawEvent) {
        CallbackData.CallbackDataBuilder builder = CallbackData.builder();
        
        // 1. 生成标准 EventId
        builder.eventId(generateEventId(rawEvent));
        
        // 2. 转换事件类型
        builder.eventType(convertEventType(rawEvent.getEventType()));
        
        // 3. 映射基础字段
        mapBasicFields(rawEvent, builder);
        
        // 4. 映射用户标识
        mapUserIdentity(rawEvent, builder);
        
        // 5. 映射广告信息
        mapAdInfo(rawEvent, builder);
        
        // 6. 映射转化信息
        mapConversionInfo(rawEvent, builder);
        
        // 7. 映射设备信息
        mapDeviceInfo(rawEvent, builder);
        
        // 8. 映射地理位置
        mapLocationInfo(rawEvent, builder);
        
        // 9. 设置元数据
        builder.sourceType(rawEvent.getMetadata().getSourceType());
        builder.sourceFormat(rawEvent.getFormat());
        builder.rawData(rawEvent.getRawData());
        builder.processTimestamp(System.currentTimeMillis());
        
        // 10. 数据校验
        CallbackData callbackData = builder.build();
        ValidationResult validationResult = validator.validate(callbackData);
        builder.validationStatus(validationResult.getStatus());
        builder.validationErrors(validationResult.getErrors());
        builder.dataQualityScore(calculateQualityScore(callbackData));
        
        return builder.build();
    }
    
    private String generateEventId(RawEvent rawEvent) {
        return String.format("%s_%s_%d_%s",
            rawEvent.getMetadata().getSourceType(),
            rawEvent.getEventType(),
            rawEvent.getTimestamp(),
            UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }
    
    private void mapBasicFields(RawEvent rawEvent, CallbackData.CallbackDataBuilder builder) {
        builder.timestamp(rawEvent.getTimestamp());
        builder.userId(mappingRules.map(rawEvent, "user_id"));
        builder.advertiserId(mappingRules.map(rawEvent, "advertiser_id"));
    }
    
    private void mapUserIdentity(RawEvent rawEvent, CallbackData.CallbackDataBuilder builder) {
        // 尝试多种用户 ID 字段
        builder.userId(
            mappingRules.map(rawEvent, "user_id")
                .orElse(mappingRules.map(rawEvent, "device_id"))
                .orElse(mappingRules.map(rawEvent, "imei"))
                .orElse(null)
        );
        
        builder.deviceId(mappingRules.map(rawEvent, "device_id"));
        builder.idType(detectIdType(rawEvent));
    }
    
    private String detectIdType(RawEvent rawEvent) {
        if (rawEvent.hasField("imei")) return "IMEI";
        if (rawEvent.hasField("idfa")) return "IDFA";
        if (rawEvent.hasField("oaid")) return "OAID";
        return "UUID";
    }
    
    private Integer calculateQualityScore(CallbackData data) {
        int score = 100;
        
        // 必填字段缺失扣分
        if (data.getUserId() == null) score -= 30;
        if (data.getTimestamp() == null) score -= 20;
        if (data.getEventType() == null) score -= 20;
        
        // 可选字段缺失扣分
        if (data.getAdvertiserId() == null) score -= 10;
        if (data.getCampaignId() == null) score -= 5;
        
        // 校验错误扣分
        if (data.getValidationErrors() != null) {
            score -= data.getValidationErrors().size() * 5;
        }
        
        return Math.max(0, score);
    }
}
```

### 4.3 字段映射规则配置

#### 4.3.1 MappingRules 配置
```java
@Data
public class MappingRules {
    
    /**
     * 数据源类型
     */
    private String sourceType;
    
    /**
     * 字段映射规则：目标字段 -> 源字段路径
     */
    private Map<String, String> fieldMappings;
    
    /**
     * 字段转换规则
     */
    private Map<String, FieldTransformer> fieldTransformers;
    
    /**
     * 默认值配置
     */
    private Map<String, Object> defaultValues;
    
    /**
     * 字段验证规则
     */
    private Map<String, FieldValidator> fieldValidators;
    
    public String map(RawEvent event, String targetField) {
        String sourcePath = fieldMappings.get(targetField);
        if (sourcePath == null) {
            return (String) defaultValues.get(targetField);
        }
        
        String value = event.getField(sourcePath);
        if (value == null || value.isEmpty()) {
            return (String) defaultValues.get(targetField);
        }
        
        // 应用转换器
        FieldTransformer transformer = fieldTransformers.get(targetField);
        if (transformer != null) {
            value = transformer.transform(value);
        }
        
        return value;
    }
}
```

#### 4.3.2 映射规则配置示例（YAML）
```yaml
# kafka-click-mapping.yaml
sourceType: KAFKA
topic: ad-click-events

fieldMappings:
  user_id: "user.id"
  device_id: "device.deviceId"
  advertiser_id: "ad.advertiserId"
  campaign_id: "ad.campaignId"
  creative_id: "ad.creativeId"
  timestamp: "event.timestamp"
  event_type: "event.type"
  click_url: "tracking.clickUrl"
  referrer: "tracking.referrer"
  ip_address: "device.ip"
  user_agent: "device.userAgent"
  country: "geo.country"
  province: "geo.province"
  city: "geo.city"

fieldTransformers:
  timestamp: "timestamp_millis"
  event_type: "uppercase"
  country: "uppercase"
  
defaultValues:
  event_type: "CLICK"
  currency: "CNY"
  
fieldValidators:
  user_id: "not_empty"
  timestamp: "positive_long"
  event_type: "enum[CLICK,CONVERSION,IMPRESSION]"
```

---

## 5. Flink 集成设计

### 5.1 Flink Source Function

#### 5.1.1 MultiSourceFunction
```java
public class MultiSourceFunction implements SourceFunction<CallbackData> {
    
    private final List<SourceConfig> sourceConfigs;
    private final CallbackDataConverter converter;
    private volatile boolean isRunning = true;
    
    @Override
    public void run(SourceContext<CallbackData> ctx) throws Exception {
        List<Source<RawEvent>> sources = createSources(sourceConfigs);
        ExecutorService executor = Executors.newFixedThreadPool(sources.size());
        
        for (Source<RawEvent> source : sources) {
            executor.submit(() -> {
                while (isRunning) {
                    source.consume()
                        .map(converter::convert)
                        .forEach(data -> {
                            synchronized (ctx.getCheckpointLock()) {
                                ctx.collect(data);
                            }
                        });
                }
            });
        }
        
        // 等待所有源完成
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void cancel() {
        isRunning = false;
    }
}
```

### 5.2 Flink DataStream 管道

```java
// 创建多源数据流
DataStream<CallbackData> callbackStream = env.addSource(
    new MultiSourceFunction(sourceConfigs, converter)
)
.name("Multi-Source Ingestion")
.uid("multi-source-ingestion");

// 数据质量过滤
DataStream<CallbackData> validStream = callbackStream
    .filter(data -> data.getValidationStatus() == ValidationStatus.VALID)
    .name("Quality Filter")
    .uid("quality-filter");

// 写入 Fluss
validStream.addSink(
    new FlussSink<>(flussConfig, "callback-data-stream")
)
.name("Fluss Sink")
.uid("fluss-sink");
```

---

## 6. 监控与指标

### 6.1 关键指标

| 指标名称 | 描述 | 类型 |
|---------|------|------|
| source_records_in | 各数据源输入记录数 | Counter |
| conversion_success | 转换成功记录数 | Counter |
| conversion_failed | 转换失败记录数 | Counter |
| conversion_latency_ms | 转换延迟（毫秒） | Histogram |
| data_quality_score | 数据质量分数分布 | Histogram |
| validation_errors | 校验错误分类统计 | Counter |

### 6.2 告警规则

| 告警名称 | 触发条件 | 级别 |
|---------|---------|------|
| 数据源中断 | 某数据源 5 分钟无数据 | P1 |
| 转换失败率高 | 失败率 > 5% | P2 |
| 数据质量下降 | 平均质量分数 < 80 | P2 |
| 转换延迟高 | P99 延迟 > 1 秒 | P3 |

---

## 7. 扩展性设计

### 7.1 添加新数据源
1. 实现 `SourceConfig` 配置类
2. 实现 `Source<RawEvent>` 接口
3. 在 `SourceFactory` 中注册
4. 配置映射规则 YAML

### 7.2 添加新数据格式
1. 实现 `FormatDecoder` 接口
2. 在 `DecoderFactory` 中注册
3. 配置格式解析参数

### 7.3 自定义转换规则
1. 实现 `FieldTransformer` 接口
2. 在映射规则 YAML 中配置
3. 支持 SpEL 表达式转换

---

## 8. 配置示例

### 8.1 完整数据源配置
```yaml
# data-sources.yaml
sources:
  - name: kafka-click-source
    type: KAFKA
    enabled: true
    config:
      bootstrapServers: "kafka-1:9092,kafka-2:9092"
      topic: "ad-click-events"
      groupId: "attribution-click-consumer"
      format: JSON
    mapping: kafka-click-mapping.yaml
    
  - name: rocketmq-conversion-source
    type: ROCKETMQ
    enabled: true
    config:
      nameServer: "rmq-1:9876;rmq-2:9876"
      topic: "ad-conversion-events"
      consumerGroup: "attribution-conversion-consumer"
      format: PROTOBUF
    mapping: rmq-conversion-mapping.yaml
    
  - name: fluss-internal-source
    type: FLUSS
    enabled: true
    config:
      gatewayUrl: "fluss://fluss-1:9123"
      streamName: "internal-events"
      format: AVRO
    mapping: fluss-internal-mapping.yaml
```

---

**文档结束**

*此设计文档定义了多源数据接入和标准化转换的完整方案，支持灵活扩展和配置化。*
