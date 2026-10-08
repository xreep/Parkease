package com.smartparking.invoice;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.booking.Booking;
import com.smartparking.payment.Payment;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final Clock clock;

    /** Issues the booking's invoice (numbered {@code INV-<year>-<6-digit sequence>}); returns the existing one if any. */
    @Transactional
    public Invoice issue(Booking booking, Payment payment) {
        return invoices.findByBookingId(booking.getId()).orElseGet(() -> {
            Instant now = clock.instant();
            Invoice invoice = new Invoice();
            invoice.setInvoiceNumber(String.format("INV-%d-%06d",
                    now.atZone(AvailabilityEvaluator.ZONE).getYear(), invoices.nextNumber()));
            invoice.setBooking(booking);
            invoice.setPayment(payment);
            invoice.setIssuedAt(now);
            return invoices.save(invoice);
        });
    }
}
