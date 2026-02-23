package com.attribution.source.flink;

import com.attribution.model.RawEvent;
import com.attribution.source.adapter.SourceAdapter;
import com.attribution.source.adapter.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;

/**
 * Flink Source 基类 (Flink 1.17 简化版)
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class BaseFlinkSource extends RichSourceFunction<RawEvent> {

    protected final SourceConfig config;
    protected final SourceAdapter<?> adapter;
    protected volatile boolean isRunning = true;

    public BaseFlinkSource(SourceConfig config, SourceAdapter<?> adapter) {
        this.config = config;
        this.adapter = adapter;
    }

    @Override
    public void run(SourceContext<RawEvent> ctx) throws Exception {
        log.info("Starting Flink Source for {}", config.getSourceType());
        
        adapter.init(config);
        
        while (isRunning) {
            Iterable<RawEvent> events = adapter.consume();
            
            for (RawEvent event : events) {
                synchronized (ctx.getCheckpointLock()) {
                    ctx.collect(event);
                }
            }
            
            Thread.sleep(100);
        }
    }

    @Override
    public void cancel() {
        isRunning = false;
        adapter.close();
    }

    /**
     * 创建 DataStream
     */
    public static DataStream<RawEvent> createStream(
            StreamExecutionEnvironment env,
            SourceConfig config,
            SourceAdapter<?> adapter,
            String sourceName) {
        
        BaseFlinkSource source = new BaseFlinkSource(config, adapter);
        return env.addSource(source)
            .name(sourceName)
            .uid(sourceName.toLowerCase().replace(" ", "-"))
            .returns(TypeInformation.of(RawEvent.class));
    }
}
