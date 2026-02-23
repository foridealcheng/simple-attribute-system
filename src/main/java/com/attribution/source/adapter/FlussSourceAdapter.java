package com.attribution.source.adapter;

import com.attribution.model.RawEvent;
import com.attribution.decoder.FormatDecoder;
import com.attribution.decoder.DecoderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.client.table.scanner.log.ScanRecord;
import org.apache.fluss.row.BinaryRow;

import java.util.*;

/**
 * Fluss 数据源适配器实现
 * 
 * 从 Apache Fluss 表/Stream 消费消息，转换为 RawEvent
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Slf4j
public class FlussSourceAdapter implements SourceAdapter<ScanRecord> {

    private Connection connection;
    private Table table;
    private LogScanner scanner;
    private FlussSourceConfig config;
    private FormatDecoder decoder;
    private volatile boolean running = false;

    @Override
    public void init(SourceConfig sourceConfig) {
        log.info("Initializing Fluss Source Adapter...");
        
        this.config = (FlussSourceConfig) sourceConfig;
        
        if (!config.validate()) {
            throw new IllegalArgumentException("Invalid Fluss source configuration");
        }
        
        try {
            // 初始化 Fluss 连接
            Properties props = config.toProperties();
            this.connection = ConnectionFactory.createConnection(props);
            
            // 获取表对象
            this.table = connection.getTable(
                config.getDatabaseName(), 
                config.getTableName()
            );
            
            // 初始化 Log Scanner
            this.scanner = table.newScan()
                .batchSize(config.getBatchSize())
                .batchTimeoutMs(config.getBatchTimeoutMs())
                .createLogScanner();
            
            // 初始化格式解码器
            this.decoder = DecoderFactory.createDecoder(config.getFormat(), config);
            
            log.info("Fluss Source Adapter initialized successfully");
            log.info("  Gateway: {}", config.getGatewayAddress());
            log.info("  Database: {}", config.getDatabaseName());
            log.info("  Table: {}", config.getTableName());
            log.info("  Format: {}", config.getFormat());
            
        } catch (Exception e) {
            log.error("Failed to initialize Fluss connection", e);
            throw new RuntimeException("Failed to initialize Fluss connection", e);
        }
    }

    @Override
    public Iterable<RawEvent> consume() {
        if (!running) {
            running = true;
        }
        
        return () -> {
            try {
                // 扫描获取数据
                List<ScanRecord> records = scanner.poll();
                
                if (records == null || records.isEmpty()) {
                    return Collections.emptyIterator();
                }
                
                return records.stream()
                    .map(record -> {
                        try {
                            BinaryRow row = record.getRow();
                            byte[] data = row.toBytes();
                            
                            RawEvent event = decoder.decode(data);
                            event.setMetadata(extractMetadata(record));
                            return event;
                        } catch (Exception e) {
                            log.error("Failed to decode Fluss record", e);
                            return null;
                        }
                    })
                    .filter(event -> event != null)
                    .iterator();
                    
            } catch (Exception e) {
                log.error("Error polling records from Fluss", e);
                return Collections.emptyIterator();
            }
        };
    }

    @Override
    public void commitOffset(Map<String, Long> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        
        log.debug("Committing Fluss offsets: {}", offsets);
        // Fluss 自动管理偏移量
    }

    @Override
    public String getSourceType() {
        return FlussSourceConfig.SOURCE_TYPE;
    }

    @Override
    public void close() {
        log.info("Closing Fluss Source Adapter...");
        running = false;
        
        if (scanner != null) {
            try {
                scanner.close();
            } catch (Exception e) {
                log.error("Error closing Fluss scanner", e);
            }
        }
        
        if (table != null) {
            try {
                table.close();
            } catch (Exception e) {
                log.error("Error closing Fluss table", e);
            }
        }
        
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.error("Error closing Fluss connection", e);
            }
        }
        
        log.info("Fluss Source Adapter closed");
    }

    @Override
    public boolean isHealthy() {
        if (connection == null) {
            return false;
        }
        
        try {
            // 简单健康检查：尝试获取表信息
            table.getSchema();
            return true;
        } catch (Exception e) {
            log.warn("Fluss connection health check failed", e);
            return false;
        }
    }

    /**
     * 提取记录元数据
     */
    private Map<String, Object> extractMetadata(ScanRecord record) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "FLUSS");
        metadata.put("bucket", record.getBucket());
        metadata.put("offset", record.getOffset());
        metadata.put("timestamp", record.getTimestamp());
        return metadata;
    }
}
