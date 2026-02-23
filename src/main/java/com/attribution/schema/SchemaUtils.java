package com.attribution.schema;

import com.attribution.model.ClickEvent;
import com.attribution.model.ConversionEvent;
import com.attribution.model.AttributionResult;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.types.RowType;

/**
 * Schema Utilities for converting between models and Fluss rows
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class SchemaUtils {

    private SchemaUtils() {
        // Utility class
    }

    /**
     * Convert ClickEvent to GenericRow
     * 
     * @param clickEvent The ClickEvent model
     * @return GenericRow matching CLICK_EVENT_SCHEMA
     */
    public static GenericRow toClickEventRow(ClickEvent clickEvent) {
        GenericRow row = new GenericRow(17);
        row.setField(0, clickEvent.getEventId());
        row.setField(1, clickEvent.getUserId());
        row.setField(2, clickEvent.getTimestamp());
        row.setField(3, clickEvent.getAdvertiserId());
        row.setField(4, clickEvent.getCampaignId());
        row.setField(5, clickEvent.getCreativeId());
        row.setField(6, clickEvent.getPlacementId());
        row.setField(7, clickEvent.getMediaId());
        row.setField(8, clickEvent.getClickType());
        row.setField(9, clickEvent.getIpAddress());
        row.setField(10, clickEvent.getUserAgent());
        row.setField(11, clickEvent.getDeviceType());
        row.setField(12, clickEvent.getOs());
        row.setField(13, clickEvent.getAppVersion());
        row.setField(14, clickEvent.getAttributes());
        row.setField(15, clickEvent.getCreateTime());
        row.setField(16, clickEvent.getSource());
        return row;
    }

    /**
     * Convert ConversionEvent to GenericRow
     * 
     * @param conversionEvent The ConversionEvent model
     * @return GenericRow matching CONVERSION_EVENT_SCHEMA
     */
    public static GenericRow toConversionEventRow(ConversionEvent conversionEvent) {
        GenericRow row = new GenericRow(19);
        row.setField(0, conversionEvent.getEventId());
        row.setField(1, conversionEvent.getUserId());
        row.setField(2, conversionEvent.getTimestamp());
        row.setField(3, conversionEvent.getAdvertiserId());
        row.setField(4, conversionEvent.getCampaignId());
        row.setField(5, conversionEvent.getConversionType());
        row.setField(6, conversionEvent.getConversionValue());
        row.setField(7, conversionEvent.getCurrency());
        row.setField(8, conversionEvent.getTransactionId());
        row.setField(9, conversionEvent.getProductId());
        row.setField(10, conversionEvent.getQuantity());
        row.setField(11, conversionEvent.getIpAddress());
        row.setField(12, conversionEvent.getUserAgent());
        row.setField(13, conversionEvent.getDeviceType());
        row.setField(14, conversionEvent.getOs());
        row.setField(15, conversionEvent.getAppVersion());
        row.setField(16, conversionEvent.getAttributes());
        row.setField(17, conversionEvent.getCreateTime());
        row.setField(18, conversionEvent.getSource());
        return row;
    }

    /**
     * Convert AttributionResult to GenericRow
     * 
     * @param result The AttributionResult model
     * @return GenericRow matching ATTRIBUTION_RESULT_SCHEMA
     */
    public static GenericRow toAttributionResultRow(AttributionResult result) {
        GenericRow row = new GenericRow(16);
        row.setField(0, result.getResultId());
        row.setField(1, result.getUserId());
        row.setField(2, result.getConversionId());
        row.setField(3, result.getAttributionModel());
        row.setField(4, result.getAttributedClicks().toArray(new String[0]));
        row.setField(5, result.getCreditDistribution());
        row.setField(6, result.getTotalConversionValue());
        row.setField(7, result.getCurrency());
        row.setField(8, result.getAdvertiserId());
        row.setField(9, result.getCampaignId());
        row.setField(10, result.getAttributionTimestamp());
        row.setField(11, result.getLookbackWindowHours());
        row.setField(12, result.getStatus());
        row.setField(13, result.getErrorMessage());
        row.setField(14, result.getRetryCount());
        row.setField(15, result.getCreateTime());
        return row;
    }
}
