package com.smartparking.user.dto;

import com.smartparking.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** A payment that reached CAPTURED or later; {@code refundAmount} is what the booking was refunded so far. */
public record DriverPaymentDto(
        Long id,
        Long bookingId,
        String bookingCode,
        String listingTitle,
        BigDecimal amount,
        PaymentStatus status,
        BigDecimal refundAmount,
        Instant paidAt,
        String invoiceNumber,
        boolean receiptAvailable) {
}
