package com.attribution;

import com.attribution.engine.AttributionEngine;
import com.attribution.flink.AttributionProcessFunction;
import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.model.RawEvent;
import com.attribution.sink.AttributionResultSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;

/**
 * 归因系统 Flink 作业入口 (使用 Flink Kafka Connector)
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionJob {

    public static void main(String[] args) throws Exception {
        log.info("Starting Attribution Job...");

        // 1. 创建 Flink 执行环境
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        
        // 启用 Checkpoint
        env.enableCheckpointing(60000); // 60 秒

        // 2. 创建 Kafka Source
        KafkaSource<String> clickSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("click-events")
            .setGroupId("attribution-click-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        KafkaSource<String> conversionSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:29092")
            .setTopics("conversion-events")
            .setGroupId("attribution-conversion-group")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        // 3. 创建数据流
        ObjectMapper mapper = new ObjectMapper();
        
        DataStream<ClickEvent> clickStream = env.fromSource(clickSource, WatermarkStrategy.noWatermarks(), "Click Source")
            .map(json -> {
                try {
                    return mapper.readValue(json, ClickEvent.class);
                } catch (Exception e) {
                    log.error("Failed to parse ClickEvent JSON", e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Click Event Mapper")
            .uid("click-mapper");

        DataStream<ConversionEvent> conversionStream = env.fromSource(conversionSource, WatermarkStrategy.noWatermarks(), "Conversion Source")
            .map(json -> {
                try {
                    return mapper.readValue(json, ConversionEvent.class);
                } catch (Exception e) {
                    log.error("Failed to parse ConversionEvent JSON", e);
                    return null;
                }
            })
            .filter(event -> event != null)
            .name("Conversion Event Mapper")
            .uid("conversion-mapper");

        // 4. 创建归因引擎
        AttributionEngine engine = new AttributionEngine(24, "LAST_CLICK", true);

        // 5. 连接两个流并执行归因
        DataStream<AttributionResult> resultStream = clickStream
            .connect(conversionStream)
            .keyBy(
                click -> click.getUserId(),
                conversion -> conversion.getUserId()
            )
            .process(new AttributionProcessFunction(engine))
            .name("Attribution Processor")
            .uid("attribution-processor");

        // 6. 写入结果（控制台输出）
        resultStream
            .addSink(new AttributionResultSink("CONSOLE", null, null, null))
            .name("Attribution Result Sink")
            .uid("result-sink");

        // 7. 执行作业
        env.execute("Simple Attribute System");
        
        log.info("Attribution Job submitted successfully");
    }
}
