package com.smartparking.admin.payments;

import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminPaymentDto(Long id, Long bookingId, String bookingCode, String driverEmail,
                              PaymentProviderType provider, String providerOrderId, String providerPaymentId,
                              PaymentStatus status, BigDecimal amount, BigDecimal refundAmount, Instant createdAt,
                              Instant capturedAt) {
}
