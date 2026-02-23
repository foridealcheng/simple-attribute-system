package com.attribution.source.adapter;

import java.util.Map;
import java.util.Properties;

import lombok.Data;

/**
 * 数据源配置接口
 * 
 * 定义数据源连接的通用配置
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public interface SourceConfig {

    /**
     * 获取数据源类型
     * 
     * @return KAFKA, ROCKETMQ, FLUSS
     */
    String getSourceType();

    /**
     * 获取数据源名称
     * 
     * @return 数据源标识名称
     */
    String getSourceName();

    /**
     * 获取数据格式
     * 
     * @return JSON, PROTOBUF, AVRO
     */
    String getFormat();

    /**
     * 获取 Topic/Stream 名称
     * 
     * @return Topic 或 Stream 名称
     */
    String getTopic();

    /**
     * 获取配置属性
     * 
     * @return 配置 Map
     */
    Map<String, String> getProperties();

    /**
     * 验证配置是否有效
     * 
     * @return 是否有效
     */
    boolean validate();

    /**
     * 转换为 Properties 对象
     * 
     * @return Properties 对象
     */
    Properties toProperties();
}
