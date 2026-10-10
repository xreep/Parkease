package com.smartparking.dispute.dto;

import com.smartparking.dispute.Dispute;
import com.smartparking.dispute.DisputeCategory;
import com.smartparking.dispute.DisputeStatus;
import java.time.Instant;

public record DisputeSummaryDto(Long id, Long bookingId, String bookingCode, String listingTitle,
                                DisputeCategory category, DisputeStatus status, Instant createdAt,
                                Instant resolvedAt) {

    /** Reads the dispute's booking and listing: call within a transaction. */
    public static DisputeSummaryDto from(Dispute d) {
        return new DisputeSummaryDto(d.getId(), d.getBooking().getId(), d.getBooking().getBookingCode(),
                d.getBooking().getListing().getTitle(), d.getCategory(), d.getStatus(), d.getCreatedAt(),
                d.getResolvedAt());
    }
}
