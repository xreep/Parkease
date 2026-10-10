package com.smartparking.admin.payouts;

import java.math.BigDecimal;

/**
 * An owner with money waiting to be paid out. {@code pendingAmount} and {@code earningsCount} only cover earnings that
 * can be paid now; {@code disputedAmount} is what is held back because the booking has an unresolved dispute.
 * {@code payoutMasked} never reveals the full UPI id or account number.
 */
public record PayoutOwnerDto(Long ownerId, String ownerName, String ownerEmail, BigDecimal pendingAmount,
                             long earningsCount, String payoutMethod, String payoutMasked,
                             BigDecimal disputedAmount) {
}
