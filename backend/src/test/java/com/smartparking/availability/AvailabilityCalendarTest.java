package com.smartparking.availability;

import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AvailabilityCalendarTest {

    private static final java.time.ZoneId IST = AvailabilityEvaluator.ZONE;

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired AvailabilityBlockRepository blocks;
    @Autowired BookingRepository bookings;

    Long listingId;
    ParkingListing listing;
    ParkingSlot slot1;
    User driver;
    LocalDate today;
    LocalDate day; // a day well inside the window, never today

    @BeforeEach
    void setUp() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "cal-owner@example.com");
        listingId = approvedListingAt(mvc, auth, listings, puneCityId(cities), "Calendar Spot", 18.5204, 73.8567, 30);
        listing = listings.findById(listingId).orElseThrow();
        slot1 = slots.findByListingIdOrderByLabelAsc(listingId).get(0);
        driver = users.save(TestUsers.newUser("cal-driver@example.com", Role.DRIVER));
        today = LocalDate.now(IST);
        day = today.plusDays(3);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static Instant at(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), IST).toInstant();
    }

    /** Opening hours 10:00-20:00 (600 minutes) every day except the given ISO weekdays (1 = Monday). */
    private void hours10to20(int... closedDays) {
        listing.setOpen24x7(false);
        listings.saveAndFlush(listing);
        for (int dow = 1; dow <= 7; dow++) {
            final int d = dow;
            if (java.util.Arrays.stream(closedDays).anyMatch(c -> c == d)) {
                continue;
            }
            AvailabilityRule rule = new AvailabilityRule();
            rule.setListing(listing);
            rule.setDayOfWeek(dow);
            rule.setOpenTime(LocalTime.of(10, 0));
            rule.setCloseTime(LocalTime.of(20, 0));
            rules.save(rule);
        }
        rules.flush();
    }

    private ParkingSlot addSlot(String label) {
        ParkingSlot s = new ParkingSlot();
        s.setListing(listing);
        s.setLabel(label);
        s.setVehicleType(VehicleType.FOUR_WHEELER);
        s.setSize(SlotSize.MEDIUM);
        return slots.saveAndFlush(s);
    }

    private Booking book(ParkingSlot slot, Instant start, Instant end, BookingStatus status) {
        Booking b = BookingTestSupport.booking(driver, listing, slot, status, start, end);
        if (status == BookingStatus.PENDING_PAYMENT) {
            b.setHoldExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        }
        return bookings.saveAndFlush(b);
    }

    private void block(ParkingSlot slotOrNull, Instant start, Instant end) {
        AvailabilityBlock b = new AvailabilityBlock();
        b.setListing(listing);
        b.setSlot(slotOrNull);
        b.setStartTime(start);
        b.setEndTime(end);
        blocks.saveAndFlush(b);
    }

    private ResultActions calendar(LocalDate from, LocalDate to) throws Exception {
        return mvc.perform(get("/api/v1/listings/" + listingId + "/availability?from=" + from + "&to=" + to));
    }

    private ResultActions oneDay(LocalDate date) throws Exception {
        return calendar(date, date);
    }

    /** Books slot1 from 10:00 for {@code minutes} on {@code day} and returns the day's bookedPercent/level check. */
    private ResultActions bookedMinutes(int minutes) throws Exception {
        bookings.deleteAll();
        bookings.flush();
        Instant start = at(day, "10:00");
        book(slot1, start, start.plus(minutes, ChronoUnit.MINUTES), BookingStatus.CONFIRMED);
        return oneDay(day);
    }

    // ---- days -----------------------------------------------------------------------------------------------

    @Test
    void emptyTwentyFourSevenListingIsAvailableEveryDay() throws Exception {
        calendar(today.plusDays(1), today.plusDays(3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(listingId))
                .andExpect(jsonPath("$.days", hasSize(3)))
                .andExpect(jsonPath("$.days[0].date").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.days[2].date").value(today.plusDays(3).toString()))
                .andExpect(jsonPath("$.days[0].level").value("AVAILABLE"))
                .andExpect(jsonPath("$.days[0].openTime").value("00:00"))
                .andExpect(jsonPath("$.days[0].closeTime").value("24:00"))
                .andExpect(jsonPath("$.days[0].bookedPercent").value(0))
                .andExpect(jsonPath("$.days[2].level").value("AVAILABLE"));
    }

    @Test
    void closedWeekdayHasNoTimesAndLevelClosed() throws Exception {
        LocalDate sunday = day.plusDays((7 - day.getDayOfWeek().getValue()) % 7);
        hours10to20(7);

        calendar(sunday.minusDays(1), sunday)
                .andExpect(jsonPath("$.days[0].level").value("AVAILABLE"))
                .andExpect(jsonPath("$.days[0].openTime").value("10:00"))
                .andExpect(jsonPath("$.days[0].closeTime").value("20:00"))
                .andExpect(jsonPath("$.days[1].date").value(sunday.toString()))
                .andExpect(jsonPath("$.days[1].level").value("CLOSED"))
                .andExpect(jsonPath("$.days[1].openTime").value(nullValue()))
                .andExpect(jsonPath("$.days[1].closeTime").value(nullValue()))
                .andExpect(jsonPath("$.days[1].bookedPercent").value(0));
    }

    @Test
    void closedDayStaysClosedEvenWithABlock() throws Exception {
        hours10to20(day.getDayOfWeek().getValue());
        block(null, at(day, "00:00"), at(day.plusDays(1), "00:00"));

        oneDay(day).andExpect(jsonPath("$.days[0].level").value("CLOSED"))
                .andExpect(jsonPath("$.days[0].bookedPercent").value(0));
    }

    @Test
    void levelThresholdsFollowTheFlooredBookedShare() throws Exception {
        hours10to20(); // 600 open minutes: every 6 minutes is one percent

        bookedMinutes(354).andExpect(jsonPath("$.days[0].bookedPercent").value(59))
                .andExpect(jsonPath("$.days[0].level").value("AVAILABLE"));
        bookedMinutes(359).andExpect(jsonPath("$.days[0].bookedPercent").value(59))
                .andExpect(jsonPath("$.days[0].level").value("AVAILABLE"));
        bookedMinutes(360).andExpect(jsonPath("$.days[0].bookedPercent").value(60))
                .andExpect(jsonPath("$.days[0].level").value("LIMITED"));
        bookedMinutes(582).andExpect(jsonPath("$.days[0].bookedPercent").value(97))
                .andExpect(jsonPath("$.days[0].level").value("LIMITED"));
        bookedMinutes(587).andExpect(jsonPath("$.days[0].bookedPercent").value(97))
                .andExpect(jsonPath("$.days[0].level").value("LIMITED"));
        bookedMinutes(588).andExpect(jsonPath("$.days[0].bookedPercent").value(98))
                .andExpect(jsonPath("$.days[0].level").value("FULL"));
        bookedMinutes(600).andExpect(jsonPath("$.days[0].bookedPercent").value(100))
                .andExpect(jsonPath("$.days[0].level").value("FULL"));
    }

    @Test
    void halfTheSlotsBookedAllDayIsFiftyPercentAndAvailable() throws Exception {
        hours10to20();
        addSlot("A-02");
        book(slot1, at(day, "10:00"), at(day, "20:00"), BookingStatus.CONFIRMED);

        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(50))
                .andExpect(jsonPath("$.days[0].level").value("AVAILABLE"));
    }

    @Test
    void threeOfFourSlotsBookedAllDayIsLimited() throws Exception {
        hours10to20();
        ParkingSlot s2 = addSlot("A-02");
        ParkingSlot s3 = addSlot("A-03");
        addSlot("A-04");
        book(slot1, at(day, "10:00"), at(day, "20:00"), BookingStatus.ACTIVE);
        book(s2, at(day, "09:00"), at(day, "21:00"), BookingStatus.AWAITING_APPROVAL); // clipped to the open window
        book(s3, at(day, "10:00"), at(day, "20:00"), BookingStatus.CONFIRMED);

        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(75))
                .andExpect(jsonPath("$.days[0].level").value("LIMITED"));
    }

    @Test
    void listingWideBlockMakesTheDayFull() throws Exception {
        hours10to20();
        addSlot("A-02");
        block(null, at(day, "10:00"), at(day, "20:00"));

        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(100))
                .andExpect(jsonPath("$.days[0].level").value("FULL"))
                .andExpect(jsonPath("$.days[0].openTime").value("10:00"));
    }

    @Test
    void slotBlockCountsForThatSlotOnly() throws Exception {
        hours10to20();
        ParkingSlot s2 = addSlot("A-02");
        block(s2, at(day, "10:00"), at(day, "20:00"));

        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(50));
    }

    @Test
    void overlappingBookingAndBlockOnOneSlotAreCountedOnce() throws Exception {
        hours10to20();
        book(slot1, at(day, "10:00"), at(day, "14:00"), BookingStatus.CONFIRMED);
        block(slot1, at(day, "12:00"), at(day, "16:00"));
        block(null, at(day, "13:00"), at(day, "15:00")); // inside the union already

        // union 10:00-16:00 = 360 of 600 minutes (double counting would give 80 or more)
        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(60))
                .andExpect(jsonPath("$.days[0].level").value("LIMITED"));
    }

    @Test
    void onlyLiveBookingsCount() throws Exception {
        hours10to20();
        book(slot1, at(day, "10:00"), at(day, "12:00"), BookingStatus.CANCELLED);
        book(slot1, at(day, "12:00"), at(day, "14:00"), BookingStatus.COMPLETED);
        book(slot1, at(day, "14:00"), at(day, "15:00"), BookingStatus.EXPIRED);
        book(slot1, at(day, "15:00"), at(day, "16:00"), BookingStatus.REJECTED);
        Booking lapsed = book(slot1, at(day, "16:00"), at(day, "17:00"), BookingStatus.PENDING_PAYMENT);
        lapsed.setHoldExpiresAt(Instant.now().minusSeconds(60));
        bookings.saveAndFlush(lapsed);

        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(0));

        book(slot1, at(day, "17:00"), at(day, "18:00"), BookingStatus.PENDING_PAYMENT); // unexpired hold
        oneDay(day).andExpect(jsonPath("$.days[0].bookedPercent").value(10));
    }

    @Test
    void bookingSpanningMidnightIsSplitAcrossDays() throws Exception {
        // 24x7: one slot, 22:00 to 03:00 next day -> 2 h of day one, 3 h of day two
        book(slot1, at(day, "22:00"), at(day.plusDays(1), "03:00"), BookingStatus.CONFIRMED);

        calendar(day, day.plusDays(1))
                .andExpect(jsonPath("$.days[0].bookedPercent").value(8))   // 120 / 1440
                .andExpect(jsonPath("$.days[1].bookedPercent").value(12)); // 180 / 1440
    }

    @Test
    void minutesBeforeNowCountAsTakenToday() throws Exception {
        Instant before = Instant.now();
        LocalDate date = before.atZone(IST).toLocalDate();
        ResultActions result = oneDay(date);
        Instant after = Instant.now();
        if (!after.atZone(IST).toLocalDate().equals(date) || !today.equals(date)) {
            return; // the IST date rolled over mid-test
        }
        long dayStart = at(date, "00:00").getEpochSecond();
        int low = (int) ((before.getEpochSecond() - dayStart) * 100 / 86_400);
        int high = (int) ((after.getEpochSecond() - dayStart) * 100 / 86_400);
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int percent = JsonPath.read(body, "$.days[0].bookedPercent");
        assertThat(percent).isBetween(low, high);
    }

    // ---- validation -----------------------------------------------------------------------------------------

    @Test
    void rangeBoundariesAreInclusive() throws Exception {
        calendar(today, today.plusDays(30)).andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(31)));
        calendar(today.plusDays(90), today.plusDays(90)).andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(1)));
        calendar(today.plusDays(60), today.plusDays(90)).andExpect(status().isOk());
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        calendar(today.plusDays(2), today.plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        calendar(today, today.plusDays(31)).andExpect(status().isBadRequest()) // 32 days
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        calendar(today.minusDays(1), today.plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        calendar(today.plusDays(80), today.plusDays(91)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mvc.perform(get("/api/v1/listings/" + listingId + "/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void onlyApprovedListingsHaveACalendar() throws Exception {
        for (ListingStatus hidden : List.of(ListingStatus.DRAFT, ListingStatus.PENDING_REVIEW,
                ListingStatus.REJECTED, ListingStatus.PAUSED, ListingStatus.SUSPENDED)) {
            listing.setStatus(hidden);
            listings.saveAndFlush(listing);
            oneDay(day).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/listings/999999/availability?from=" + day + "&to=" + day))
                .andExpect(status().isNotFound());
    }
}
