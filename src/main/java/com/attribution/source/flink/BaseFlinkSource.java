package com.attribution.source.flink;

import com.attribution.model.RawEvent;
import com.attribution.source.adapter.SourceAdapter;
import com.attribution.source.adapter.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.connector.base.source.reader.SourceReaderBase;
import org.apache.flink.connector.base.source.reader.split.SourceSplitBase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flink Source Connector 基类
 * 
 * 基于 Flink Connector API 实现的通用 Source
 * 支持 Kafka、RocketMQ、Fluss 等数据源
 * 
 * @param <T> 原始消息类型
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public abstract class BaseFlinkSource<T> implements org.apache.flink.api.connector.source.Source<RawEvent, SourceSplitBase, Map<String, Long>> {

    protected final SourceConfig config;
    protected final SourceAdapter<T> adapter;

    public BaseFlinkSource(SourceConfig config, SourceAdapter<T> adapter) {
        this.config = config;
        this.adapter = adapter;
    }

    @Override
    public Boundedness getBoundedness() {
        // 默认为连续流（无界）
        return Boundedness.CONTINUOUS_UNBOUNDED;
    }

    @Override
    public SourceReader<RawEvent, SourceSplitBase> createReader(SourceReaderContext readerContext) throws Exception {
        log.info("Creating Flink Source Reader for {}", config.getSourceType());
        
        // 初始化适配器
        adapter.init(config);
        
        return new SourceReaderBase<RawEvent, SourceSplitBase, Map<String, Long>>() {
            
            private final Map<String, Long> offsets = new ConcurrentHashMap<>();
            
            @Override
            public void start() {
                log.info("Flink Source Reader started for {}", config.getTopic());
            }

            @Override
            public void pollNext(org.apache.flink.api.connector.source.SplitOutput<RawEvent> output) throws Exception {
                Iterable<RawEvent> events = adapter.consume();
                
                int count = 0;
                for (RawEvent event : events) {
                    output.collect(event);
                    count++;
                    
                    // 记录偏移量（简化实现）
                    if (event.getMetadata("offset") != null) {
                        String partition = event.getMetadata("partition") != null ? 
                            event.getMetadata("partition").toString() : "0";
                        Long offset = Long.parseLong(event.getMetadata("offset").toString());
                        offsets.put(partition, offset);
                    }
                }
                
                if (count == 0) {
                    // 没有数据时，短暂等待
                    Thread.sleep(100);
                }
            }

            @Override
            public Map<String, Long> snapshotState(long checkpointId) {
                log.debug("Snapshotting state for checkpoint {}", checkpointId);
                return new ConcurrentHashMap<>(offsets);
            }

            @Override
            public void notifyCheckpointComplete(long checkpointId) {
                log.debug("Checkpoint {} completed", checkpointId);
                // 提交偏移量
                adapter.commitOffset(offsets);
            }

            @Override
            public void close() throws Exception {
                log.info("Closing Flink Source Reader");
                adapter.close();
            }
        };
    }

    @Override
    public TypeInformation<RawEvent> getProducedType() {
        return TypeInformation.of(RawEvent.class);
    }
}
