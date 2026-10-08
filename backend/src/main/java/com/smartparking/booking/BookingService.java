package com.smartparking.booking;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.availability.AvailabilityEvaluator.ListingAvailabilityInput;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.booking.SlotAllocator.Allocation;
import com.smartparking.booking.SlotAllocator.BookingDraft;
import com.smartparking.booking.dto.CheckoutDto;
import com.smartparking.booking.dto.CreateBookingRequest;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentService;
import com.smartparking.payment.ProviderOrder;
import com.smartparking.pricing.PricingService;
import com.smartparking.pricing.Quote;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.vehicle.Vehicle;
import com.smartparking.vehicle.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserving a slot. Not itself transactional: slot allocation needs its own transactions (so a lost race can retry
 * on another slot) and creating the provider order is a network call, so the steps run as separate short stages.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final ParkingListingRepository listings;
    private final VehicleRepository vehicles;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final AvailabilityEvaluator evaluator;
    private final PricingService pricing;
    private final SlotAllocator allocator;
    private final PaymentService paymentService;
    private final BookingMapper mapper;
    private final BookingProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public BookingService(ParkingListingRepository listings, VehicleRepository vehicles, ParkingSlotRepository slots,
                          AvailabilityRuleRepository rules, AvailabilityBlockRepository blocks,
                          BookingRepository bookings, PaymentRepository payments, AvailabilityEvaluator evaluator,
                          PricingService pricing, SlotAllocator allocator, PaymentService paymentService,
                          BookingMapper mapper, BookingProperties properties, Clock clock,
                          PlatformTransactionManager txManager) {
        this.listings = listings;
        this.vehicles = vehicles;
        this.slots = slots;
        this.rules = rules;
        this.blocks = blocks;
        this.bookings = bookings;
        this.payments = payments;
        this.evaluator = evaluator;
        this.pricing = pricing;
        this.allocator = allocator;
        this.paymentService = paymentService;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    private record Prepared(BookingDraft draft, List<Long> freeSlotIds) {
    }

    /** Holds a slot for the driver for {@code holdMinutes} and opens a payment order for it. */
    public CheckoutDto reserve(Long driverId, CreateBookingRequest request) {
        TimeWindow window = TimeWindow.of(request.start(), request.end(), clock);
        Prepared prepared = readOnlyTx.execute(s -> prepare(driverId, request, window));

        Allocation allocation = allocator.allocate(prepared.freeSlotIds(), prepared.draft())
                .orElseThrow(() -> unavailable("FULLY_BOOKED"));

        ProviderOrder order;
        try {
            order = paymentService.createProviderOrder(allocation.bookingCode(),
                    prepared.draft().quote().totalAmount());
        } catch (RuntimeException e) {
            log.error("Could not create a payment order for booking {}", allocation.bookingCode(), e);
            allocator.release(allocation.bookingId(), "Payment order failed");
            throw e;
        }
        return tx.execute(s -> {
            Booking booking = bookings.findById(allocation.bookingId()).orElseThrow();
            Payment payment = paymentService.recordOrder(booking, order);
            return new CheckoutDto(mapper.toDetail(booking), paymentService.checkoutInfo(booking, payment));
        });
    }

    /** Re-reads the checkout of an unpaid booking whose hold is still running. */
    @Transactional(readOnly = true)
    public CheckoutDto checkout(Long driverId, Long bookingId) {
        Booking booking = bookings.findByIdAndDriverId(bookingId, driverId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
        Instant now = clock.instant();
        switch (booking.getStatus()) {
            case PENDING_PAYMENT -> {
                if (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(now)) {
                    throw holdExpired();
                }
            }
            case EXPIRED -> throw holdExpired();
            default -> throw ApiException.conflict("INVALID_STATUS",
                    "This booking can no longer be paid for (" + booking.getStatus() + ")");
        }
        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        return new CheckoutDto(mapper.toDetail(booking), paymentService.checkoutInfo(booking, payment));
    }

    private Prepared prepare(Long driverId, CreateBookingRequest request, TimeWindow window) {
        ParkingListing listing = listings.findById(request.listingId())
                .orElseThrow(() -> ApiException.notFound("Listing not found"));
        if (listing.getStatus() != ListingStatus.APPROVED) {
            throw ApiException.conflict("LISTING_UNAVAILABLE", "This listing is not available for booking");
        }
        Vehicle vehicle = vehicles.findByIdAndUserId(request.vehicleId(), driverId)
                .orElseThrow(() -> ApiException.notFound("Vehicle not found"));

        Instant now = clock.instant();
        long holds = bookings.countByDriverIdAndStatusAndHoldExpiresAtAfter(driverId, BookingStatus.PENDING_PAYMENT, now);
        if (holds >= properties.maxActiveHolds()) {
            throw ApiException.conflict("TOO_MANY_HOLDS",
                    "You already have " + holds + " bookings waiting for payment. Pay for or let them expire first.");
        }

        AvailabilityEvaluator.Result availability = evaluate(listing, window, vehicle.getType(), now);
        if (!availability.available()) {
            throw unavailable(availability.reason());
        }
        Quote quote = pricing.quote(listing, window.start(), window.end());
        BookingDraft draft = new BookingDraft(driverId, listing.getId(), vehicle.getId(), vehicle.getType(),
                vehicle.getPlateNumber(), window.start(), window.end(), quote);
        return new Prepared(draft, availability.freeSlotIds());
    }

    private AvailabilityEvaluator.Result evaluate(ParkingListing listing, TimeWindow window, VehicleType type,
                                                  Instant now) {
        Long id = listing.getId();
        List<AvailabilityRule> listingRules = listing.isOpen24x7() ? List.of()
                : rules.findByListingIdOrderByDayOfWeekAsc(id);
        List<AvailabilityBlock> overlapping = blocks.findOverlapping(List.of(id), window.start(), window.end());
        var booked = new HashSet<>(bookings.findLiveOverlappingSlotIds(List.of(id), window.start(), window.end(), now));
        List<ParkingSlot> listingSlots = slots.findByListingIdOrderByLabelAsc(id);
        return evaluator.evaluate(
                new ListingAvailabilityInput(listing.isOpen24x7(), listingRules, listingSlots, overlapping, booked),
                window, type);
    }

    private static ApiException unavailable(String reason) {
        return ApiException.conflict("SLOT_UNAVAILABLE", "No slot is available for the chosen time").with("reason", reason);
    }

    private static ApiException holdExpired() {
        return new ApiException(HttpStatus.GONE, "HOLD_EXPIRED", "Your slot hold expired. Please book again.");
    }
}
