package com.smartparking.booking;

import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.BookingSummaryDto;
import com.smartparking.dispute.Dispute;
import com.smartparking.dispute.DisputePolicy;
import com.smartparking.dispute.DisputeRepository;
import com.smartparking.dispute.DisputeStatus;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.invoice.Invoice;
import com.smartparking.invoice.InvoiceRepository;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.ListingPhoto;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ParkingListing;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.review.Review;
import com.smartparking.review.ReviewMapper;
import com.smartparking.review.ReviewPolicy;
import com.smartparking.review.ReviewRepository;
import com.smartparking.review.dto.ReviewDto;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps bookings to DTOs. Call within a transaction (lazy listing, city, slot and owner). */
@Component
@RequiredArgsConstructor
public class BookingMapper {

    private final ListingPhotoRepository photos;
    private final ListingMapper listingMapper;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final BookingEventRepository events;
    private final ReviewRepository reviews;
    private final ReviewMapper reviewMapper;
    private final DisputeRepository disputes;
    private final Clock clock;

    public BookingSummaryDto toSummary(Booking b) {
        return summary(b, coverUrls(List.of(b.getListing().getId())));
    }

    /** Summaries for many bookings with one photo query. */
    public List<BookingSummaryDto> toSummaries(List<Booking> bookings) {
        Map<Long, String> covers = coverUrls(bookings.stream().map(b -> b.getListing().getId()).distinct().toList());
        return bookings.stream().map(b -> summary(b, covers)).toList();
    }

    public BookingDetailDto toDetail(Booking b) {
        ParkingListing l = b.getListing();
        Payment payment = payments.findByBookingId(b.getId()).orElse(null);
        String invoiceNumber = invoices.findByBookingId(b.getId()).map(Invoice::getInvoiceNumber).orElse(null);
        List<BookingDetailDto.Event> history = events.findByBookingIdOrderByCreatedAtAscIdAsc(b.getId()).stream()
                .map(e -> new BookingDetailDto.Event(e.getFromStatus(), e.getToStatus(), e.getActor(), e.getNote(),
                        e.getCreatedAt()))
                .toList();
        Review posted = reviews.findByBookingId(b.getId()).orElse(null);
        boolean reviewable = posted == null && ReviewPolicy.notReviewableReason(b, clock.instant()) == null;
        ReviewDto review = posted == null ? null : reviewMapper.toDto(posted);
        List<Dispute> raised = disputes.findByBookingIdOrderByCreatedAtDescIdDesc(b.getId());
        boolean unresolved = raised.stream().anyMatch(d -> d.getStatus() != DisputeStatus.RESOLVED);
        boolean disputable = DisputePolicy.notDisputableReason(b, unresolved, clock.instant()) == null;
        return new BookingDetailDto(
                b.getId(), b.getBookingCode(), b.getStatus(), l.getId(), l.getTitle(), l.getCity().getName(),
                coverUrls(List.of(l.getId())).get(l.getId()), b.getStartTime(), b.getEndTime(), b.getVehicleType(),
                b.getPlateNumber(), b.getTotalAmount(), b.getCreatedAt(),
                l.getAddress(), l.getLat(), l.getLng(), b.getSlot().getLabel(), b.getPricingMode(),
                b.getPricingBreakdown(), b.getBaseAmount(), b.getPlatformFee(), b.getGstAmount(), b.getRefundAmount(),
                b.getHoldExpiresAt(), b.getApprovalDeadline(), b.getConfirmedAt(), b.getCancelReason(),
                b.getCancelledBy(), payment == null ? null : payment.getStatus(), invoiceNumber, l.isAutoApprove(),
                firstName(l.getOwner().getName()), history, reviewable, review,
                raised.stream().map(DisputeSummaryDto::from).toList(), disputable);
    }

    private BookingSummaryDto summary(Booking b, Map<Long, String> covers) {
        ParkingListing l = b.getListing();
        return new BookingSummaryDto(b.getId(), b.getBookingCode(), b.getStatus(), l.getId(), l.getTitle(),
                l.getCity().getName(), covers.get(l.getId()), b.getStartTime(), b.getEndTime(), b.getVehicleType(),
                b.getPlateNumber(), b.getTotalAmount(), b.getCreatedAt());
    }

    /** Cover photo (first by sort order) per listing id; listings without photos are absent. */
    private Map<Long, String> coverUrls(Collection<Long> listingIds) {
        Map<Long, String> covers = new HashMap<>();
        for (ListingPhoto p : photos.findByListingIdInOrderByListingIdAscSortOrderAsc(listingIds)) {
            covers.putIfAbsent(p.getListing().getId(), listingMapper.photoUrl(p));
        }
        return covers;
    }

    static String firstName(String name) {
        String trimmed = name == null ? "" : name.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
