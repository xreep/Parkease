package com.smartparking.owner.dashboard;

import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OwnerDashboardTest {

    private static final java.time.ZoneId IST = AvailabilityEvaluator.ZONE;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired BookingRepository bookings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired AvailabilityBlockRepository blocks;

    String ownerAuth;
    String otherOwnerAuth;
    String driverAuth;
    User owner;
    User otherOwner;
    User driver;
    ParkingListing listing;
    ParkingSlot slot;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        ownerAuth = verifiedOwner(mvc, users, profiles, "dash-owner@example.com");
        otherOwnerAuth = verifiedOwner(mvc, users, profiles, "dash-owner2@example.com");
        driverAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "dash-driver@example.com", "DRIVER")));
        owner = users.findByEmail("dash-owner@example.com").orElseThrow();
        otherOwner = users.findByEmail("dash-owner2@example.com").orElseThrow();
        driver = users.save(TestUsers.newUser("dash-booker@example.com", Role.DRIVER));
        driver.setName("Rahul Sharma");
        users.saveAndFlush(driver);
        Long id = approvedListingAt(mvc, ownerAuth, listings, puneCityId(cities), "Stats Spot", 18.5204, 73.8567, 30);
        listing = listings.findById(id).orElseThrow();
        slot = slots.findByListingIdOrderByLabelAsc(id).get(0);
        today = LocalDate.now(IST);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static Instant at(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), IST).toInstant();
    }

    private Booking booking(ParkingListing l, ParkingSlot s, BookingStatus st, LocalDate day, String from, String to) {
        return bookings.saveAndFlush(BookingTestSupport.booking(driver, l, s, st, at(day, from), at(day, to)));
    }

    private Booking booking(BookingStatus st, LocalDate day, String from, String to) {
        return booking(listing, slot, st, day, from, to);
    }

    private OwnerEarning earning(Booking b, User who, String net, EarningStatus st) {
        OwnerEarning e = new OwnerEarning();
        e.setBooking(b);
        e.setOwner(who);
        e.setGross(new BigDecimal("60.00"));
        e.setCommission(new BigDecimal("6.00"));
        e.setNet(new BigDecimal(net));
        e.setStatus(st);
        return earnings.saveAndFlush(e);
    }

    private OwnerEarning earning(Booking b, String net, EarningStatus st) {
        return earning(b, owner, net, st);
    }

    private ResultActions getWith(String auth, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions stats(LocalDate from, LocalDate to) throws Exception {
        return getWith(ownerAuth, "/api/v1/owner/stats?from=" + from + "&to=" + to);
    }

    // ---- stats ----------------------------------------------------------------------------------------------

    /** The fixture behind {@link #statsAddUpEarningsBookingsOccupancyAndSeries()}; the range is d1..d3. */
    private void statsFixture(LocalDate d1) {
        LocalDate d2 = d1.plusDays(1);
        LocalDate d3 = d1.plusDays(2);
        earning(booking(BookingStatus.COMPLETED, d1, "10:00", "12:00"), "54.00", EarningStatus.PENDING_PAYOUT);
        earning(booking(BookingStatus.COMPLETED, d1, "14:00", "16:00"), "27.00", EarningStatus.PAID);
        earning(booking(BookingStatus.CONFIRMED, d2, "10:00", "14:00"), "90.00", EarningStatus.HELD);
        earning(booking(BookingStatus.CANCELLED, d2, "16:00", "18:00"), "0.00", EarningStatus.REVERSED);
        booking(BookingStatus.REJECTED, d3, "10:00", "12:00");
        booking(BookingStatus.EXPIRED, d3, "13:00", "14:00");          // never paid: not counted anywhere
        booking(BookingStatus.PENDING_PAYMENT, d3, "15:00", "16:00");  // idem
        earning(booking(BookingStatus.COMPLETED, d1.minusDays(2), "10:00", "12:00"), "1000.00", EarningStatus.HELD);

        // A second listing of the owner: paused, so outside the occupancy, but its bookings and reviews count.
        listing.setAvgRating(new BigDecimal("4.5"));
        listing.setReviewCount(2);
        listings.saveAndFlush(listing);
        ParkingListing second = pausedListing();
        earning(booking(second, secondSlot, BookingStatus.COMPLETED, d1, "11:00", "13:00"), "20.00",
                EarningStatus.PENDING_PAYOUT);

        // Somebody else's activity never shows up.
        ParkingListing foreign = foreignListing();
        earning(booking(foreign, foreignSlot, BookingStatus.COMPLETED, d1, "10:00", "12:00"), otherOwner, "500.00",
                EarningStatus.HELD);
    }

    ParkingSlot secondSlot;
    ParkingSlot foreignSlot;

    private ParkingListing pausedListing() throws RuntimeException {
        try {
            Long id = approvedListingAt(mvc, ownerAuth, listings, puneCityId(cities), "Paused Spot", 18.53, 73.85, 30);
            ParkingListing l = listings.findById(id).orElseThrow();
            l.setStatus(ListingStatus.PAUSED);
            l.setAvgRating(new BigDecimal("3.0"));
            l.setReviewCount(1);
            listings.saveAndFlush(l);
            secondSlot = slots.findByListingIdOrderByLabelAsc(id).get(0);
            return l;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ParkingListing foreignListing() {
        try {
            Long id = approvedListingAt(mvc, otherOwnerAuth, listings, puneCityId(cities), "Foreign Spot", 18.54, 73.86, 30);
            foreignSlot = slots.findByListingIdOrderByLabelAsc(id).get(0);
            return listings.findById(id).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void statsAddUpEarningsBookingsOccupancyAndSeries() throws Exception {
        LocalDate d1 = today.minusDays(10);
        statsFixture(d1);
        // upcoming: six future bookings of the owner, the first five by start are returned
        for (int i = 1; i <= 6; i++) {
            booking(i == 2 ? BookingStatus.AWAITING_APPROVAL : BookingStatus.CONFIRMED, today.plusDays(i), "10:00", "12:00");
        }
        booking(BookingStatus.CANCELLED, today.plusDays(1), "14:00", "15:00"); // not upcoming
        booking(BookingStatus.PENDING_PAYMENT, today.plusDays(1), "16:00", "17:00");

        stats(d1, d1.plusDays(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d1.toString()))
                .andExpect(jsonPath("$.to").value(d1.plusDays(2).toString()))
                .andExpect(jsonPath("$.totals.earningsNet").value(191.0))
                .andExpect(jsonPath("$.totals.bookings").value(4))
                .andExpect(jsonPath("$.totals.cancellations").value(2))
                .andExpect(jsonPath("$.totals.occupancyPercent").value(11.1)) // 8 booked of 72 open slot-hours
                .andExpect(jsonPath("$.totals.avgRating").value(4.0))        // (4.5 * 2 + 3.0 * 1) / 3
                .andExpect(jsonPath("$.totals.reviewCount").value(3))
                .andExpect(jsonPath("$.balances.held").value(1090.0))
                .andExpect(jsonPath("$.balances.pendingPayout").value(74.0))
                .andExpect(jsonPath("$.balances.paid").value(27.0))
                .andExpect(jsonPath("$.pendingApprovals").value(1))
                .andExpect(jsonPath("$.series", hasSize(3)))
                .andExpect(jsonPath("$.series[0].date").value(d1.toString()))
                .andExpect(jsonPath("$.series[0].earningsNet").value(101.0))
                .andExpect(jsonPath("$.series[0].bookings").value(3))
                .andExpect(jsonPath("$.series[1].earningsNet").value(90.0))
                .andExpect(jsonPath("$.series[1].bookings").value(1))
                .andExpect(jsonPath("$.series[2].date").value(d1.plusDays(2).toString()))
                .andExpect(jsonPath("$.series[2].earningsNet").value(0.0))
                .andExpect(jsonPath("$.series[2].bookings").value(0))
                .andExpect(jsonPath("$.upcoming", hasSize(5)))
                .andExpect(jsonPath("$.upcoming[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.upcoming[1].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.upcoming[0].listingTitle").value("Stats Spot"))
                .andExpect(jsonPath("$.upcoming[0].driverFirstName").value("Rahul"));
    }

    @Test
    void statsDefaultToTheLastThirtyDaysEndingTodayWithZeroFilledSeries() throws Exception {
        getWith(ownerAuth, "/api/v1/owner/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.series", hasSize(30)))
                .andExpect(jsonPath("$.series[29].date").value(today.toString()))
                .andExpect(jsonPath("$.totals.earningsNet").value(0.0))
                .andExpect(jsonPath("$.totals.bookings").value(0))
                .andExpect(jsonPath("$.totals.occupancyPercent").value(0.0))
                .andExpect(jsonPath("$.totals.avgRating").value(0.0))
                .andExpect(jsonPath("$.balances.held").value(0.0))
                .andExpect(jsonPath("$.upcoming", hasSize(0)));
    }

    @Test
    void statsRangeIsValidated() throws Exception {
        stats(today, today.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        stats(today.minusDays(366), today).andExpect(status().isBadRequest()) // 367 days
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        stats(today.minusDays(365), today).andExpect(status().isOk())
                .andExpect(jsonPath("$.series", hasSize(366)));
    }

    @Test
    void dashboardEndpointsAreOwnerOnly() throws Exception {
        for (String url : new String[] {"/api/v1/owner/stats", "/api/v1/owner/earnings",
                "/api/v1/owner/calendar?listingId=" + listing.getId() + "&from=" + today + "&to=" + today}) {
            getWith(driverAuth, url).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    // ---- earnings -------------------------------------------------------------------------------------------

    private void ledger() {
        LocalDate base = today.minusDays(20);
        Booking b1 = booking(BookingStatus.COMPLETED, base, "10:00", "12:00");
        OwnerEarning paid = earning(b1, "54.00", EarningStatus.PAID);
        paid.setPaidAt(Instant.parse("2026-09-30T06:30:00Z"));
        paid.setPayoutReference("UTR-1");
        earnings.saveAndFlush(paid);
        earning(booking(BookingStatus.COMPLETED, base.plusDays(1), "10:00", "12:00"), "45.00", EarningStatus.PENDING_PAYOUT);
        earning(booking(BookingStatus.CONFIRMED, base.plusDays(2), "10:00", "12:00"), "90.00", EarningStatus.HELD);
        earning(booking(BookingStatus.CANCELLED, base.plusDays(3), "10:00", "12:00"), "0.00", EarningStatus.REVERSED);
        // a foreign earning
        ParkingListing foreign = foreignListing();
        earning(booking(foreign, foreignSlot, BookingStatus.COMPLETED, base, "10:00", "12:00"), otherOwner, "999.00",
                EarningStatus.PAID);
    }

    @Test
    void earningsAreNewestBookingFirstWithTotalsOverEverything() throws Exception {
        ledger();

        getWith(ownerAuth, "/api/v1/owner/earnings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.held").value(90.0))
                .andExpect(jsonPath("$.totals.pendingPayout").value(45.0))
                .andExpect(jsonPath("$.totals.paid").value(54.0))
                .andExpect(jsonPath("$.totals.reversedCount").value(1))
                .andExpect(jsonPath("$.earnings.totalElements").value(4))
                .andExpect(jsonPath("$.earnings.content[*].status",
                        contains("REVERSED", "HELD", "PENDING_PAYOUT", "PAID")))
                .andExpect(jsonPath("$.earnings.content[0].net").value(0.0))
                .andExpect(jsonPath("$.earnings.content[1].gross").value(60.0))
                .andExpect(jsonPath("$.earnings.content[1].commission").value(6.0))
                .andExpect(jsonPath("$.earnings.content[1].listingTitle").value("Stats Spot"))
                .andExpect(jsonPath("$.earnings.content[1].bookingCode").isNotEmpty())
                .andExpect(jsonPath("$.earnings.content[1].bookingId").isNumber())
                .andExpect(jsonPath("$.earnings.content[1].startTime").isNotEmpty())
                .andExpect(jsonPath("$.earnings.content[1].paidAt").value(nullValue()))
                .andExpect(jsonPath("$.earnings.content[3].paidAt").isNotEmpty())
                .andExpect(jsonPath("$.earnings.content[3].payoutReference").value("UTR-1"));
    }

    @Test
    void earningsFilterByStatusAndBookingStartDateAndPage() throws Exception {
        ledger();
        LocalDate base = today.minusDays(20);

        getWith(ownerAuth, "/api/v1/owner/earnings?status=PAID")
                .andExpect(jsonPath("$.earnings.content", hasSize(1)))
                .andExpect(jsonPath("$.earnings.content[0].payoutReference").value("UTR-1"))
                .andExpect(jsonPath("$.totals.held").value(90.0)); // totals ignore the filter
        getWith(ownerAuth, "/api/v1/owner/earnings?from=" + base.plusDays(1) + "&to=" + base.plusDays(2))
                .andExpect(jsonPath("$.earnings.content[*].status", contains("HELD", "PENDING_PAYOUT")));
        getWith(ownerAuth, "/api/v1/owner/earnings?from=" + base.plusDays(3))
                .andExpect(jsonPath("$.earnings.content", hasSize(1)));
        getWith(ownerAuth, "/api/v1/owner/earnings?page=1&size=3")
                .andExpect(jsonPath("$.earnings.content", hasSize(1)))
                .andExpect(jsonPath("$.earnings.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.earnings.totalPages").value(2))
                .andExpect(jsonPath("$.earnings.size").value(3));
        getWith(ownerAuth, "/api/v1/owner/earnings?from=" + today + "&to=" + today.minusDays(1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        getWith(otherOwnerAuth, "/api/v1/owner/earnings")
                .andExpect(jsonPath("$.earnings.totalElements").value(1))
                .andExpect(jsonPath("$.totals.paid").value(999.0));
    }

    @Test
    void earningsCsvHasTheLedgerRowsQuotedAndSafeFromFormulaInjection() throws Exception {
        LocalDate day = today.minusDays(5);
        listing.setTitle("=SUM(1,2) \"Bay\"");
        listings.saveAndFlush(listing);
        Booking b = booking(BookingStatus.COMPLETED, day, "10:00", "12:00");
        OwnerEarning e = earning(b, "54.00", EarningStatus.PAID);
        e.setPaidAt(at(day.plusDays(1), "09:30"));
        e.setPayoutReference("@UTR,1");
        earnings.saveAndFlush(e);
        Booking plain = booking(BookingStatus.COMPLETED, day.minusDays(1), "10:00", "12:00");
        earning(plain, "45.00", EarningStatus.HELD);

        String csv = getWith(ownerAuth, "/api/v1/owner/earnings?format=csv")
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CONTENT_DISPOSITION,
                                org.hamcrest.Matchers.containsString("parkease-earnings-" + today + ".csv")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo(
                "Booking,Listing,Start (IST),End (IST),Gross,Commission,Net,Status,Paid at,Payout reference");
        assertThat(lines[1]).isEqualTo(b.getBookingCode() + ",\"'=SUM(1,2) \"\"Bay\"\"\"," + CSV_TIME.format(
                b.getStartTime().atZone(IST)) + "," + CSV_TIME.format(b.getEndTime().atZone(IST))
                + ",60.00,6.00,54.00,PAID," + CSV_TIME.format(e.getPaidAt().atZone(IST)) + ",\"'@UTR,1\"");
        assertThat(lines[2]).startsWith(plain.getBookingCode() + ",\"'=SUM").endsWith(",60.00,6.00,45.00,HELD,,");
        // (the second booking is on the same listing, so it carries the same dangerous title)

        String filtered = getWith(ownerAuth, "/api/v1/owner/earnings?format=csv&status=HELD")
                .andReturn().getResponse().getContentAsString();
        assertThat(filtered.split("\r\n")).hasSize(2);
    }

    // ---- calendar -------------------------------------------------------------------------------------------

    @Test
    void calendarListsSlotsLiveBookingsAndBlocks() throws Exception {
        LocalDate from = today.plusDays(1);
        ParkingSlot s2 = new ParkingSlot();
        s2.setListing(listing);
        s2.setLabel("A-02");
        s2.setVehicleType(com.smartparking.common.model.VehicleType.FOUR_WHEELER);
        s2.setSize(com.smartparking.slot.SlotSize.MEDIUM);
        slots.saveAndFlush(s2);
        ParkingSlot inactive = new ParkingSlot();
        inactive.setListing(listing);
        inactive.setLabel("A-03");
        inactive.setVehicleType(com.smartparking.common.model.VehicleType.FOUR_WHEELER);
        inactive.setSize(com.smartparking.slot.SlotSize.MEDIUM);
        inactive.setActive(false);
        slots.saveAndFlush(inactive);

        Booking confirmed = booking(BookingStatus.CONFIRMED, from, "10:00", "12:00");
        Booking completed = booking(listing, s2, BookingStatus.COMPLETED, from, "08:00", "09:00");
        booking(BookingStatus.CANCELLED, from, "13:00", "14:00");
        booking(BookingStatus.EXPIRED, from, "15:00", "16:00");
        Booking lapsed = booking(BookingStatus.PENDING_PAYMENT, from, "17:00", "18:00");
        lapsed.setHoldExpiresAt(Instant.now().minusSeconds(60));
        bookings.saveAndFlush(lapsed);
        booking(BookingStatus.CONFIRMED, from.plusDays(14), "10:00", "12:00"); // outside the range
        AvailabilityBlock slotBlock = new AvailabilityBlock();
        slotBlock.setListing(listing);
        slotBlock.setSlot(s2);
        slotBlock.setStartTime(at(from, "20:00"));
        slotBlock.setEndTime(at(from.plusDays(1), "02:00"));
        slotBlock.setReason("Repairs");
        blocks.saveAndFlush(slotBlock);
        AvailabilityBlock wide = new AvailabilityBlock();
        wide.setListing(listing);
        wide.setStartTime(at(from.plusDays(2), "00:00"));
        wide.setEndTime(at(from.plusDays(2), "06:00"));
        blocks.saveAndFlush(wide);

        getWith(ownerAuth, "/api/v1/owner/calendar?listingId=" + listing.getId() + "&from=" + from + "&to="
                + from.plusDays(6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(listing.getId()))
                .andExpect(jsonPath("$.from").value(from.toString()))
                .andExpect(jsonPath("$.to").value(from.plusDays(6).toString()))
                .andExpect(jsonPath("$.slots", hasSize(2)))
                .andExpect(jsonPath("$.slots[0].label").value("A-01"))
                .andExpect(jsonPath("$.slots[1].id").value(s2.getId()))
                .andExpect(jsonPath("$.bookings", hasSize(2)))
                .andExpect(jsonPath("$.bookings[?(@.id == " + confirmed.getId() + ")].driverName").value("Rahul S."))
                .andExpect(jsonPath("$.bookings[?(@.id == " + confirmed.getId() + ")].slotId").value(slot.getId().intValue()))
                .andExpect(jsonPath("$.bookings[?(@.id == " + confirmed.getId() + ")].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookings[?(@.id == " + completed.getId() + ")].bookingCode")
                        .value(completed.getBookingCode()))
                .andExpect(jsonPath("$.bookings[?(@.id == " + completed.getId() + ")].status").value("COMPLETED"))
                .andExpect(jsonPath("$.blocks", hasSize(2)))
                .andExpect(jsonPath("$.blocks[?(@.reason == 'Repairs')].slotId").value(s2.getId().intValue()))
                .andExpect(jsonPath("$.blocks[?(@.slotId == null)]", hasSize(1)));
    }

    @Test
    void calendarRulesAndAuthorization() throws Exception {
        String base = "/api/v1/owner/calendar?listingId=" + listing.getId();
        getWith(ownerAuth, base + "&from=" + today + "&to=" + today.plusDays(13)).andExpect(status().isOk());
        getWith(ownerAuth, base + "&from=" + today + "&to=" + today.plusDays(14))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        getWith(ownerAuth, base + "&from=" + today.plusDays(1) + "&to=" + today)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        getWith(otherOwnerAuth, base + "&from=" + today + "&to=" + today).andExpect(status().isNotFound());
        getWith(ownerAuth, "/api/v1/owner/calendar?listingId=999999&from=" + today + "&to=" + today)
                .andExpect(status().isNotFound());
        // past weeks are fine for an owner looking back
        getWith(ownerAuth, base + "&from=" + today.minusDays(7) + "&to=" + today.minusDays(1)).andExpect(status().isOk());
        assertThat(ChronoUnit.DAYS.between(today.minusDays(7), today)).isEqualTo(7);
    }
}
