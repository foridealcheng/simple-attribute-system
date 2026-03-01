package com.attribution.sink;

import com.attribution.model.AttributionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/**
 * 归因结果输出 Sink (Flink 1.17 简化版)
 * 
 * 支持多种输出目标：
 * - 控制台（调试用）
 * - JDBC 数据库
 * - 文件系统
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class AttributionResultSink extends RichSinkFunction<AttributionResult> {

    private static final long serialVersionUID = 1L;

    private final String sinkType;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    
    private transient ObjectMapper objectMapper;
    private transient Connection dbConnection;

    public AttributionResultSink() {
        this("CONSOLE", null, null, null);
    }

    public AttributionResultSink(String sinkType, String jdbcUrl, String jdbcUser, String jdbcPassword) {
        this.sinkType = sinkType;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        
        log.info("AttributionResultSink initialized: type={}", sinkType);
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        objectMapper = new ObjectMapper();
        
        if ("JDBC".equalsIgnoreCase(sinkType) && jdbcUrl != null) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            dbConnection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
            log.info("Database connection established");
        }
    }

    @Override
    public void invoke(AttributionResult result, Context context) throws Exception {
        log.debug("Writing attribution result: resultId={}, status={}", 
            result.getResultId(), result.getStatus());
        
        switch (sinkType.toUpperCase()) {
            case "CONSOLE":
                writeConsole(result);
                break;
            
            case "JDBC":
                writeJdbc(result);
                break;
            
            case "FLUSS_MQ":
            case "KAFKA":
                // TODO: 实现消息队列写入
                log.info("Message queue output not yet implemented");
                break;
            
            default:
                log.warn("Unknown sink type: {}, defaulting to console", sinkType);
                writeConsole(result);
        }
    }

    /**
     * 写入控制台（调试用）
     */
    private void writeConsole(AttributionResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            System.out.println(json);
        } catch (Exception e) {
            log.error("Failed to serialize result", e);
        }
    }

    /**
     * 写入数据库
     */
    private void writeJdbc(AttributionResult result) throws Exception {
        if (dbConnection == null) {
            throw new IllegalStateException("Database connection not initialized");
        }

        String sql = "INSERT INTO attribution_results " +
            "(result_id, conversion_id, user_id, attribution_model, total_value, " +
            "currency, advertiser_id, campaign_id, status, create_time) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, result.getResultId());
            stmt.setString(2, result.getConversionId());
            stmt.setString(3, result.getUserId());
            stmt.setString(4, result.getAttributionModel());
            stmt.setDouble(5, result.getTotalConversionValue());
            stmt.setString(6, result.getCurrency());
            stmt.setString(7, result.getAdvertiserId());
            stmt.setString(8, result.getCampaignId());
            stmt.setString(9, result.getStatus());
            stmt.setLong(10, result.getCreateTime());
            
            stmt.executeUpdate();
        }
    }

    @Override
    public void close() throws Exception {
        if (dbConnection != null && !dbConnection.isClosed()) {
            dbConnection.close();
            log.info("Database connection closed");
        }
        super.close();
    }
}
