package com.smartparking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.model.VehicleType;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.invoice.Invoice;
import com.smartparking.invoice.InvoiceRepository;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.payment.Refund;
import com.smartparking.payment.RefundRepository;
import com.smartparking.payment.RefundStatus;
import com.smartparking.payment.WebhookEvent;
import com.smartparking.payment.WebhookEventRepository;
import com.smartparking.pricing.PricingMode;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import com.smartparking.vehicle.Vehicle;
import com.smartparking.vehicle.VehicleRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class BookingSchemaTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired UserRepository users;
    @Autowired VehicleRepository vehicles;
    @Autowired BookingRepository bookings;
    @Autowired BookingEventRepository events;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired OwnerEarningRepository earnings;
    @Autowired InvoiceRepository invoices;
    @Autowired WebhookEventRepository webhooks;
    @Autowired EntityManager em;

    private User driver;
    private User owner;
    private ParkingListing listing;
    private ParkingSlot slot;
    private Instant tomorrow10;
    private int codeSeq;

    @BeforeEach
    void setUp() throws Exception {
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "bk-owner@example.com", "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Booking Spot", 18.5204, 73.8567, 30);
        listing = listings.findById(listingId).orElseThrow();
        owner = listing.getOwner();
        slot = slots.findByListingIdOrderByLabelAsc(listingId).get(0);
        driver = users.save(TestUsers.newUser("bk-driver@example.com", Role.DRIVER));
        tomorrow10 = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(34, ChronoUnit.HOURS);
    }

    private Booking booking(BookingStatus status, int startHour, int endHour) {
        Booking b = new Booking();
        b.setBookingCode("PE" + String.format("%08d", ++codeSeq));
        b.setDriver(driver);
        b.setListing(listing);
        b.setSlot(slot);
        b.setVehicleType(VehicleType.FOUR_WHEELER);
        b.setPlateNumber("MH12AB1234");
        b.setStartTime(tomorrow10.plus(startHour - 10, ChronoUnit.HOURS));
        b.setEndTime(tomorrow10.plus(endHour - 10, ChronoUnit.HOURS));
        b.setPricingMode(PricingMode.HOURLY);
        b.setPricingBreakdown("2 h x 30.00");
        b.setBaseAmount(new BigDecimal("60.00"));
        b.setPlatformFee(new BigDecimal("6.00"));
        b.setGstAmount(new BigDecimal("1.08"));
        b.setGstPercent(new BigDecimal("18.00"));
        b.setTotalAmount(new BigDecimal("67.08"));
        b.setStatus(status);
        return b;
    }

    @Test
    void overlappingLiveBookingOnSameSlotIsRejected() {
        bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 10, 12));

        assertThatThrownBy(() -> bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 11, 13)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void touchingBookingsAreAllowed() {
        bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 10, 12));

        Booking next = bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 12, 14));

        assertThat(next.getId()).isNotNull();
    }

    @Test
    void expiredOrCancelledBookingsDoNotBlockTheSlot() {
        bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 10, 12));

        assertThat(bookings.saveAndFlush(booking(BookingStatus.EXPIRED, 11, 13)).getId()).isNotNull();
        assertThat(bookings.saveAndFlush(booking(BookingStatus.CANCELLED, 11, 13)).getId()).isNotNull();
    }

    @Test
    void liveOverlappingSlotIdsAndStaleHoldExpiry() {
        Instant now = Instant.now();
        Booking hold = booking(BookingStatus.PENDING_PAYMENT, 10, 12);
        hold.setHoldExpiresAt(now.minus(1, ChronoUnit.MINUTES));
        bookings.saveAndFlush(hold);

        // An expired hold does not count as live...
        assertThat(bookings.findLiveOverlappingSlotIds(List.of(listing.getId()),
                tomorrow10, tomorrow10.plus(2, ChronoUnit.HOURS), now)).isEmpty();
        assertThat(hold.isLiveAt(now)).isFalse();

        // ...and the stale hold can be expired in bulk to free the slot.
        assertThat(bookings.expireStaleHolds(List.of(slot.getId()), now)).isEqualTo(1);
        em.clear();
        assertThat(bookings.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);

        Booking live = booking(BookingStatus.PENDING_PAYMENT, 10, 12);
        live.setHoldExpiresAt(now.plus(10, ChronoUnit.MINUTES));
        bookings.saveAndFlush(live);
        assertThat(live.isLiveAt(now)).isTrue();
        assertThat(bookings.findLiveOverlappingSlotIds(List.of(listing.getId()),
                tomorrow10, tomorrow10.plus(2, ChronoUnit.HOURS), now)).containsExactly(slot.getId());
        assertThat(bookings.countByDriverIdAndStatusAndHoldExpiresAtAfter(driver.getId(),
                BookingStatus.PENDING_PAYMENT, now)).isEqualTo(1);
    }

    @Test
    void liveOverlappingSlotIdsAreDistinct() {
        Instant now = Instant.now();
        bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 10, 12));
        bookings.saveAndFlush(booking(BookingStatus.CONFIRMED, 12, 14));

        assertThat(bookings.findLiveOverlappingSlotIds(List.of(listing.getId()),
                tomorrow10, tomorrow10.plus(4, ChronoUnit.HOURS), now)).containsExactly(slot.getId());
    }

    @Test
    void expiringStaleHoldsBumpsUpdatedAt() {
        Instant now = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Booking hold = booking(BookingStatus.PENDING_PAYMENT, 10, 12);
        hold.setHoldExpiresAt(now.minus(1, ChronoUnit.MINUTES));
        bookings.saveAndFlush(hold);

        assertThat(bookings.expireStaleHolds(List.of(slot.getId()), now)).isEqualTo(1);

        assertThat(bookings.findById(hold.getId()).orElseThrow().getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void relatedRowsPersistAndReload() {
        Vehicle vehicle = new Vehicle();
        vehicle.setUser(driver);
        vehicle.setType(VehicleType.FOUR_WHEELER);
        vehicle.setPlateNumber("MH12AB1234");
        vehicle.setMakeModel("Honda City");
        vehicle.setDefault(true);
        vehicles.save(vehicle);

        Booking booking = booking(BookingStatus.CONFIRMED, 10, 12);
        booking.setVehicle(vehicle);
        booking.setConfirmedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        bookings.save(booking);

        BookingEvent event = new BookingEvent();
        event.setBooking(booking);
        event.setToStatus(BookingStatus.CONFIRMED);
        event.setActor(BookingActor.SYSTEM);
        event.setNote("auto");
        events.save(event);

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProviderType.MOCK);
        payment.setOrderId("order_mock_1");
        payment.setAmount(new BigDecimal("67.08"));
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedAt(Instant.now());
        payments.save(payment);

        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setProviderRefundId("rfnd_1");
        refund.setAmount(new BigDecimal("30.00"));
        refund.setStatus(RefundStatus.PROCESSED);
        refunds.save(refund);

        OwnerEarning earning = new OwnerEarning();
        earning.setBooking(booking);
        earning.setOwner(owner);
        earning.setGross(new BigDecimal("60.00"));
        earning.setCommission(new BigDecimal("6.00"));
        earning.setNet(new BigDecimal("54.00"));
        earning.setStatus(EarningStatus.HELD);
        earnings.save(earning);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("PE-INV-" + invoices.nextNumber());
        invoice.setBooking(booking);
        invoice.setPayment(payment);
        invoice.setIssuedAt(Instant.now());
        invoices.save(invoice);

        WebhookEvent webhook = new WebhookEvent();
        webhook.setProviderEventId("evt_1");
        webhook.setEventType("payment.captured");
        webhook.setPayload("{\"event\":\"payment.captured\"}");
        webhooks.save(webhook);

        em.flush();
        em.clear();

        Booking reloaded = bookings.findByBookingCode(booking.getBookingCode()).orElseThrow();
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("67.08");
        assertThat(reloaded.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(reloaded.getVehicle().getPlateNumber()).isEqualTo("MH12AB1234");
        assertThat(bookings.findByIdAndDriverId(reloaded.getId(), driver.getId())).isPresent();
        assertThat(events.findByBookingIdOrderByCreatedAtAscIdAsc(reloaded.getId())).hasSize(1);
        assertThat(payments.findByBookingId(reloaded.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payments.findByOrderId("order_mock_1")).isPresent();
        assertThat(refunds.findByProviderRefundId("rfnd_1")).isPresent();
        assertThat(earnings.findByBookingId(reloaded.getId()).orElseThrow().getNet()).isEqualByComparingTo("54.00");
        assertThat(invoices.findByBookingId(reloaded.getId())).isPresent();
        assertThat(webhooks.existsByProviderEventId("evt_1")).isTrue();
        assertThat(webhooks.existsByProviderEventId("evt_2")).isFalse();
        assertThat(bookings.findByListingOwnerId(owner.getId(), org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(1);
    }

    @Test
    void invoiceNumbersIncrease() {
        long first = invoices.nextNumber();
        long second = invoices.nextNumber();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void platesAreUniquePerUser() {
        Vehicle a = new Vehicle();
        a.setUser(driver);
        a.setType(VehicleType.TWO_WHEELER);
        a.setPlateNumber("MH12AB1234");
        vehicles.saveAndFlush(a);

        Vehicle dup = new Vehicle();
        dup.setUser(driver);
        dup.setType(VehicleType.TWO_WHEELER);
        dup.setPlateNumber("MH12AB1234");

        assertThatThrownBy(() -> vehicles.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingVehicleKeepsBookingWithPlateSnapshot() {
        Vehicle vehicle = new Vehicle();
        vehicle.setUser(driver);
        vehicle.setType(VehicleType.FOUR_WHEELER);
        vehicle.setPlateNumber("MH12AB1234");
        vehicles.save(vehicle);
        Booking booking = booking(BookingStatus.CONFIRMED, 10, 12);
        booking.setVehicle(vehicle);
        bookings.saveAndFlush(booking);

        em.clear();
        vehicles.delete(vehicles.findById(vehicle.getId()).orElseThrow());
        em.flush();
        em.clear();

        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getVehicle()).isNull();
        assertThat(reloaded.getPlateNumber()).isEqualTo("MH12AB1234");
    }
}
