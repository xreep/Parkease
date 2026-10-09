package com.smartparking.owner.dashboard.dto;

import com.smartparking.earning.EarningStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OwnerEarningDto(
        Long id,
        Long bookingId,
        String bookingCode,
        String listingTitle,
        Instant startTime,
        Instant endTime,
        BigDecimal gross,
        BigDecimal commission,
        BigDecimal net,
        EarningStatus status,
        Instant paidAt,
        String payoutReference) {
}
