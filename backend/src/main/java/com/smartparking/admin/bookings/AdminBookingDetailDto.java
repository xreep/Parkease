package com.smartparking.admin.bookings;

import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The summary fields plus pricing, payment, refunds, timeline and disputes (flat JSON). */
public record AdminBookingDetailDto(
        Long id,
        String bookingCode,
        BookingStatus status,
        String listingTitle,
        String cityName,
        String driverName,
        String driverEmail,
        String ownerName,
        Instant startTime,
        Instant endTime,
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        PaymentStatus paymentStatus,
        Instant createdAt,
        Long listingId,
        String slotLabel,
        BigDecimal baseAmount,
        BigDecimal platformFee,
        BigDecimal gstAmount,
        String cancelReason,
        BookingActor cancelledBy,
        PaymentInfo payment,
        List<RefundInfo> refunds,
        List<BookingDetailDto.Event> events,
        List<DisputeSummaryDto> disputes) {

    public record PaymentInfo(Long id, PaymentProviderType provider, String providerOrderId, String providerPaymentId,
                              PaymentStatus status, BigDecimal amount, Instant capturedAt) {
    }

    public record RefundInfo(Long id, BigDecimal amount, RefundStatus status, int attempts, String providerRefundId,
                             String reason, Instant createdAt) {
    }
}
