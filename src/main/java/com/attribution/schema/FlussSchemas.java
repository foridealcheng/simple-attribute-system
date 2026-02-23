package com.attribution.schema;

import org.apache.fluss.types.DataTypes;
import org.apache.fluss.types.RowType;

/**
 * Fluss Stream Schema Definitions
 * 
 * Defines the schema for all Fluss streams used in the attribution system.
 * 
 * Streams:
 * - click-events-stream: Raw click events from advertisers
 * - conversion-events-stream: User conversion events
 * - attribution-results-success: Successful attribution results
 * - attribution-results-failed: Failed attribution results (for retry)
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class FlussSchemas {

    private FlussSchemas() {
        // Utility class, prevent instantiation
    }

    /**
     * Click Event Stream Schema
     * 
     * Used for: click-events-stream
     * Partition Key: user_id
     * 
     * Schema matches ClickEvent model class
     */
    public static final RowType CLICK_EVENT_SCHEMA = DataTypes.ROW(
        DataTypes.FIELD("event_id", DataTypes.STRING()),
        DataTypes.FIELD("user_id", DataTypes.STRING()),
        DataTypes.FIELD("timestamp", DataTypes.BIGINT()),
        DataTypes.FIELD("advertiser_id", DataTypes.STRING()),
        DataTypes.FIELD("campaign_id", DataTypes.STRING()),
        DataTypes.FIELD("creative_id", DataTypes.STRING()),
        DataTypes.FIELD("placement_id", DataTypes.STRING()),
        DataTypes.FIELD("media_id", DataTypes.STRING()),
        DataTypes.FIELD("click_type", DataTypes.STRING()),
        DataTypes.FIELD("ip_address", DataTypes.STRING()),
        DataTypes.FIELD("user_agent", DataTypes.STRING()),
        DataTypes.FIELD("device_type", DataTypes.STRING()),
        DataTypes.FIELD("os", DataTypes.STRING()),
        DataTypes.FIELD("app_version", DataTypes.STRING()),
        DataTypes.FIELD("attributes", DataTypes.STRING()),
        DataTypes.FIELD("create_time", DataTypes.BIGINT()),
        DataTypes.FIELD("source", DataTypes.STRING())
    );

    /**
     * Conversion Event Stream Schema
     * 
     * Used for: conversion-events-stream
     * Partition Key: user_id
     * 
     * Schema matches ConversionEvent model class
     */
    public static final RowType CONVERSION_EVENT_SCHEMA = DataTypes.ROW(
        DataTypes.FIELD("event_id", DataTypes.STRING()),
        DataTypes.FIELD("user_id", DataTypes.STRING()),
        DataTypes.FIELD("timestamp", DataTypes.BIGINT()),
        DataTypes.FIELD("advertiser_id", DataTypes.STRING()),
        DataTypes.FIELD("campaign_id", DataTypes.STRING()),
        DataTypes.FIELD("conversion_type", DataTypes.STRING()),
        DataTypes.FIELD("conversion_value", DataTypes.DOUBLE()),
        DataTypes.FIELD("currency", DataTypes.STRING()),
        DataTypes.FIELD("transaction_id", DataTypes.STRING()),
        DataTypes.FIELD("product_id", DataTypes.STRING()),
        DataTypes.FIELD("quantity", DataTypes.INT()),
        DataTypes.FIELD("ip_address", DataTypes.STRING()),
        DataTypes.FIELD("user_agent", DataTypes.STRING()),
        DataTypes.FIELD("device_type", DataTypes.STRING()),
        DataTypes.FIELD("os", DataTypes.STRING()),
        DataTypes.FIELD("app_version", DataTypes.STRING()),
        DataTypes.FIELD("attributes", DataTypes.STRING()),
        DataTypes.FIELD("create_time", DataTypes.BIGINT()),
        DataTypes.FIELD("source", DataTypes.STRING())
    );

    /**
     * Attribution Result Stream Schema
     * 
     * Used for: attribution-results-success and attribution-results-failed
     * Partition Key: user_id
     * 
     * Schema matches AttributionResult model class
     */
    public static final RowType ATTRIBUTION_RESULT_SCHEMA = DataTypes.ROW(
        DataTypes.FIELD("result_id", DataTypes.STRING()),
        DataTypes.FIELD("user_id", DataTypes.STRING()),
        DataTypes.FIELD("conversion_id", DataTypes.STRING()),
        DataTypes.FIELD("attribution_model", DataTypes.STRING()),
        DataTypes.FIELD("attributed_clicks", DataTypes.ARRAY(DataTypes.STRING())),
        DataTypes.FIELD("credit_distribution", DataTypes.MAP(DataTypes.STRING(), DataTypes.DOUBLE())),
        DataTypes.FIELD("total_conversion_value", DataTypes.DOUBLE()),
        DataTypes.FIELD("currency", DataTypes.STRING()),
        DataTypes.FIELD("advertiser_id", DataTypes.STRING()),
        DataTypes.FIELD("campaign_id", DataTypes.STRING()),
        DataTypes.FIELD("attribution_timestamp", DataTypes.BIGINT()),
        DataTypes.FIELD("lookback_window_hours", DataTypes.INT()),
        DataTypes.FIELD("status", DataTypes.STRING()),
        DataTypes.FIELD("error_message", DataTypes.STRING()),
        DataTypes.FIELD("retry_count", DataTypes.INT()),
        DataTypes.FIELD("create_time", DataTypes.BIGINT())
    );

    /**
     * Fluss KV Store Schema (for user click session storage)
     * 
     * Used for: user-click-sessions (KV Table)
     * Key: user_id (STRING)
     * Value: JSON array of ClickEvents
     * 
     * This is a simplified schema for KV storage
     */
    public static final RowType USER_CLICK_SESSION_SCHEMA = DataTypes.ROW(
        DataTypes.FIELD("user_id", DataTypes.STRING()),
        DataTypes.FIELD("clicks", DataTypes.ARRAY(
            DataTypes.ROW(
                DataTypes.FIELD("event_id", DataTypes.STRING()),
                DataTypes.FIELD("timestamp", DataTypes.BIGINT()),
                DataTypes.FIELD("campaign_id", DataTypes.STRING()),
                DataTypes.FIELD("creative_id", DataTypes.STRING()),
                DataTypes.FIELD("advertiser_id", DataTypes.STRING())
            )
        )),
        DataTypes.FIELD("session_start_time", DataTypes.BIGINT()),
        DataTypes.FIELD("last_update_time", DataTypes.BIGINT()),
        DataTypes.FIELD("click_count", DataTypes.INT())
    );

    /**
     * Get schema DDL for creating a Fluss table
     * 
     * @param tableName Name of the table
     * @param schema The row schema
     * @param partitionKey Partition key column name
     * @return SQL DDL statement
     */
    public static String getTableDDL(String tableName, RowType schema, String partitionKey) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
        
        // Add fields
        for (int i = 0; i < schema.getFieldCount(); i++) {
            String fieldName = schema.getFieldNames().get(i);
            String fieldType = schema.getFieldTypes().get(i).asSummaryString();
            ddl.append("    ").append(fieldName).append(" ").append(fieldType);
            if (i < schema.getFieldCount() - 1) {
                ddl.append(",");
            }
            ddl.append("\n");
        }
        
        ddl.append(") WITH (\n");
        ddl.append("    'connector' = 'fluss',\n");
        ddl.append("    'partition.key' = '").append(partitionKey).append("'\n");
        ddl.append(")");
        
        return ddl.toString();
    }

    /**
     * Get schema DDL for creating a Fluss KV table
     * 
     * @param tableName Name of the KV table
     * @param schema The row schema
     * @param keyColumn Key column name
     * @return SQL DDL statement
     */
    public static String getKVTableDDL(String tableName, RowType schema, String keyColumn) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
        
        // Add fields
        for (int i = 0; i < schema.getFieldCount(); i++) {
            String fieldName = schema.getFieldNames().get(i);
            String fieldType = schema.getFieldTypes().get(i).asSummaryString();
            ddl.append("    ").append(fieldName).append(" ").append(fieldType);
            if (i < schema.getFieldCount() - 1) {
                ddl.append(",");
            }
            ddl.append("\n");
        }
        
        ddl.append(") WITH (\n");
        ddl.append("    'connector' = 'fluss',\n");
        ddl.append("    'table.kind' = 'PRIMARY KEY',\n");
        ddl.append("    'primary.key' = '").append(keyColumn).append("'\n");
        ddl.append(")");
        
        return ddl.toString();
    }
}
