package com.smartparking.admin.payouts;

import com.smartparking.earning.EarningStatus;
import com.smartparking.owner.dashboard.dto.OwnerEarningDto;
import java.math.BigDecimal;
import java.time.Instant;

/** An earning waiting for payout (the owner's earning row) and whether an unresolved dispute holds it back. */
public record PayoutEarningDto(
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
        String payoutReference,
        boolean disputed) {

    static PayoutEarningDto of(OwnerEarningDto e, boolean disputed) {
        return new PayoutEarningDto(e.id(), e.bookingId(), e.bookingCode(), e.listingTitle(), e.startTime(),
                e.endTime(), e.gross(), e.commission(), e.net(), e.status(), e.paidAt(), e.payoutReference(), disputed);
    }
}
