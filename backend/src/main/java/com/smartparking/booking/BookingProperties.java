package com.smartparking.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Booking rules: how long an unpaid slot hold lasts, the owner approval window and the per-driver hold cap. */
@ConfigurationProperties("app.booking")
public record BookingProperties(
        @DefaultValue("10") int holdMinutes,
        @DefaultValue("2") int approvalHours,
        @DefaultValue("3") int maxActiveHolds) {
}
