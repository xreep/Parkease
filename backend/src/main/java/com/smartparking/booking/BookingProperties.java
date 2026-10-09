package com.smartparking.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Booking rules from {@code application.yml}. The hold length, approval window and request lead time are the
 * <em>defaults</em> for the admin-editable {@code PlatformSettings}, which is what the booking code reads; only the
 * per-driver hold cap is used from here directly.
 */
@ConfigurationProperties("app.booking")
public record BookingProperties(
        @DefaultValue("10") int holdMinutes,
        @DefaultValue("2") int approvalHours,
        @DefaultValue("3") int maxActiveHolds,
        @DefaultValue("30") int requestMinLeadMinutes) {
}
