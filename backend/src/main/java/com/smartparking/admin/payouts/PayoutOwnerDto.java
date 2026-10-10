package com.smartparking.admin.payouts;

import java.math.BigDecimal;

/** An owner with money waiting to be paid out. {@code payoutMasked} never reveals the full UPI id or account number. */
public record PayoutOwnerDto(Long ownerId, String ownerName, String ownerEmail, BigDecimal pendingAmount,
                             long earningsCount, String payoutMethod, String payoutMasked) {
}
