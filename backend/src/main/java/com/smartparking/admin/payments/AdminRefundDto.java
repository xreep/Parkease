package com.smartparking.admin.payments;

import com.smartparking.payment.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminRefundDto(Long id, Long paymentId, Long bookingId, String bookingCode, BigDecimal amount,
                             RefundStatus status, int attempts, String providerRefundId, String reason,
                             Instant createdAt, String lastError) {
}
