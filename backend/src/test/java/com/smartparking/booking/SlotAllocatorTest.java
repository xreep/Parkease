package com.smartparking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.booking.SlotAllocator.Allocation;
import com.smartparking.booking.SlotAllocator.BookingDraft;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.pricing.PricingMode;
import com.smartparking.pricing.Quote;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@CommittedIntegrationTest
class SlotAllocatorTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired UserRepository users;
    @Autowired BookingRepository bookings;
    @Autowired SlotAllocator allocator;

    private User driver;
    private ParkingListing listing;
    private ParkingSlot slot1;
    private ParkingSlot slot2;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "sa-owner@example.com", "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Allocator Spot", 18.5204, 73.8567, 30);
        listing = listings.findById(listingId).orElseThrow();
        slot1 = slots.findByListingIdOrderByLabelAsc(listingId).get(0);
        ParkingSlot second = new ParkingSlot();
        second.setListing(listing);
        second.setLabel("A-02");
        second.setVehicleType(VehicleType.FOUR_WHEELER);
        second.setSize(SlotSize.MEDIUM);
        slot2 = slots.saveAndFlush(second);
        driver = users.save(TestUsers.newUser("sa-driver@example.com", Role.DRIVER));
        start = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(34, ChronoUnit.HOURS);
        end = start.plus(2, ChronoUnit.HOURS);
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private BookingDraft draft() {
        Quote quote = new Quote(PricingMode.HOURLY, 120, new BigDecimal("60.00"), new BigDecimal("6.00"),
                new BigDecimal("1.08"), new BigDecimal("67.08"), "2 hours");
        return new BookingDraft(driver.getId(), listing.getId(), null, VehicleType.FOUR_WHEELER, "MH12AB1234",
                start, end, quote);
    }

    private Booking existing(ParkingSlot slot, BookingStatus status) {
        Booking b = BookingTestSupport.booking(driver, listing, slot, status, start, end);
        if (status == BookingStatus.PENDING_PAYMENT) {
            b.setHoldExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
        }
        return bookings.saveAndFlush(b);
    }

    @Test
    void landsOnTheSecondSlotWhenTheFirstWasJustTaken() {
        existing(slot1, BookingStatus.CONFIRMED);

        Optional<Allocation> result = allocator.allocate(List.of(slot1.getId(), slot2.getId()), draft());

        assertThat(result).isPresent();
        assertThat(result.get().slotId()).isEqualTo(slot2.getId());
        assertThat(result.get().bookingCode()).matches("PK-[A-HJKMNP-Z2-9]{6}");
        Booking saved = bookings.findById(result.get().bookingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("67.08");
        assertThat(saved.getPlateNumber()).isEqualTo("MH12AB1234");
        assertThat(Duration.between(Instant.now().plus(10, ChronoUnit.MINUTES), saved.getHoldExpiresAt()).abs())
                .isLessThan(Duration.ofSeconds(30));
        assertThat(jdbc.queryForObject(
                "select count(*) from booking_events where booking_id = ? and from_status is null "
                        + "and to_status = 'PENDING_PAYMENT' and actor = 'DRIVER'",
                Integer.class, saved.getId())).isEqualTo(1);
    }

    @Test
    void returnsEmptyWhenEverySlotIsTaken() {
        existing(slot1, BookingStatus.CONFIRMED);
        existing(slot2, BookingStatus.AWAITING_APPROVAL);

        assertThat(allocator.allocate(List.of(slot1.getId(), slot2.getId()), draft())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from bookings", Integer.class)).isEqualTo(2);
    }

    @Test
    void anExpiredHoldIsSweptSoItsSlotCanBeTakenAgain() {
        Booking stale = existing(slot1, BookingStatus.PENDING_PAYMENT);
        stale.setHoldExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        bookings.saveAndFlush(stale);

        Optional<Allocation> result = allocator.allocate(List.of(slot1.getId(), slot2.getId()), draft());

        assertThat(result).isPresent();
        assertThat(result.get().slotId()).isEqualTo(slot1.getId());
        assertThat(bookings.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void aLiveHoldIsNotSwept() {
        Booking live = existing(slot1, BookingStatus.PENDING_PAYMENT);

        Optional<Allocation> result = allocator.allocate(List.of(slot1.getId(), slot2.getId()), draft());

        assertThat(result.orElseThrow().slotId()).isEqualTo(slot2.getId());
        assertThat(bookings.findById(live.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void triesAtMostFiveSlots() {
        // Slots beyond the fifth candidate are never attempted.
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(slot1.getId());
        }
        existing(slot1, BookingStatus.CONFIRMED);
        ids.add(slot2.getId());

        assertThat(allocator.allocate(ids, draft())).isEmpty();
    }

    @Test
    void reviveBringsAnExpiredBookingBackWhenTheSlotIsFree() {
        Booking expired = existing(slot1, BookingStatus.EXPIRED);
        Instant now = Instant.now();

        boolean revived = allocator.revive(expired.getId(), true, now, Duration.ofHours(2));

        assertThat(revived).isTrue();
        Booking b = bookings.findById(expired.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getConfirmedAt()).isNotNull();
    }

    @Test
    void reviveFailsWhenAnotherBookingHoldsTheSlot() {
        Booking expired = existing(slot1, BookingStatus.EXPIRED);
        existing(slot1, BookingStatus.CONFIRMED);

        assertThat(allocator.revive(expired.getId(), false, Instant.now(), Duration.ofHours(2))).isFalse();
        assertThat(bookings.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }
}
