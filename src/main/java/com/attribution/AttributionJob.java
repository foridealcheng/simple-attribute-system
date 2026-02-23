package com.attribution;

import com.attribution.engine.AttributionEngine;
import com.attribution.flink.AttributionProcessFunction;
import com.attribution.model.AttributionResult;
import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.sink.AttributionResultSink;
import com.attribution.source.flink.FlinkFlussSource;
import com.attribution.source.adapter.FlussSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;

/**
 * 归因系统 Flink 作业入口
 * 
 * 主作业流程：
 * 1. 从 Fluss 读取 Click 和 Conversion 事件
 * 2. 通过 KeyedCoProcessFunction 进行归因计算
 * 3. 将结果写入 Fluss 消息队列
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
        env.setParallelism(4);
        
        // 启用 Checkpoint
        env.enableCheckpointing(60000); // 60 秒

        // 2. 配置数据源
        FlussSourceConfig clickConfig = FlussSourceConfig.builder()
            .gatewayAddress("localhost:9110")
            .databaseName("attribution")
            .tableName("click_events")
            .format("JSON")
            .build();

        FlussSourceConfig conversionConfig = FlussSourceConfig.builder()
            .gatewayAddress("localhost:9110")
            .databaseName("attribution")
            .tableName("conversion_events")
            .format("JSON")
            .build();

        // 3. 创建数据流
        DataStream<ClickEvent> clickStream = FlinkFlussSource.fromFluss(env, clickConfig)
            .map(raw -> raw.toClickEvent())
            .name("Click Event Mapper")
            .uid("click-mapper");

        DataStream<ConversionEvent> conversionStream = FlinkFlussSource.fromFluss(env, conversionConfig)
            .map(raw -> raw.toConversionEvent())
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

        // 6. 写入结果
        resultStream
            .addSink(new AttributionResultSink())
            .name("Attribution Result Sink")
            .uid("result-sink");

        // 7. 执行作业
        env.execute("Simple Attribute System");
        
        log.info("Attribution Job submitted successfully");
    }
}
