package com.attribution.decoder;

import com.attribution.model.RawEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON 格式解码器
 * 
 * 将 JSON 格式数据解码为 RawEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class JsonDecoder implements FormatDecoder {

    private final ObjectMapper objectMapper;

    public JsonDecoder() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public RawEvent decode(byte[] data) throws DecoderException {
        if (data == null || data.length == 0) {
            throw new DecoderException("Empty data");
        }

        try {
            String json = new String(data, "UTF-8");
            JsonNode rootNode = objectMapper.readTree(json);

            RawEvent event = new RawEvent();
            
            // 提取标准字段
            extractStandardFields(event, rootNode);
            
            // 提取所有字段到 data 中
            Map<String, Object> allFields = jsonToMap(rootNode);
            event.setData(allFields);

            return event;

        } catch (IOException e) {
            log.error("Failed to parse JSON", e);
            throw new DecoderException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormat() {
        return "JSON";
    }

    /**
     * 提取标准字段
     */
    private void extractStandardFields(RawEvent event, JsonNode rootNode) {
        // event_id
        if (rootNode.has("event_id")) {
            event.setEventId(rootNode.get("event_id").asText());
        } else if (rootNode.has("eventId")) {
            event.setEventId(rootNode.get("eventId").asText());
        }

        // user_id
        if (rootNode.has("user_id")) {
            event.setUserId(rootNode.get("user_id").asText());
        } else if (rootNode.has("userId")) {
            event.setUserId(rootNode.get("userId").asText());
        }

        // timestamp
        if (rootNode.has("timestamp")) {
            event.setTimestamp(rootNode.get("timestamp").asLong());
        }

        // event_type
        if (rootNode.has("event_type")) {
            event.setEventType(rootNode.get("event_type").asText());
        } else if (rootNode.has("eventType")) {
            event.setEventType(rootNode.get("eventType").asText());
        } else if (rootNode.has("type")) {
            event.setEventType(rootNode.get("type").asText());
        }

        // advertiser_id
        if (rootNode.has("advertiser_id")) {
            event.setAdvertiserId(rootNode.get("advertiser_id").asText());
        } else if (rootNode.has("advertiserId")) {
            event.setAdvertiserId(rootNode.get("advertiserId").asText());
        }

        // campaign_id
        if (rootNode.has("campaign_id")) {
            event.setCampaignId(rootNode.get("campaign_id").asText());
        } else if (rootNode.has("campaignId")) {
            event.setCampaignId(rootNode.get("campaignId").asText());
        }
    }

    /**
     * JSON 转 Map
     */
    private Map<String, Object> jsonToMap(JsonNode node) {
        Map<String, Object> map = new HashMap<>();
        
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                map.put(field.getKey(), jsonNodeToObject(field.getValue()));
            }
        }
        
        return map;
    }

    /**
     * JsonNode 转 Object
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node == null) {
            return null;
        } else if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            if (node.isInt() || node.isLong()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            // 数组转 List
            // 简化处理，转为 JSON 字符串
            return node.toString();
        } else if (node.isObject()) {
            // 嵌套对象转 Map
            return jsonToMap(node);
        } else {
            return node.asText();
        }
    }
}
