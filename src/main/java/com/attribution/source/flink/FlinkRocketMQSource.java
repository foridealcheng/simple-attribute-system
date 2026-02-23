package com.attribution.source.flink;

import com.attribution.source.adapter.RocketMQSourceAdapter;
import com.attribution.source.adapter.RocketMQSourceConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Flink RocketMQ Source
 * 
 * 用于从 RocketMQ 读取数据到 Flink
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlinkRocketMQSource extends BaseFlinkSource<org.apache.rocketmq.common.message.MessageExt> {

    public FlinkRocketMQSource(RocketMQSourceConfig config) {
        super(config, new RocketMQSourceAdapter());
    }

    /**
     * 创建 RocketMQ Source
     * 
     * @param config RocketMQ 配置
     * @return FlinkRocketMQSource 实例
     */
    public static FlinkRocketMQSource create(RocketMQSourceConfig config) {
        return new FlinkRocketMQSource(config);
    }

    /**
     * 从 RocketMQ 读取数据到 Flink DataStream
     * 
     * @param env Flink 执行环境
     * @param config RocketMQ 配置
     * @return RawEvent DataStream
     */
    public static DataStream<RawEvent> fromRocketMQ(
            StreamExecutionEnvironment env,
            RocketMQSourceConfig config) {
        
        FlinkRocketMQSource source = create(config);
        return env.fromSource(source, getBoundedness(), "RocketMQ Source");
    }

    /**
     * 获取 Boundedness
     */
    private static org.apache.flink.api.connector.source.Boundedness getBoundedness() {
        return org.apache.flink.api.connector.source.Boundedness.CONTINUOUS_UNBOUNDED;
    }
}
