package com.attribution.decoder;

import com.attribution.source.adapter.SourceConfig;

/**
 * 解码器工厂
 * 
 * 根据格式类型创建对应的解码器实例
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class DecoderFactory {

    private DecoderFactory() {
        // 工具类，防止实例化
    }

    /**
     * 创建格式解码器
     * 
     * @param format 格式类型：JSON, PROTOBUF, AVRO
     * @param config 数据源配置
     * @return 对应的解码器实例
     * @throws IllegalArgumentException 不支持的格式
     */
    public static FormatDecoder createDecoder(String format, SourceConfig config) {
        if (format == null || format.trim().isEmpty()) {
            throw new IllegalArgumentException("Format cannot be null or empty");
        }

        String normalizedFormat = format.toUpperCase().trim();

        switch (normalizedFormat) {
            case "JSON":
                return new JsonDecoder();
            
            case "PROTOBUF":
            case "PB":
                return new ProtobufDecoder();
            
            case "AVRO":
                return new AvroDecoder();
            
            default:
                throw new IllegalArgumentException(
                    "Unsupported format: " + format + 
                    ". Supported formats: JSON, PROTOBUF, AVRO"
                );
        }
    }

    /**
     * 创建格式解码器（简化版，不需要配置）
     * 
     * @param format 格式类型
     * @return 对应的解码器实例
     */
    public static FormatDecoder createDecoder(String format) {
        return createDecoder(format, null);
    }

    /**
     * 检查格式是否支持
     * 
     * @param format 格式类型
     * @return 是否支持
     */
    public static boolean isSupported(String format) {
        if (format == null) {
            return false;
        }
        
        String normalizedFormat = format.toUpperCase().trim();
        return "JSON".equals(normalizedFormat) ||
               "PROTOBUF".equals(normalizedFormat) ||
               "PB".equals(normalizedFormat) ||
               "AVRO".equals(normalizedFormat);
    }
}
