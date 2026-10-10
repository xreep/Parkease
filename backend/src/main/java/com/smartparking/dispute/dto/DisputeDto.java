package com.smartparking.dispute.dto;

import com.smartparking.dispute.DisputeCategory;
import com.smartparking.dispute.DisputeResolution;
import com.smartparking.dispute.DisputeStatus;
import com.smartparking.earning.EarningStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A dispute in full (flat: the summary fields first). {@code adminNotes}, {@code refundableRemaining} and the owner's
 * earning for the booking ({@code earningStatus}, {@code earningNet}; null when there is none) are only filled for
 * admins, so the resolve screen can warn when the earning was already paid out.
 */
public record DisputeDto(
        Long id,
        Long bookingId,
        String bookingCode,
        String listingTitle,
        DisputeCategory category,
        DisputeStatus status,
        Instant createdAt,
        Instant resolvedAt,
        String description,
        String raisedByName,
        String ownerResponse,
        Instant ownerRespondedAt,
        DisputeResolution resolution,
        BigDecimal resolutionAmount,
        String adminNotes,
        BigDecimal refundableRemaining,
        EarningStatus earningStatus,
        BigDecimal earningNet) {
}
