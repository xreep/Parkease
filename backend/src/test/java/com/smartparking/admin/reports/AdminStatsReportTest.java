package com.smartparking.admin.reports;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentProviderType;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.OwnerTestSupport;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import com.smartparking.user.UserStatus;
import com.smartparking.common.model.VehicleType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admin KPI stats and the usage/revenue reports against one hand-computed fixture (two cities in two states plus an
 * approved listing nobody booked). All bookings are attributed by their creation day (IST); range = d0..d0+4.
 */
@CommittedIntegrationTest
class AdminStatsReportTest {

    private static final ZoneId IST = AvailabilityEvaluator.ZONE;
    private static final String BOM = "﻿";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired PasswordEncoder encoder;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired OwnerEarningRepository earnings;
    @Autowired ReportService reportService;

    String admin;
    String ownerAuth;
    String driverAuth;
    User ownerA;
    User ownerB;
    User driverA;
    ParkingListing pune;
    ParkingListing bengaluru;
    ParkingListing hyderabad;
    ParkingSlot puneSlot;
    ParkingSlot bengaluruSlot;
    Long puneId;
    Long bengaluruId;
    Long hyderabadId;
    Long mhId;
    Long kaId;
    LocalDate d0;
    LocalDate to;
    String originalHyderabadName;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        admin = adminAuth(mvc, users, encoder, "stats-admin@example.com");
        ownerAuth = OwnerTestSupport.verifiedOwner(mvc, users, profiles, "stats-owner-a@example.com");
        AuthTestSupport.register(mvc, "stats-owner-b@example.com", "OWNER");
        driverAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "stats-driver-a@example.com", "DRIVER")));
        ownerA = users.findByEmail("stats-owner-a@example.com").orElseThrow();
        ownerB = users.findByEmail("stats-owner-b@example.com").orElseThrow();
        driverA = users.findByEmail("stats-driver-a@example.com").orElseThrow();
        puneId = city("Pune");
        bengaluruId = city("Bengaluru");
        hyderabadId = city("Hyderabad");
        originalHyderabadName = cities.findById(hyderabadId).orElseThrow().getName();
        mhId = jdbc.queryForObject("select state_id from cities where id = ?", Long.class, puneId);
        kaId = jdbc.queryForObject("select state_id from cities where id = ?", Long.class, bengaluruId);
        d0 = LocalDate.now(IST).minusDays(20);
        to = d0.plusDays(4);

        pune = listing(ownerAuth, puneId, "Pune Spot");
        bengaluru = listing(ownerAuth, bengaluruId, "Bengaluru Spot");
        hyderabad = listing(ownerAuth, hyderabadId, "Hyderabad Spot");
        puneSlot = slots.findByListingIdOrderByLabelAsc(pune.getId()).get(0);
        bengaluruSlot = slots.findByListingIdOrderByLabelAsc(bengaluru.getId()).get(0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("update cities set name = ? where id = ?", originalHyderabadName, hyderabadId);
        DatabaseCleaner.clean(jdbc);
    }

    // ---- fixture --------------------------------------------------------------------------------------------

    private Long city(String name) {
        return cities.searchByPrefix(name, org.springframework.data.domain.Limit.of(5)).stream()
                .filter(c -> c.getName().equals(name)).map(City::getId).findFirst().orElseThrow();
    }

    private ParkingListing listing(String auth, Long cityId, String title) throws Exception {
        // The API only accepts a city around the (Pune) coordinates, so move the listing afterwards.
        Long id = approvedListingAt(mvc, auth, listings, puneId, title, 18.5204, 73.8567, 30);
        ParkingListing l = listings.findById(id).orElseThrow();
        l.setCity(cities.findById(cityId).orElseThrow());
        return listings.saveAndFlush(l);
    }

    private static Instant at(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), IST).toInstant();
    }

    private Booking booking(ParkingListing l, ParkingSlot s, BookingStatus st, LocalDate createdDay,
                            String createdTime, LocalDate startDay, String from, String until) {
        Booking b = BookingTestSupport.booking(driverA, l, s, st, at(startDay, from), at(startDay, until));
        if (st == BookingStatus.PENDING_PAYMENT) {
            b.setHoldExpiresAt(Instant.now().plusSeconds(3600));
        }
        b = bookings.saveAndFlush(b);
        jdbc.update("update bookings set created_at = ? where id = ?", java.sql.Timestamp.from(at(createdDay, createdTime)),
                b.getId());
        return b;
    }

    private Booking pay(Booking b, PaymentStatus status) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setProvider(PaymentProviderType.MOCK);
        p.setOrderId("order_stats_" + b.getId());
        p.setAmount(b.getTotalAmount());
        p.setStatus(status);
        payments.saveAndFlush(p);
        return b;
    }

    private Booking refunded(Booking b, String amount) {
        b.setRefundAmount(new BigDecimal(amount));
        return bookings.saveAndFlush(b);
    }

    private Booking confirmed(Booking b) {
        b.setConfirmedAt(Instant.now());
        return bookings.saveAndFlush(b);
    }

    private void earning(Booking b, User owner, String net, EarningStatus st) {
        OwnerEarning e = new OwnerEarning();
        e.setBooking(b);
        e.setOwner(owner);
        e.setGross(new BigDecimal("60.00"));
        e.setCommission(new BigDecimal("6.00"));
        e.setNet(new BigDecimal(net));
        e.setStatus(st);
        earnings.saveAndFlush(e);
    }

    private void setCreated(Long userId, LocalDate day) {
        jdbc.update("update users set created_at = ? where id = ?", java.sql.Timestamp.from(at(day, "12:00")), userId);
    }

    private User driver(String email, UserStatus status, LocalDate createdDay) {
        User u = TestUsers.newUser(email, Role.DRIVER);
        u.setStatus(status);
        u = users.saveAndFlush(u);
        setCreated(u.getId(), createdDay);
        return u;
    }

    /**
     * Money (default booking: base 60.00, fee 6.00, GST 1.08, total 67.08). Pune (Maharashtra) bookings:
     * P1 completed, paid; P2 cancelled after confirmation, fully refunded; P3 confirmed, 30.00 refunded; P4 unpaid
     * hold; P5 expired hold; P6 rejected request, fully refunded; P8 confirmed but starting after the range; P9
     * cancelled unpaid hold; P7 created before the range. Bengaluru (Karnataka): B1 completed (total 118.00, fee
     * 10.00, GST 18.00), B2 active, created 00:30 IST (the previous day in UTC).
     */
    private void bookingsFixture() {
        LocalDate d1 = d0.plusDays(1);
        LocalDate d2 = d0.plusDays(2);
        LocalDate d3 = d0.plusDays(3);
        Booking p1 = confirmed(pay(booking(pune, puneSlot, BookingStatus.COMPLETED, d0, "10:00", d1, "10:00", "12:00"),
                PaymentStatus.CAPTURED));
        earning(p1, ownerA, "54.00", EarningStatus.PAID);
        Booking p2 = confirmed(refunded(pay(booking(pune, puneSlot, BookingStatus.CANCELLED, d0, "11:00", d2, "10:00",
                "11:00"), PaymentStatus.REFUNDED), "67.08"));
        earning(p2, ownerA, "0.00", EarningStatus.REVERSED);
        Booking p3 = confirmed(refunded(pay(booking(pune, puneSlot, BookingStatus.CONFIRMED, d1, "09:00", d2, "14:00",
                "18:00"), PaymentStatus.PARTIALLY_REFUNDED), "30.00"));
        earning(p3, ownerA, "40.00", EarningStatus.HELD);
        booking(pune, puneSlot, BookingStatus.PENDING_PAYMENT, d1, "10:00", d3, "10:00", "11:00");
        booking(pune, puneSlot, BookingStatus.EXPIRED, d1, "23:30", d3, "12:00", "13:00");
        refunded(pay(booking(pune, puneSlot, BookingStatus.REJECTED, d2, "09:00", d3, "14:00", "15:00"),
                PaymentStatus.REFUNDED), "67.08");
        Booking p8 = confirmed(pay(booking(pune, puneSlot, BookingStatus.CONFIRMED, to, "12:00", d0.plusDays(10),
                "10:00", "12:00"), PaymentStatus.CAPTURED));
        earning(p8, ownerA, "54.00", EarningStatus.HELD);
        pay(booking(pune, puneSlot, BookingStatus.CANCELLED, d1, "12:00", d3, "16:00", "17:00"), PaymentStatus.FAILED);
        // created the day before the range: nothing counts, although it starts inside it
        confirmed(pay(booking(pune, puneSlot, BookingStatus.COMPLETED, d0.minusDays(1), "12:00", d1, "20:00", "21:00"),
                PaymentStatus.CAPTURED));

        Booking b1 = booking(bengaluru, bengaluruSlot, BookingStatus.COMPLETED, d2, "12:00", d2, "08:00", "12:00");
        b1.setBaseAmount(new BigDecimal("100.00"));
        b1.setPlatformFee(new BigDecimal("10.00"));
        b1.setGstAmount(new BigDecimal("18.00"));
        b1.setTotalAmount(new BigDecimal("118.00"));
        b1 = confirmed(pay(bookings.saveAndFlush(b1), PaymentStatus.CAPTURED));
        earning(b1, ownerB, "90.00", EarningStatus.PENDING_PAYOUT);
        Booking b2 = confirmed(pay(booking(bengaluru, bengaluruSlot, BookingStatus.ACTIVE, d2, "00:30", d3, "09:00",
                "10:00"), PaymentStatus.CAPTURED));
        earning(b2, ownerB, "90.00", EarningStatus.HELD);
    }

    /** Users and listing statuses of the fixture. */
    private void accountsFixture() throws Exception {
        LocalDate d1 = d0.plusDays(1);
        setCreated(driverA.getId(), d1);
        driver("stats-driver-b@example.com", UserStatus.ACTIVE, d0);
        driver("stats-driver-c@example.com", UserStatus.ACTIVE, d0.minusDays(30));
        driver("stats-driver-d@example.com", UserStatus.SUSPENDED, d0.minusDays(30));
        setCreated(ownerA.getId(), d1);
        setCreated(ownerB.getId(), d0.minusDays(30));
        setStatus(listing(ownerAuth, puneId, "Review Spot"), ListingStatus.PENDING_REVIEW);
        setStatus(listing(ownerAuth, puneId, "Paused Spot"), ListingStatus.PAUSED);
        setStatus(listing(ownerAuth, bengaluruId, "Suspended Spot"), ListingStatus.SUSPENDED);
    }

    private void setStatus(ParkingListing l, ListingStatus status) {
        l.setStatus(status);
        listings.saveAndFlush(l);
    }

    private ResultActions getWith(String auth, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions stats(LocalDate from, LocalDate until) throws Exception {
        return getWith(admin, "/api/v1/admin/stats?from=" + from + "&to=" + until);
    }

    private ResultActions report(String kind, String query) throws Exception {
        return getWith(admin, "/api/v1/admin/reports/" + kind + "?from=" + d0 + "&to=" + to + query);
    }

    // ---- stats ----------------------------------------------------------------------------------------------

    @Test
    void statsFollowTheKpiDefinitionsOverTheFixture() throws Exception {
        accountsFixture();
        bookingsFixture();

        stats(d0, to)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d0.toString()))
                .andExpect(jsonPath("$.to").value(to.toString()))
                .andExpect(jsonPath("$.users.drivers").value(4))
                .andExpect(jsonPath("$.users.owners").value(2))
                .andExpect(jsonPath("$.users.newDrivers").value(2))
                .andExpect(jsonPath("$.users.newOwners").value(1))
                .andExpect(jsonPath("$.users.suspended").value(1))
                .andExpect(jsonPath("$.listings.approved").value(3))
                .andExpect(jsonPath("$.listings.pendingReview").value(1))
                .andExpect(jsonPath("$.listings.suspended").value(1))
                .andExpect(jsonPath("$.listings.paused").value(1))
                .andExpect(jsonPath("$.bookings.created").value(10))
                .andExpect(jsonPath("$.bookings.confirmed").value(6))
                .andExpect(jsonPath("$.bookings.conversionPercent").value(60.0))
                .andExpect(jsonPath("$.bookings.cancelled").value(2))
                // 11 booked slot-hours of 3 approved 24x7 slots x 5 days = 360 open hours
                .andExpect(jsonPath("$.bookings.utilizationPercent").value(3.1))
                .andExpect(jsonPath("$.money.gmv").value(356.32))
                .andExpect(jsonPath("$.money.platformRevenue").value(34.0))
                .andExpect(jsonPath("$.money.refunds").value(164.16))
                .andExpect(jsonPath("$.money.ownerEarnings").value(328.0));
    }

    @Test
    void topListsAreOrderedByGmvAndSeriesIsPerIstDayWithZeroDays() throws Exception {
        bookingsFixture();

        stats(d0, to)
                .andExpect(jsonPath("$.topStates", hasSize(2)))
                .andExpect(jsonPath("$.topStates[0].stateId").value(kaId))
                .andExpect(jsonPath("$.topStates[0].name").value("Karnataka"))
                .andExpect(jsonPath("$.topStates[0].bookings").value(2))
                .andExpect(jsonPath("$.topStates[0].gmv").value(185.08))
                .andExpect(jsonPath("$.topStates[1].stateId").value(mhId))
                .andExpect(jsonPath("$.topStates[1].name").value("Maharashtra"))
                .andExpect(jsonPath("$.topStates[1].bookings").value(8))
                .andExpect(jsonPath("$.topStates[1].gmv").value(171.24))
                .andExpect(jsonPath("$.topCities", hasSize(2)))
                .andExpect(jsonPath("$.topCities[0].cityId").value(bengaluruId))
                .andExpect(jsonPath("$.topCities[0].name").value("Bengaluru"))
                .andExpect(jsonPath("$.topCities[0].stateName").value("Karnataka"))
                .andExpect(jsonPath("$.topCities[0].gmv").value(185.08))
                .andExpect(jsonPath("$.topCities[1].cityId").value(puneId))
                .andExpect(jsonPath("$.topCities[1].bookings").value(8))
                .andExpect(jsonPath("$.series", hasSize(5)))
                .andExpect(jsonPath("$.series[0].date").value(d0.toString()))
                .andExpect(jsonPath("$.series[0].bookings").value(2))
                .andExpect(jsonPath("$.series[0].gmv").value(67.08))
                .andExpect(jsonPath("$.series[0].revenue").value(6.0))
                .andExpect(jsonPath("$.series[1].bookings").value(4))
                .andExpect(jsonPath("$.series[1].gmv").value(37.08))
                .andExpect(jsonPath("$.series[1].revenue").value(6.0))
                // B2 was created 00:30 IST on d2, which is still d1 in UTC
                .andExpect(jsonPath("$.series[2].bookings").value(3))
                .andExpect(jsonPath("$.series[2].gmv").value(185.08))
                .andExpect(jsonPath("$.series[2].revenue").value(16.0))
                .andExpect(jsonPath("$.series[3].date").value(d0.plusDays(3).toString()))
                .andExpect(jsonPath("$.series[3].bookings").value(0))
                .andExpect(jsonPath("$.series[3].gmv").value(0.0))
                .andExpect(jsonPath("$.series[3].revenue").value(0.0))
                .andExpect(jsonPath("$.series[4].bookings").value(1))
                .andExpect(jsonPath("$.series[4].gmv").value(67.08));
    }

    @Test
    void topListsAreCappedAtFive() throws Exception {
        List<Long> others = jdbc.queryForList("""
                select min(c.id) from cities c where c.state_id not in (?, ?) group by c.state_id
                order by c.state_id limit 6""", Long.class, mhId, kaId);
        assertThat(others).hasSize(6);
        int n = 0;
        for (Long cityId : others) {
            ParkingListing l = new ParkingListing();
            l.setOwner(ownerA);
            l.setCity(cities.findById(cityId).orElseThrow());
            l.setTitle("Spot " + cityId);
            l.setAddress("Somewhere");
            l.setPincode("400001");
            l.setLat(19.0);
            l.setLng(72.8);
            l.setListingType(ListingType.OFFICE);
            l.setStatus(ListingStatus.APPROVED);
            l = listings.saveAndFlush(l);
            ParkingSlot s = new ParkingSlot();
            s.setListing(l);
            s.setLabel("A-01");
            s.setVehicleType(VehicleType.FOUR_WHEELER);
            s.setSize(SlotSize.MEDIUM);
            s = slots.saveAndFlush(s);
            Booking b = pay(booking(l, s, BookingStatus.CONFIRMED, d0, "10:00", d0.plusDays(1), "10:00", "11:00"),
                    PaymentStatus.CAPTURED);
            b.setTotalAmount(new BigDecimal(10 + ++n).setScale(2));
            bookings.saveAndFlush(b);
        }

        stats(d0, to)
                .andExpect(jsonPath("$.topCities", hasSize(5)))
                .andExpect(jsonPath("$.topCities[0].gmv").value(16.0))
                .andExpect(jsonPath("$.topCities[4].gmv").value(12.0))
                .andExpect(jsonPath("$.topStates", hasSize(5)))
                .andExpect(jsonPath("$.topStates[0].gmv").value(16.0));
    }

    @Test
    void statsOfASingleDayOnlyCountThatDay() throws Exception {
        bookingsFixture();

        stats(d0, d0)
                .andExpect(jsonPath("$.bookings.created").value(2))
                .andExpect(jsonPath("$.bookings.confirmed").value(2))
                .andExpect(jsonPath("$.bookings.conversionPercent").value(100.0))
                .andExpect(jsonPath("$.bookings.cancelled").value(1))
                .andExpect(jsonPath("$.money.gmv").value(67.08))
                .andExpect(jsonPath("$.money.refunds").value(67.08))
                .andExpect(jsonPath("$.series", hasSize(1)));
    }

    @Test
    void anEmptyRangeGivesZerosAndNoDivisionByZero() throws Exception {
        stats(d0, to)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings.created").value(0))
                .andExpect(jsonPath("$.bookings.conversionPercent").value(0.0))
                .andExpect(jsonPath("$.bookings.utilizationPercent").value(0.0))
                .andExpect(jsonPath("$.money.gmv").value(0.0))
                .andExpect(jsonPath("$.topStates", hasSize(0)))
                .andExpect(jsonPath("$.topCities", hasSize(0)))
                .andExpect(jsonPath("$.series", hasSize(5)));
    }

    @Test
    void statsDefaultToTheLastThirtyDaysAndValidateTheRange() throws Exception {
        LocalDate today = LocalDate.now(IST);
        getWith(admin, "/api/v1/admin/stats").andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.series", hasSize(30)));
        stats(today, today.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        stats(today.minusDays(366), today).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        stats(today.minusDays(365), today).andExpect(status().isOk()).andExpect(jsonPath("$.series", hasSize(366)));
    }

    // ---- reports --------------------------------------------------------------------------------------------

    @Test
    void usageReportListsCitiesByBookingsWithTotals() throws Exception {
        bookingsFixture();

        report("usage", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d0.toString()))
                .andExpect(jsonPath("$.to").value(to.toString()))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].cityId").value(puneId))
                .andExpect(jsonPath("$.rows[0].cityName").value("Pune"))
                .andExpect(jsonPath("$.rows[0].stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.rows[0].listings").value(1))
                .andExpect(jsonPath("$.rows[0].slots").value(1))
                .andExpect(jsonPath("$.rows[0].bookings").value(8))
                .andExpect(jsonPath("$.rows[0].bookedHours").value(6.0))
                .andExpect(jsonPath("$.rows[0].utilizationPercent").value(5.0))
                .andExpect(jsonPath("$.rows[0].cancellations").value(2))
                .andExpect(jsonPath("$.rows[1].cityName").value("Bengaluru"))
                .andExpect(jsonPath("$.rows[1].bookings").value(2))
                .andExpect(jsonPath("$.rows[1].bookedHours").value(5.0))
                .andExpect(jsonPath("$.rows[1].utilizationPercent").value(4.2))
                .andExpect(jsonPath("$.rows[1].cancellations").value(0))
                // approved listing without bookings still has a row
                .andExpect(jsonPath("$.rows[2].cityName").value("Hyderabad"))
                .andExpect(jsonPath("$.rows[2].listings").value(1))
                .andExpect(jsonPath("$.rows[2].bookings").value(0))
                .andExpect(jsonPath("$.rows[2].bookedHours").value(0.0))
                .andExpect(jsonPath("$.rows[2].utilizationPercent").value(0.0))
                .andExpect(jsonPath("$.totals.listings").value(3))
                .andExpect(jsonPath("$.totals.slots").value(3))
                .andExpect(jsonPath("$.totals.bookings").value(10))
                .andExpect(jsonPath("$.totals.bookedHours").value(11.0))
                .andExpect(jsonPath("$.totals.utilizationPercent").value(3.1))
                .andExpect(jsonPath("$.totals.cancellations").value(2));
    }

    @Test
    void revenueReportListsCitiesByGmvWithTotals() throws Exception {
        bookingsFixture();

        report("revenue", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].cityId").value(bengaluruId))
                .andExpect(jsonPath("$.rows[0].cityName").value("Bengaluru"))
                .andExpect(jsonPath("$.rows[0].stateName").value("Karnataka"))
                .andExpect(jsonPath("$.rows[0].bookings").value(2))
                .andExpect(jsonPath("$.rows[0].gmv").value(185.08))
                .andExpect(jsonPath("$.rows[0].platformFees").value(16.0))
                .andExpect(jsonPath("$.rows[0].gst").value(19.08))
                .andExpect(jsonPath("$.rows[0].refunds").value(0.0))
                .andExpect(jsonPath("$.rows[0].ownerEarnings").value(180.0))
                .andExpect(jsonPath("$.rows[1].cityName").value("Pune"))
                .andExpect(jsonPath("$.rows[1].bookings").value(8))
                .andExpect(jsonPath("$.rows[1].gmv").value(171.24))
                .andExpect(jsonPath("$.rows[1].platformFees").value(18.0))
                .andExpect(jsonPath("$.rows[1].gst").value(3.24))
                .andExpect(jsonPath("$.rows[1].refunds").value(164.16))
                .andExpect(jsonPath("$.rows[1].ownerEarnings").value(148.0))
                .andExpect(jsonPath("$.totals.bookings").value(10))
                .andExpect(jsonPath("$.totals.gmv").value(356.32))
                .andExpect(jsonPath("$.totals.platformFees").value(34.0))
                .andExpect(jsonPath("$.totals.gst").value(22.32))
                .andExpect(jsonPath("$.totals.refunds").value(164.16))
                .andExpect(jsonPath("$.totals.ownerEarnings").value(328.0));
    }

    @Test
    void reportsFilterByStateAndCity() throws Exception {
        bookingsFixture();

        report("usage", "&stateId=" + kaId)
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].cityName").value("Bengaluru"))
                .andExpect(jsonPath("$.totals.bookings").value(2))
                .andExpect(jsonPath("$.totals.utilizationPercent").value(4.2));
        report("revenue", "&cityId=" + puneId)
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].cityName").value("Pune"))
                .andExpect(jsonPath("$.totals.gmv").value(171.24));
        report("revenue", "&stateId=" + kaId + "&cityId=" + puneId) // contradictory: nothing matches
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.totals.gmv").value(0.0));
    }

    @Test
    void reportRangeIsValidatedAndDefaults() throws Exception {
        LocalDate today = LocalDate.now(IST);
        for (String kind : new String[] {"usage", "revenue"}) {
            getWith(admin, "/api/v1/admin/reports/" + kind + "?from=" + today + "&to=" + today.minusDays(1))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
            getWith(admin, "/api/v1/admin/reports/" + kind + "?from=" + today.minusDays(366) + "&to=" + today)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
            getWith(admin, "/api/v1/admin/reports/" + kind).andExpect(status().isOk())
                    .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()))
                    .andExpect(jsonPath("$.to").value(today.toString()));
            report(kind, "&format=xml").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    // ---- CSV ------------------------------------------------------------------------------------------------

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void usageCsvHasBomHeaderAndRows() throws Exception {
        bookingsFixture();

        ResultActions result = report("usage", "&format=csv")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"parkease-usage-report-" + d0 + "-to-" + to + ".csv\""))
                .andExpect(header().doesNotExist("X-Truncated"));
        String csv = body(result);
        assertThat(csv).startsWith(BOM + "City,State,Listings,Slots,Bookings,Booked hours,Utilization %,Cancellations\r\n");
        assertThat(csv.substring(1).split("\r\n")).containsExactly(
                "City,State,Listings,Slots,Bookings,Booked hours,Utilization %,Cancellations",
                "Pune,Maharashtra,1,1,8,6.0,5.0,2",
                "Bengaluru,Karnataka,1,1,2,5.0,4.2,0",
                "Hyderabad,Telangana,1,1,0,0.0,0.0,0");
        assertThat(csv).endsWith("\r\n");
    }

    @Test
    void revenueCsvHasBomHeaderRowsAndGuardsFormulasAndQuotes() throws Exception {
        bookingsFixture();
        jdbc.update("update cities set name = ? where id = ?", "=HYPERLINK(\"x\",\"y\")", hyderabadId);
        // give the renamed city a booking so that it appears in the revenue report
        Booking b = pay(booking(hyderabad, slots.findByListingIdOrderByLabelAsc(hyderabad.getId()).get(0),
                BookingStatus.CONFIRMED, d0, "13:00", d0.plusDays(1), "10:00", "11:00"), PaymentStatus.CAPTURED);
        b.setTotalAmount(new BigDecimal("1000.00"));
        bookings.saveAndFlush(b);

        ResultActions result = report("revenue", "&format=csv")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"parkease-revenue-report-" + d0 + "-to-" + to + ".csv\""));
        String csv = body(result);
        assertThat(csv.charAt(0)).isEqualTo('﻿');
        assertThat(csv.substring(1).split("\r\n")).containsExactly(
                "City,State,Bookings,GMV,Platform fees,GST,Refunds,Owner earnings",
                "\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\",Telangana,1,1000.00,6.00,1.08,0.00,0.00",
                "Bengaluru,Karnataka,2,185.08,16.00,19.08,0.00,180.00",
                "Pune,Maharashtra,8,171.24,18.00,3.24,164.16,148.00");
    }

    @Test
    void csvStopsAtTheRowCapAndSaysSo() throws Exception {
        bookingsFixture();
        ReportService target = AopTestUtils.getUltimateTargetObject(reportService);
        int original = target.csvMaxRows;
        target.csvMaxRows = 1;
        try {
            ResultActions result = report("usage", "&format=csv")
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Truncated", "true"));
            assertThat(body(result).substring(1).split("\r\n")).hasSize(2);
        } finally {
            target.csvMaxRows = original;
        }
    }

    // ---- authorization --------------------------------------------------------------------------------------

    @Test
    void everythingIsAdminOnly() throws Exception {
        String range = "?from=" + d0 + "&to=" + to;
        for (String url : new String[] {"/api/v1/admin/stats" + range, "/api/v1/admin/reports/usage" + range,
                "/api/v1/admin/reports/revenue" + range, "/api/v1/admin/reports/usage" + range + "&format=csv",
                "/api/v1/admin/reports/revenue" + range + "&format=csv"}) {
            getWith(driverAuth, url).andExpect(status().isForbidden());
            getWith(ownerAuth, url).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }
}
