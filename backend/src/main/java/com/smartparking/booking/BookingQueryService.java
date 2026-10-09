package com.smartparking.booking;

import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.BookingSummaryDto;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.web.PageResponse;
import com.smartparking.invoice.Invoice;
import com.smartparking.invoice.InvoiceRepository;
import com.smartparking.invoice.ReceiptPdf;
import com.smartparking.pricing.PricingProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A driver's read-only view of their own bookings: history lists, one booking's details and the PDF receipt. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingQueryService {

    /** A rendered receipt and the file name it should be downloaded as. */
    public record Receipt(String fileName, byte[] pdf) {
    }

    private enum View { UPCOMING, PAST, ALL }

    private final BookingRepository bookings;
    private final InvoiceRepository invoices;
    private final BookingMapper mapper;
    private final PricingProperties pricing;
    private final Clock clock;

    /**
     * {@code upcoming} (default): not ended and paid or on a running payment hold, soonest first. {@code past}: the
     * rest, newest first. {@code all}: everything, newest first.
     */
    public PageResponse<BookingSummaryDto> list(Long driverId, String view, int page, int size) {
        Instant now = clock.instant();
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        Sort newestFirst = Sort.by(Sort.Order.desc("startTime"), Sort.Order.desc("id"));
        Page<Booking> result = switch (parseView(view)) {
            case UPCOMING -> bookings.findUpcomingForDriver(driverId, now, paged(pageNumber, pageSize,
                    Sort.by("startTime", "id")));
            case PAST -> bookings.findPastForDriver(driverId, now, paged(pageNumber, pageSize, newestFirst));
            case ALL -> bookings.findByDriverId(driverId, paged(pageNumber, pageSize, newestFirst));
        };
        return new PageResponse<>(mapper.toSummaries(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public BookingDetailDto detail(Long driverId, Long bookingId) {
        return mapper.toDetail(owned(driverId, bookingId));
    }

    /** The tax invoice / receipt PDF; 409 {@code NOT_PAID} while the booking has no invoice. */
    public Receipt receipt(Long driverId, Long bookingId) {
        Booking booking = owned(driverId, bookingId);
        Invoice invoice = invoices.findByBookingId(booking.getId())
                .orElseThrow(() -> ApiException.conflict("NOT_PAID", "There is no receipt until the booking is paid"));
        return new Receipt("ParkEase-" + invoice.getInvoiceNumber() + ".pdf",
                ReceiptPdf.render(invoice, pricing.gstPercent()));
    }

    private Booking owned(Long driverId, Long bookingId) {
        return bookings.findByIdAndDriverId(bookingId, driverId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
    }

    private static Pageable paged(int page, int size, Sort sort) {
        return PageRequest.of(page, size, sort);
    }

    private static View parseView(String view) {
        if (view == null || view.isBlank()) {
            return View.UPCOMING;
        }
        try {
            return View.valueOf(view.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_VIEW", "view must be upcoming, past or all");
        }
    }
}
