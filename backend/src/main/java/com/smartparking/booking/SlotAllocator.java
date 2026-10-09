package com.smartparking.booking;

import com.smartparking.common.model.VehicleType;
import com.smartparking.common.util.SqlStates;
import com.smartparking.listing.ParkingListing;
import com.smartparking.pricing.Quote;
import com.smartparking.settings.PlatformSettings;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.user.User;
import com.smartparking.vehicle.Vehicle;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a slot for a booking under the database exclusion constraint. Each attempt runs in its own transaction, so a
 * conflict ({@code 23P01}) only discards that attempt and the next candidate slot can be tried cleanly.
 */
@Component
public class SlotAllocator {

    private static final Logger log = LoggerFactory.getLogger(SlotAllocator.class);

    static final int MAX_SLOT_ATTEMPTS = 5;
    static final int MAX_CODE_ATTEMPTS = 5;

    /** Everything needed to create a booking, as plain values (entities are re-referenced inside each attempt). */
    public record BookingDraft(Long driverId, Long listingId, Long vehicleId, VehicleType vehicleType,
                               String plateNumber, Instant start, Instant end, Quote quote) {
    }

    public record Allocation(Long bookingId, String bookingCode, Long slotId) {
    }

    private final BookingRepository bookings;
    private final BookingEvents events;
    private final EntityManager em;
    private final PlatformSettings settings;
    private final Clock clock;
    private final TransactionTemplate newTx;

    public SlotAllocator(BookingRepository bookings, BookingEvents events, EntityManager em,
                         PlatformSettings settings, Clock clock, PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.events = events;
        this.em = em;
        this.settings = settings;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Tries the candidate slots in order (at most {@value #MAX_SLOT_ATTEMPTS}) and holds the first one that is free.
     * Empty when every attempted slot was taken in the meantime.
     */
    public Optional<Allocation> allocate(List<Long> slotIds, BookingDraft draft) {
        for (Long slotId : slotIds.stream().limit(MAX_SLOT_ATTEMPTS).toList()) {
            Allocation allocation = tryAllocate(slotId, draft);
            if (allocation != null) {
                return Optional.of(allocation);
            }
        }
        return Optional.empty();
    }

    private Allocation tryAllocate(Long slotId, BookingDraft draft) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = BookingCodes.newCode();
            try {
                Long bookingId = newTx.execute(status -> insertHold(slotId, code, draft));
                return new Allocation(bookingId, code, slotId);
            } catch (DataIntegrityViolationException e) {
                String sqlState = SqlStates.of(e);
                if (SqlStates.EXCLUSION_VIOLATION.equals(sqlState)) {
                    log.info("Slot {} was taken before the hold could be placed", slotId);
                    return null;
                }
                if (SqlStates.UNIQUE_VIOLATION.equals(sqlState)) {
                    continue; // booking code collision: draw another code
                }
                throw e;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking code");
    }

    private Long insertHold(Long slotId, String code, BookingDraft draft) {
        Instant now = clock.instant();
        // Frees the slot from lapsed holds first. Clears the persistence context, so it must run before any entity
        // is loaded or created in this transaction.
        bookings.expireStaleHolds(List.of(slotId), now);

        Quote quote = draft.quote();
        Booking booking = new Booking();
        booking.setBookingCode(code);
        booking.setDriver(em.getReference(User.class, draft.driverId()));
        booking.setListing(em.getReference(ParkingListing.class, draft.listingId()));
        booking.setSlot(em.getReference(ParkingSlot.class, slotId));
        if (draft.vehicleId() != null) {
            booking.setVehicle(em.getReference(Vehicle.class, draft.vehicleId()));
        }
        booking.setVehicleType(draft.vehicleType());
        booking.setPlateNumber(draft.plateNumber());
        booking.setStartTime(draft.start());
        booking.setEndTime(draft.end());
        booking.setPricingMode(quote.pricingMode());
        booking.setPricingBreakdown(quote.breakdown());
        booking.setBaseAmount(quote.baseAmount());
        booking.setPlatformFee(quote.platformFee());
        booking.setGstAmount(quote.gstAmount());
        booking.setTotalAmount(quote.totalAmount());
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setHoldExpiresAt(now.plus(Duration.ofMinutes(settings.holdMinutes())));
        bookings.saveAndFlush(booking);

        events.record(booking, null, BookingStatus.PENDING_PAYMENT, BookingActor.DRIVER, null);
        return booking.getId();
    }

    /** Gives up an unpaid hold (its payment order could not be created) and frees the slot. */
    public void release(Long bookingId, String note) {
        newTx.executeWithoutResult(status -> {
            Booking booking = bookings.findById(bookingId).orElseThrow();
            if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
                return;
            }
            booking.setStatus(BookingStatus.EXPIRED);
            bookings.saveAndFlush(booking);
            events.record(booking, BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED, BookingActor.SYSTEM, note);
        });
    }
}
