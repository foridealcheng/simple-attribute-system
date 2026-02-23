package com.attribution.decoder;

import com.attribution.model.RawEvent;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Protobuf 格式解码器
 * 
 * 将 Protobuf 格式数据解码为 RawEvent
 * 使用 DynamicMessage 进行动态解码
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class ProtobufDecoder implements FormatDecoder {

    private Descriptors.Descriptor messageDescriptor;
    private boolean initialized = false;

    /**
     * 使用 Protobuf 描述符初始化解码器
     * 
     * @param descriptorProto Protobuf 描述符
     */
    public void init(DescriptorProtos.DescriptorProto descriptorProto) {
        try {
            FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .addMessageType(descriptorProto)
                .build();
            
            Descriptors.FileDescriptor fileDescriptor = 
                Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[0]);
            
            this.messageDescriptor = fileDescriptor.findMessageTypeByName(descriptorProto.getName());
            this.initialized = true;
            
            log.info("Protobuf decoder initialized with message type: {}", descriptorProto.getName());
            
        } catch (Descriptors.DescriptorValidationException e) {
            log.error("Failed to initialize Protobuf descriptor", e);
            throw new RuntimeException("Failed to initialize Protobuf decoder", e);
        }
    }

    @Override
    public RawEvent decode(byte[] data) throws DecoderException {
        if (data == null || data.length == 0) {
            throw new DecoderException("Empty data");
        }

        if (!initialized) {
            throw new DecoderException("Protobuf decoder not initialized. Please provide descriptor.");
        }

        try {
            // 使用 DynamicMessage 解析 Protobuf 数据
            DynamicMessage message = DynamicMessage.parseFrom(messageDescriptor, data);
            
            RawEvent event = new RawEvent();
            
            // 提取字段
            Map<String, Object> fieldMap = new HashMap<>();
            
            for (Descriptors.FieldDescriptor field : messageDescriptor.getFields()) {
                Object value = message.getField(field);
                fieldMap.put(field.getName(), value);
                
                // 同时尝试设置到标准字段
                setStandardField(event, field.getName(), value);
            }
            
            event.setData(fieldMap);
            
            return event;

        } catch (Exception e) {
            log.error("Failed to parse Protobuf message", e);
            throw new DecoderException("Failed to parse Protobuf: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormat() {
        return "PROTOBUF";
    }

    /**
     * 设置标准字段
     */
    private void setStandardField(RawEvent event, String fieldName, Object value) {
        if (value == null) {
            return;
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
        // Protobuf 解码器不需要特殊清理
        log.debug("Protobuf decoder closed");
    }
}
