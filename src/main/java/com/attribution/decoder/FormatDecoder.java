package com.attribution.decoder;

import com.attribution.model.RawEvent;

/**
 * 格式解码器接口
 * 
 * 将不同格式的原始数据解码为 RawEvent
 * 支持 JSON、Protobuf、Avro 等格式
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public interface FormatDecoder {

    /**
     * 解码原始字节数据为 RawEvent
     * 
     * @param data 原始字节数据
     * @return RawEvent 对象
     * @throws DecoderException 解码失败时抛出
     */
    RawEvent decode(byte[] data) throws DecoderException;

    /**
     * 获取解码器支持的格式
     * 
     * @return 格式名称 (JSON/PROTOBUF/AVRO)
     */
    String getFormat();

    /**
     * 初始化解码器
     * 
     * @param config 配置参数
     */
    default void init(Object config) {
        // 可选的初始化逻辑
    }

    /**
     * 关闭解码器，释放资源
     */
    default void close() {
        // 可选的清理逻辑
    }
}
