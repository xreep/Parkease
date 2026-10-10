package com.smartparking.dispute.dto;

import com.smartparking.dispute.DisputeCategory;
import com.smartparking.dispute.DisputeResolution;
import com.smartparking.dispute.DisputeStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A dispute in full (flat: the summary fields first). {@code adminNotes} and {@code refundableRemaining} are only
 * filled for admins.
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
        BigDecimal refundableRemaining) {
}
