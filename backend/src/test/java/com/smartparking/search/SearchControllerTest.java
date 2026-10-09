package com.smartparking.search;

import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class SearchControllerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double LAT = 18.5204;
    private static final double LNG = 73.8567;
    /** One kilometre of latitude, in degrees. */
    private static final double KM = 1 / 111.2;

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired AvailabilityBlockRepository blocks;
    @Autowired BookingRepository bookings;

    String auth;
    Long pune;

    @BeforeEach
    void setUp() throws Exception {
        auth = verifiedOwner(mvc, users, profiles, "search-owner@example.com");
        pune = puneCityId(cities);
    }

    private Long listing(String title, double kmNorth, double pricePerHour) throws Exception {
        return approvedListingAt(mvc, auth, listings, pune, title, LAT + kmNorth * KM, LNG, pricePerHour);
    }

    private void edit(Long id, java.util.function.Consumer<ParkingListing> change) {
        ParkingListing l = listings.findById(id).orElseThrow();
        change.accept(l);
        listings.saveAndFlush(l);
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG + query));
    }

    private static String instants(Instant start, Instant end) {
        return "&start=" + start + "&end=" + end;
    }

    private static Instant istInstant(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(IST).toInstant();
    }

    /** First day of the given weekday at least two days from now (IST), so windows are always in the future. */
    private static LocalDate nextDay(DayOfWeek dow) {
        return LocalDate.now(IST).plusDays(2).with(TemporalAdjusters.nextOrSame(dow));
    }

    private void openMonToSat(Long id) {
        ParkingListing l = listings.getReferenceById(id);
        for (int d = 1; d <= 6; d++) {
            AvailabilityRule r = new AvailabilityRule();
            r.setListing(l);
            r.setDayOfWeek(d);
            r.setOpenTime(LocalTime.of(8, 0));
            r.setCloseTime(LocalTime.of(22, 0));
            rules.save(r);
        }
        edit(id, x -> x.setOpen24x7(false));
    }

    @Test
    void findsApprovedListingsNearestFirst() throws Exception {
        Long far = listing("Three km", 3, 30);
        Long near = listing("Half km", 0.5, 30);
        Long draft = createListing(mvc, auth, pune);
        edit(draft, d -> {
            d.setLat(LAT + 0.2 * KM);
            d.setLng(LNG);
        });
        search("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(near))
                .andExpect(jsonPath("$.content[1].id").value(far))
                .andExpect(jsonPath("$.content[0].distanceKm").value(closeTo(0.5, 0.1)))
                .andExpect(jsonPath("$.content[0].title").value("Half km"))
                .andExpect(jsonPath("$.content[0].cityName").value("Pune"))
                .andExpect(jsonPath("$.content[0].stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.content[0].coverPhotoUrl").isNotEmpty())
                .andExpect(jsonPath("$.content[0].amenities[0]").value("CCTV"))
                .andExpect(jsonPath("$.content[0].totalSlots").value(1))
                .andExpect(jsonPath("$.content[0].freeSlots").value(nullValue()))
                .andExpect(jsonPath("$.content[0].quote").value(nullValue()))
                .andExpect(jsonPath("$.window").value(nullValue()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.radiusKm").value(5.0))
                .andExpect(jsonPath("$.center.lat").value(LAT))
                .andExpect(jsonPath("$.center.lng").value(LNG))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.not(hasItem(draft.intValue()))));
    }

    @Test
    void radiusExcludesFarListings() throws Exception {
        Long far = listing("Eight km", 8, 30);
        search("").andExpect(jsonPath("$.content").value(empty()));
        search("&radiusKm=10").andExpect(jsonPath("$.content[0].id").value(far))
                .andExpect(jsonPath("$.radiusKm").value(10.0));
        search("&radiusKm=500").andExpect(jsonPath("$.radiusKm").value(25.0));
        search("&radiusKm=0.01").andExpect(jsonPath("$.radiusKm").value(0.5));
    }

    @Test
    void windowFiltersClosedAndBlocked() throws Exception {
        Long a = listing("Always open", 1, 30);
        Long b = listing("Mon-Sat only", 2, 30);
        openMonToSat(b);

        LocalDate sunday = nextDay(DayOfWeek.SUNDAY);
        Instant sStart = istInstant(sunday, 10);
        Instant sEnd = istInstant(sunday, 13);
        search(instants(sStart, sEnd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a))
                .andExpect(jsonPath("$.content[0].freeSlots").value(1))
                .andExpect(jsonPath("$.content[0].totalSlots").value(1))
                .andExpect(jsonPath("$.content[0].quote.baseAmount").value(90.00))
                .andExpect(jsonPath("$.content[0].quote.totalAmount").value(100.62))
                .andExpect(jsonPath("$.window.start").isNotEmpty())
                .andExpect(jsonPath("$.window.end").isNotEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));

        LocalDate monday = nextDay(DayOfWeek.MONDAY);
        Instant mStart = istInstant(monday, 10);
        Instant mEnd = istInstant(monday, 13);
        search(instants(mStart, mEnd))
                .andExpect(jsonPath("$.content[*].id").value(contains(a.intValue(), b.intValue())));

        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listings.getReferenceById(a));
        block.setStartTime(mStart);
        block.setEndTime(mEnd);
        blocks.saveAndFlush(block);
        search(instants(mStart, mEnd))
                .andExpect(jsonPath("$.content[*].id").value(contains(b.intValue())));
    }

    @Test
    void slotBlockReducesFreeSlotsAndFullyBookedIsDropped() throws Exception {
        Long a = listing("Two slots", 1, 30);
        ParkingSlot extra = new ParkingSlot();
        extra.setListing(listings.getReferenceById(a));
        extra.setLabel("A-02");
        extra.setVehicleType(com.smartparking.common.model.VehicleType.FOUR_WHEELER);
        extra.setSize(com.smartparking.slot.SlotSize.MEDIUM);
        slots.saveAndFlush(extra);
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        Instant start = istInstant(day, 10);
        Instant end = istInstant(day, 12);
        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listings.getReferenceById(a));
        block.setSlot(extra);
        block.setStartTime(start);
        block.setEndTime(end);
        blocks.saveAndFlush(block);
        search(instants(start, end))
                .andExpect(jsonPath("$.content[0].freeSlots").value(1))
                .andExpect(jsonPath("$.content[0].totalSlots").value(2));
        ParkingSlot first = slots.findByListingIdOrderByLabelAsc(a).get(0);
        AvailabilityBlock second = new AvailabilityBlock();
        second.setListing(listings.getReferenceById(a));
        second.setSlot(first);
        second.setStartTime(start);
        second.setEndTime(end);
        blocks.saveAndFlush(second);
        search(instants(start, end)).andExpect(jsonPath("$.content").value(empty()));
    }

    @Test
    void liveBookingRemovesListingFromWindowedResultsOnly() throws Exception {
        Long a = listing("Booked spot", 1, 30);
        Long b = listing("Free spot", 2, 30);
        var driver = users.save(TestUsers.newUser("search-driver@example.com", Role.DRIVER));
        ParkingSlot slot = slots.findByListingIdOrderByLabelAsc(a).get(0);
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        Instant start = istInstant(day, 10);
        Instant end = istInstant(day, 13);
        bookings.saveAndFlush(BookingTestSupport.booking(driver, listings.getReferenceById(a), slot,
                BookingStatus.CONFIRMED, istInstant(day, 11), istInstant(day, 12)));

        search(instants(start, end))
                .andExpect(jsonPath("$.content[*].id").value(contains(b.intValue())));
        // A window that only touches the booking still finds both.
        search(instants(istInstant(day, 12), istInstant(day, 14)))
                .andExpect(jsonPath("$.content[*].id").value(contains(a.intValue(), b.intValue())));
        // Without a window the listing remains visible.
        search("").andExpect(jsonPath("$.content[*].id").value(contains(a.intValue(), b.intValue())));
    }

    @Test
    void expiredUnpaidHoldDoesNotBlockSearch() throws Exception {
        Long a = listing("Held spot", 1, 30);
        var driver = users.save(TestUsers.newUser("search-driver2@example.com", Role.DRIVER));
        ParkingSlot slot = slots.findByListingIdOrderByLabelAsc(a).get(0);
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        Instant start = istInstant(day, 10);
        Instant end = istInstant(day, 13);
        var hold = BookingTestSupport.booking(driver, listings.getReferenceById(a), slot,
                BookingStatus.PENDING_PAYMENT, start, end);
        hold.setHoldExpiresAt(Instant.now().minusSeconds(60));
        bookings.saveAndFlush(hold);
        search(instants(start, end)).andExpect(jsonPath("$.content[*].id").value(contains(a.intValue())));

        hold.setHoldExpiresAt(Instant.now().plusSeconds(600));
        bookings.saveAndFlush(hold);
        search(instants(start, end)).andExpect(jsonPath("$.content").value(empty()));
    }

    @Test
    void vehicleTypeFilter() throws Exception {
        Long onlyCars = listing("Cars only", 1, 30);
        Long mixed = listing("Cars and bikes", 2, 30);
        ParkingSlot bike = new ParkingSlot();
        bike.setListing(listings.getReferenceById(mixed));
        bike.setLabel("B-01");
        bike.setVehicleType(com.smartparking.common.model.VehicleType.TWO_WHEELER);
        bike.setSize(com.smartparking.slot.SlotSize.SMALL);
        slots.saveAndFlush(bike);
        search("&vehicleType=TWO_WHEELER")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mixed))
                .andExpect(jsonPath("$.content[0].totalSlots").value(1));
        search("&vehicleType=FOUR_WHEELER")
                .andExpect(jsonPath("$.content[*].id").value(contains(onlyCars.intValue(), mixed.intValue())));
        search("").andExpect(jsonPath("$.content[1].totalSlots").value(2));
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        search(instants(istInstant(day, 10), istInstant(day, 12)) + "&vehicleType=TWO_WHEELER")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mixed));
    }

    @Test
    void inactiveSlotsDoNotCount() throws Exception {
        Long a = listing("Inactive", 1, 30);
        ParkingSlot slot = slots.findByListingIdOrderByLabelAsc(a).get(0);
        slot.setActive(false);
        slots.saveAndFlush(slot);
        search("").andExpect(jsonPath("$.content").value(empty()));
    }

    @Test
    void filtersByTypeAmenitiesPriceAnd24x7() throws Exception {
        Long l1 = listing("L1 office both cheap 24x7", 1, 30);
        Long l2 = listing("L2 residential cctv only", 1.1, 30);
        Long l3 = listing("L3 office both pricey", 1.2, 50);
        Long l4 = listing("L4 office both cheap hours", 1.3, 30);
        edit(l1, l -> {
            l.setListingType(ListingType.OFFICE);
            l.getAmenities().add(Amenity.COVERED);
        });
        edit(l2, l -> l.setListingType(ListingType.RESIDENTIAL));
        edit(l3, l -> {
            l.setListingType(ListingType.OFFICE);
            l.getAmenities().add(Amenity.COVERED);
        });
        edit(l4, l -> {
            l.setListingType(ListingType.OFFICE);
            l.getAmenities().add(Amenity.COVERED);
        });
        openMonToSat(l4);

        search("&types=OFFICE").andExpect(jsonPath("$.content[*].id")
                .value(containsInAnyOrder(l1.intValue(), l3.intValue(), l4.intValue())));
        search("&types=OFFICE&types=RESIDENTIAL").andExpect(jsonPath("$.content.length()").value(4));
        search("&types=METRO").andExpect(jsonPath("$.content").value(empty()));
        search("&amenities=CCTV&amenities=COVERED").andExpect(jsonPath("$.content[*].id")
                .value(containsInAnyOrder(l1.intValue(), l3.intValue(), l4.intValue())));
        search("&amenities=CCTV").andExpect(jsonPath("$.content.length()").value(4));
        search("&amenities=EV_CHARGING").andExpect(jsonPath("$.content").value(empty()));
        search("&maxPricePerHour=35").andExpect(jsonPath("$.content[*].id")
                .value(containsInAnyOrder(l1.intValue(), l2.intValue(), l4.intValue())));
        search("&open24x7=true").andExpect(jsonPath("$.content[*].id")
                .value(containsInAnyOrder(l1.intValue(), l2.intValue(), l3.intValue())));
        search("&open24x7=false").andExpect(jsonPath("$.content.length()").value(4));
        search("&types=OFFICE&amenities=CCTV&amenities=COVERED&maxPricePerHour=35&open24x7=true")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(l1));
    }

    @Test
    void sortByPriceAndPagination() throws Exception {
        Long mid = listing("Mid", 1, 35);
        Long cheap = listing("Cheap", 2, 20);
        Long pricey = listing("Pricey", 0.5, 50);
        search("&sort=price&size=2")
                .andExpect(jsonPath("$.content[*].id").value(contains(cheap.intValue(), mid.intValue())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(2));
        search("&sort=price&size=2&page=1")
                .andExpect(jsonPath("$.content[*].id").value(contains(pricey.intValue())))
                .andExpect(jsonPath("$.page").value(1));
        search("&sort=price&size=2&page=5").andExpect(jsonPath("$.content").value(empty()))
                .andExpect(jsonPath("$.totalElements").value(3));
        search("&size=500").andExpect(jsonPath("$.size").value(50));
        search("&size=0").andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void sortByPriceWithWindowUsesQuoteTotal() throws Exception {
        // Hourly 50 with a daily cap of 100 beats hourly 30 for a full-day window.
        Long hourlyOnly = listing("Hourly only", 1, 30);
        Long withDaily = listing("With daily cap", 2, 50);
        edit(withDaily, l -> l.setPricePerDay(new BigDecimal("100")));
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        Instant start = istInstant(day, 0);
        Instant end = istInstant(day.plusDays(1), 0);
        search(instants(start, end) + "&sort=price")
                .andExpect(jsonPath("$.content[*].id").value(contains(withDaily.intValue(), hourlyOnly.intValue())))
                .andExpect(jsonPath("$.content[0].quote.pricingMode").value("DAILY"))
                .andExpect(jsonPath("$.content[0].quote.baseAmount").value(100.00));
    }

    @Test
    void sortByRating() throws Exception {
        Long unrated = listing("Unrated", 0.5, 30);
        Long good = listing("Good", 2, 30);
        Long best = listing("Best", 3, 30);
        Long bestMoreReviews = listing("Best with more reviews", 4, 30);
        edit(good, l -> {
            l.setAvgRating(new BigDecimal("4.0"));
            l.setReviewCount(10);
        });
        edit(best, l -> {
            l.setAvgRating(new BigDecimal("4.5"));
            l.setReviewCount(3);
        });
        edit(bestMoreReviews, l -> {
            l.setAvgRating(new BigDecimal("4.5"));
            l.setReviewCount(30);
        });
        search("&sort=rating").andExpect(jsonPath("$.content[*].id").value(
                contains(bestMoreReviews.intValue(), best.intValue(), good.intValue(), unrated.intValue())));
    }

    @Test
    void validation() throws Exception {
        mvc.perform(get("/api/v1/search?lng=" + LNG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
        mvc.perform(get("/api/v1/search"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
        mvc.perform(get("/api/v1/search?lat=51&lng=" + LNG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=120"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
        LocalDate day = nextDay(DayOfWeek.MONDAY);
        search("&start=" + istInstant(day, 10))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        search("&end=" + istInstant(day, 10))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        search(instants(istInstant(day, 10), istInstant(day, 10).plusSeconds(1800)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        search("&sort=nonsense").andExpect(status().isBadRequest());
    }

    @Test
    void maxPricePerHourMustBePositiveAndReasonable() throws Exception {
        for (String bad : new String[] {"0", "-5", "1000000000", "100000.01"}) {
            search("&maxPricePerHour=" + bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.detail").value("maxPricePerHour must be between 1 and 100000"));
        }
        search("&maxPricePerHour=100000").andExpect(status().isOk());
        search("&maxPricePerHour=1").andExpect(status().isOk());
    }

    @Test
    void publicFieldsOnlyAndNoAuthNeeded() throws Exception {
        listing("Public", 1, 30);
        search("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").doesNotExist())
                .andExpect(jsonPath("$.content[0].rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.content[0].submittedAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].owner").doesNotExist())
                .andExpect(jsonPath("$.content[0].storageKey").doesNotExist());
    }

    @Test
    void emptyWhenNothingNearby() throws Exception {
        search("").andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(empty()))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }
}
