package com.attribution.validator;

import com.attribution.model.ClickEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Click 事件验证器
 * 
 * 验证 Click 事件的关键字段是否完整
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Slf4j
public class ClickValidator {

    /**
     * 验证 Click 事件是否有效
     * 
     * @param click Click 事件
     * @return true 如果有效
     */
    public static boolean isValid(ClickEvent click) {
        if (click == null) {
            log.debug("Click event is null");
            return false;
        }

        // 必填字段检查
        if (click.getUserId() == null || click.getUserId().trim().isEmpty()) {
            log.debug("Click event missing userId: eventId={}", click.getEventId());
            return false;
        }

        if (click.getEventId() == null || click.getEventId().trim().isEmpty()) {
            log.debug("Click event missing eventId");
            return false;
        }

        if (click.getTimestamp() == null || click.getTimestamp() <= 0) {
            log.debug("Click event has invalid timestamp: eventId={}", click.getEventId());
            return false;
        }

        if (click.getAdvertiserId() == null || click.getAdvertiserId().trim().isEmpty()) {
            log.debug("Click event missing advertiserId: eventId={}", click.getEventId());
            return false;
        }

        // 可选字段检查（根据业务需求调整）
        if (click.getCampaignId() == null || click.getCampaignId().trim().isEmpty()) {
            log.warn("Click event missing campaignId (optional): eventId={}", click.getEventId());
        }

        return true;
    }

    /**
     * 验证 Click 事件并返回详细错误信息
     * 
     * @param click Click 事件
     * @return 验证结果
     */
    public static ValidationResult validateWithReason(ClickEvent click) {
        if (click == null) {
            return ValidationResult.invalid("Click event is null");
        }

        if (click.getUserId() == null || click.getUserId().trim().isEmpty()) {
            return ValidationResult.invalid("Missing userId");
        }

        if (click.getEventId() == null || click.getEventId().trim().isEmpty()) {
            return ValidationResult.invalid("Missing eventId");
        }

        if (click.getTimestamp() == null || click.getTimestamp() <= 0) {
            return ValidationResult.invalid("Invalid timestamp");
        }

        if (click.getAdvertiserId() == null || click.getAdvertiserId().trim().isEmpty()) {
            return ValidationResult.invalid("Missing advertiserId");
        }

        return ValidationResult.valid();
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }
}
