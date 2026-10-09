package com.smartparking.user;

import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.web.PageResponse;
import com.smartparking.invoice.Invoice;
import com.smartparking.invoice.InvoiceRepository;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentRepository.Spending;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.review.ReviewPolicy;
import com.smartparking.user.dto.DriverPaymentDto;
import com.smartparking.user.dto.DriverStatsDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A driver's payment history and headline numbers. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DriverAccountService {

    /** Payments that moved money: captured, and those since refunded in part or in full. */
    private static final List<PaymentStatus> MONEY_MOVED = List.of(PaymentStatus.CAPTURED,
            PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final BookingRepository bookings;
    private final Clock clock;

    public PageResponse<DriverPaymentDto> payments(Long driverId, int page, int size) {
        Page<Payment> result = payments.findByBookingDriverIdAndStatusIn(driverId, MONEY_MOVED,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Order.desc("capturedAt"), Sort.Order.desc("id"))));
        Map<Long, String> invoiceNumbers = new HashMap<>();
        if (!result.isEmpty()) {
            for (Invoice i : invoices.findByBookingIdIn(result.getContent().stream()
                    .map(p -> p.getBooking().getId()).toList())) {
                invoiceNumbers.put(i.getBooking().getId(), i.getInvoiceNumber());
            }
        }
        return PageResponse.from(result.map(p -> {
            String invoiceNumber = invoiceNumbers.get(p.getBooking().getId());
            return new DriverPaymentDto(p.getId(), p.getBooking().getId(), p.getBooking().getBookingCode(),
                    p.getBooking().getListing().getTitle(), p.getAmount(), p.getStatus(),
                    p.getBooking().getRefundAmount(), p.getCapturedAt(), invoiceNumber, invoiceNumber != null);
        }));
    }

    public DriverStatsDto stats(Long driverId) {
        Instant now = clock.instant();
        Spending spending = payments.spendingOf(driverId, MONEY_MOVED);
        long seconds = bookings.sumCompletedSeconds(driverId);
        List<Long> reviewable = bookings.findReviewableIds(driverId, now.minus(ReviewPolicy.WINDOW));
        return new DriverStatsDto(
                (int) bookings.countPaidByDriver(driverId, MONEY_MOVED),
                (int) bookings.countByDriverIdAndStatusIn(driverId, List.of(BookingStatus.COMPLETED)),
                spending.getPaid().subtract(spending.getRefunded()).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP),
                reviewable.size(), reviewable.isEmpty() ? null : reviewable.get(0));
    }
}
