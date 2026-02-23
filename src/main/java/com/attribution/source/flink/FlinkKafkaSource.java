package com.attribution.source.flink;

import com.attribution.model.RawEvent;
import com.attribution.source.adapter.KafkaSourceAdapter;
import com.attribution.source.adapter.KafkaSourceConfig;
import com.attribution.source.adapter.SourceAdapter;
import com.attribution.source.adapter.SourceConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Flink Kafka Source (Flink 1.17 简化版)
 * 
 * 用于从 Kafka 读取数据到 Flink
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlinkKafkaSource extends BaseFlinkSource {

    public FlinkKafkaSource(KafkaSourceConfig config) {
        super(config, new KafkaSourceAdapter());
    }

    /**
     * 从 Kafka 读取数据到 Flink DataStream
     */
    public static DataStream<RawEvent> fromKafka(
            StreamExecutionEnvironment env,
            KafkaSourceConfig config) {
        
        return createStream(env, config, new KafkaSourceAdapter(), "Kafka Source");
    }
}
