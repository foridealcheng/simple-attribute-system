package com.attribution.decoder;

import com.attribution.model.RawEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Avro 格式解码器
 * 
 * 将 Avro 格式数据解码为 RawEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AvroDecoder implements FormatDecoder {

    private Schema schema;
    private GenericDatumReader<GenericRecord> datumReader;
    private boolean initialized = false;

    /**
     * 使用 Avro Schema 初始化解码器
     * 
     * @param schemaStr Avro Schema (JSON 格式)
     */
    public void init(String schemaStr) {
        try {
            this.schema = new Schema.Parser().parse(schemaStr);
            this.datumReader = new GenericDatumReader<>(schema);
            this.initialized = true;
            
            log.info("Avro decoder initialized with schema: {}", schema.getName());
            
        } catch (Exception e) {
            log.error("Failed to parse Avro schema", e);
            throw new RuntimeException("Failed to initialize Avro decoder", e);
        }
    }

    /**
     * 使用 Schema 对象初始化
     * 
     * @param schema Avro Schema 对象
     */
    public void init(Schema schema) {
        this.schema = schema;
        this.datumReader = new GenericDatumReader<>(schema);
        this.initialized = true;
        
        log.info("Avro decoder initialized with schema: {}", schema.getName());
    }

    @Override
    public RawEvent decode(byte[] data) throws DecoderException {
        if (data == null || data.length == 0) {
            throw new DecoderException("Empty data");
        }

        if (!initialized) {
            throw new DecoderException("Avro decoder not initialized. Please provide schema.");
        }

        try {
            // 使用 BinaryDecoder 解码 Avro 数据
            BinaryDecoder binaryDecoder = DecoderFactory.get().binaryDecoder(data, null);
            GenericRecord record = datumReader.read(null, binaryDecoder);
            
            RawEvent event = new RawEvent();
            
            // 提取字段
            Map<String, Object> fieldMap = new HashMap<>();
            
            for (Schema.Field field : schema.getFields()) {
                Object value = record.get(field.name());
                fieldMap.put(field.name(), value);
                
                // 同时尝试设置到标准字段
                setStandardField(event, field.name(), value);
            }
            
            event.setData(fieldMap);
            
            return event;

        } catch (IOException e) {
            log.error("Failed to parse Avro record", e);
            throw new DecoderException("Failed to parse Avro: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormat() {
        return "AVRO";
    }

    /**
     * 设置标准字段
     */
    private void setStandardField(RawEvent event, String fieldName, Object value) {
        if (value == null) {
            return;
        }

        // 处理 ByteBuffer 类型
        if (value instanceof ByteBuffer) {
            ByteBuffer buffer = (ByteBuffer) value;
            value = new String(buffer.array());
        }

        String normalizedField = fieldName.toLowerCase().replace("_", "");
        
        switch (normalizedField) {
            case "eventid":
                event.setEventId(value.toString());
                break;
            case "userid":
                event.setUserId(value.toString());
                break;
            case "timestamp":
                if (value instanceof Long) {
                    event.setTimestamp((Long) value);
                } else if (value instanceof Integer) {
                    event.setTimestamp(((Integer) value).longValue());
                }
                break;
            case "eventtype":
                event.setEventType(value.toString());
                break;
            case "advertiserid":
                event.setAdvertiserId(value.toString());
                break;
            case "campaignid":
                event.setCampaignId(value.toString());
                break;
            default:
                // 忽略其他字段
                break;
        }
    }

    @Override
    public void close() {
        // Avro 解码器不需要特殊清理
        log.debug("Avro decoder closed");
    }
}
