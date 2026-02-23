package com.attribution.source.flink;

import com.attribution.model.RawEvent;
import com.attribution.source.adapter.RocketMQSourceAdapter;
import com.attribution.source.adapter.RocketMQSourceConfig;
import com.attribution.source.adapter.SourceAdapter;
import com.attribution.source.adapter.SourceConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Flink RocketMQ Source (Flink 1.17 简化版)
 * 
 * 用于从 RocketMQ 读取数据到 Flink
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlinkRocketMQSource extends BaseFlinkSource {

    public FlinkRocketMQSource(RocketMQSourceConfig config) {
        super(config, new RocketMQSourceAdapter());
    }

    /**
     * 从 RocketMQ 读取数据到 Flink DataStream
     */
    public static DataStream<RawEvent> fromRocketMQ(
            StreamExecutionEnvironment env,
            RocketMQSourceConfig config) {
        
        return createStream(env, config, new RocketMQSourceAdapter(), "RocketMQ Source");
    }
}
