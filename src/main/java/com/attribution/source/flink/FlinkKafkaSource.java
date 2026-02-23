package com.attribution.source.flink;

import com.attribution.source.adapter.KafkaSourceAdapter;
import com.attribution.source.adapter.KafkaSourceConfig;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Flink Kafka Source
 * 
 * 用于从 Kafka 读取数据到 Flink
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlinkKafkaSource extends BaseFlinkSource<org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>> {

    public FlinkKafkaSource(KafkaSourceConfig config) {
        super(config, new KafkaSourceAdapter());
    }

    /**
     * 创建 Kafka Source
     * 
     * @param config Kafka 配置
     * @return FlinkKafkaSource 实例
     */
    public static FlinkKafkaSource create(KafkaSourceConfig config) {
        return new FlinkKafkaSource(config);
    }

    /**
     * 从 Kafka 读取数据到 Flink DataStream
     * 
     * @param env Flink 执行环境
     * @param config Kafka 配置
     * @return RawEvent DataStream
     */
    public static DataStream<RawEvent> fromKafka(
            StreamExecutionEnvironment env,
            KafkaSourceConfig config) {
        
        FlinkKafkaSource source = create(config);
        return env.fromSource(source, getBoundedness(), "Kafka Source");
    }

    /**
     * 获取 Boundedness
     */
    private static org.apache.flink.api.connector.source.Boundedness getBoundedness() {
        return org.apache.flink.api.connector.source.Boundedness.CONTINUOUS_UNBOUNDED;
    }
}
