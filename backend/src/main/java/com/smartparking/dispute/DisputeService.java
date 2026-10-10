package com.smartparking.dispute;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.SqlStates;
import com.smartparking.common.web.PageResponse;
import com.smartparking.dispute.DisputeMapper.Audience;
import com.smartparking.dispute.dto.CreateDisputeRequest;
import com.smartparking.dispute.dto.DisputeDto;
import com.smartparking.dispute.dto.DisputeSummaryDto;
import com.smartparking.email.EmailTemplates;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.user.User;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Disputes from the driver's and the owner's side: raising, reading and the owner's single response. */
@Service
@RequiredArgsConstructor
public class DisputeService {

    static final String OWNER_PATH = "/owner/disputes";
    static final int MIN_DESCRIPTION = 10;

    private final DisputeRepository disputes;
    private final BookingRepository bookings;
    private final DisputeMapper mapper;
    private final Notifier notifier;
    private final AppProperties app;
    private final EntityManager em;
    private final Clock clock;

    // ---- driver ---------------------------------------------------------------------------------------------

    @Transactional
    public DisputeDto raise(Long driverId, Long bookingId, CreateDisputeRequest request) {
        String description = request.description().trim();
        if (description.length() < MIN_DESCRIPTION) {
            throw ApiException.badRequest("VALIDATION_FAILED",
                    "Describe the problem in at least " + MIN_DESCRIPTION + " characters");
        }
        // Ownership by id only: the booking must not be loaded before its row lock, or the locking query would hand
        // back the stale instance that was read without it.
        if (!bookings.existsByIdAndDriverId(bookingId, driverId)) {
            throw ApiException.notFound("Booking not found");
        }
        // The booking row lock serialises concurrent reports of one booking (the unique index is the backstop).
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking not found"));
        String reason = DisputePolicy.notDisputableReason(booking, false, clock.instant());
        if (reason != null) {
            throw ApiException.conflict("DISPUTE_NOT_ALLOWED", reason);
        }
        if (disputes.existsByBookingIdAndStatusNot(bookingId, DisputeStatus.RESOLVED)) {
            throw alreadyOpen();
        }
        Dispute dispute = new Dispute();
        dispute.setBooking(booking);
        dispute.setRaisedBy(em.getReference(User.class, driverId));
        dispute.setCategory(request.category());
        dispute.setDescription(description);
        try {
            disputes.saveAndFlush(dispute);
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.UNIQUE_VIOLATION.equals(SqlStates.of(e))) { // uq_disputes_one_open_per_booking
                throw alreadyOpen();
            }
            throw e;
        }

        User owner = booking.getListing().getOwner();
        notifier.notify(owner, NotificationType.DISPUTE_OPENED, "A driver reported a problem",
                "Booking " + booking.getBookingCode() + " at " + booking.getListing().getTitle()
                        + ": " + label(request.category()) + ". You can respond once.", OWNER_PATH,
                EmailTemplates.disputeOpened(owner, booking, label(request.category()), app.frontendUrl() + OWNER_PATH));
        return mapper.toDto(dispute, Audience.DRIVER);
    }

    @Transactional(readOnly = true)
    public PageResponse<DisputeSummaryDto> listForDriver(Long driverId, int page, int size) {
        return PageResponse.from(disputes.findByRaisedById(driverId, paged(page, size)).map(DisputeSummaryDto::from));
    }

    @Transactional(readOnly = true)
    public DisputeDto getForDriver(Long driverId, Long id) {
        return mapper.toDto(disputes.findByIdAndRaisedById(id, driverId)
                .orElseThrow(() -> ApiException.notFound("Report not found")), Audience.DRIVER);
    }

    // ---- owner ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<DisputeSummaryDto> listForOwner(Long ownerId, DisputeStatus status, int page, int size) {
        Page<Dispute> result = disputes.findForOwner(ownerId, status, paged(page, size));
        return PageResponse.from(result.map(DisputeSummaryDto::from));
    }

    @Transactional(readOnly = true)
    public DisputeDto getForOwner(Long ownerId, Long id) {
        return mapper.toDto(ownedBy(ownerId, id), Audience.OWNER);
    }

    @Transactional
    public DisputeDto respond(Long ownerId, Long id, String rawResponse) {
        // Ownership by id only, then the lock: loading the dispute first would make the locking query return that
        // stale instance, and the update below would write its old status and response back over a concurrent change.
        if (!disputes.existsByIdAndBookingListingOwnerId(id, ownerId)) {
            throw ApiException.notFound("Report not found");
        }
        Dispute dispute = disputes.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        if (dispute.getStatus() == DisputeStatus.RESOLVED) {
            throw ApiException.conflict("DISPUTE_NOT_ALLOWED", "This report has already been resolved");
        }
        if (dispute.getOwnerResponse() != null) {
            throw ApiException.conflict("ALREADY_RESPONDED", "You have already responded to this report");
        }
        dispute.setOwnerResponse(rawResponse.trim());
        dispute.setOwnerRespondedAt(clock.instant());
        disputes.saveAndFlush(dispute);

        Booking booking = dispute.getBooking();
        String path = "/driver/bookings/" + booking.getId();
        notifier.notify(dispute.getRaisedBy(), NotificationType.DISPUTE_RESPONSE, "The owner responded to your report",
                "The owner of " + booking.getListing().getTitle() + " responded to your report for booking "
                        + booking.getBookingCode() + ".", path,
                EmailTemplates.disputeResponse(dispute.getRaisedBy(), booking, app.frontendUrl() + path));
        return mapper.toDto(dispute, Audience.OWNER);
    }

    private static ApiException alreadyOpen() {
        return ApiException.conflict("DISPUTE_ALREADY_OPEN", "There is already an open report for this booking");
    }

    private Dispute ownedBy(Long ownerId, Long id) {
        return disputes.findByIdAndBookingListingOwnerId(id, ownerId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
    }

    static String label(DisputeCategory category) {
        return category.name().toLowerCase().replace('_', ' ');
    }

    private static PageRequest paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
