package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 归因结果模型
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    // Getters/Setters for Lombok @Builder compatibility
    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
    
    public String getResultId() {
        return this.resultId;
    }

    private String resultId;
    private String conversionId;
    private String userId;
    private String advertiserId;
    private String campaignId;
    private String attributionModel;
    private List<String> attributedClicks;
    private Map<String, Double> creditDistribution;
    private Double totalConversionValue;
    private String currency;
    private Long attributionTimestamp;
    private Integer lookbackWindowHours;
    private String status;
    private String errorMessage;
    private Integer retryCount;
    private Long createTime;
}
