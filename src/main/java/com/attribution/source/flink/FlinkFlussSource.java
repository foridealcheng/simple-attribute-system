package com.attribution.source.flink;

import com.attribution.source.adapter.FlussSourceAdapter;
import com.attribution.source.adapter.FlussSourceConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Flink Fluss Source
 * 
 * 用于从 Apache Fluss 读取数据到 Flink
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlinkFlussSource extends BaseFlinkSource<org.apache.fluss.client.table.scanner.log.ScanRecord> {

    public FlinkFlussSource(FlussSourceConfig config) {
        super(config, new FlussSourceAdapter());
    }

    /**
     * 创建 Fluss Source
     * 
     * @param config Fluss 配置
     * @return FlinkFlussSource 实例
     */
    public static FlinkFlussSource create(FlussSourceConfig config) {
        return new FlinkFlussSource(config);
    }

    /**
     * 从 Fluss 读取数据到 Flink DataStream
     * 
     * @param env Flink 执行环境
     * @param config Fluss 配置
     * @return RawEvent DataStream
     */
    public static DataStream<RawEvent> fromFluss(
            StreamExecutionEnvironment env,
            FlussSourceConfig config) {
        
        FlinkFlussSource source = create(config);
        return env.fromSource(source, getBoundedness(), "Fluss Source");
    }

    /**
     * 获取 Boundedness
     */
    private static org.apache.flink.api.connector.source.Boundedness getBoundedness() {
        return org.apache.flink.api.connector.source.Boundedness.CONTINUOUS_UNBOUNDED;
    }
}
