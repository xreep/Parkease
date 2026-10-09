package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.approveListing;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PublicListingControllerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired ParkingListingRepository listings;
    @Autowired AvailabilityBlockRepository blocks;
    @Autowired BookingRepository bookings;
    @Autowired ParkingSlotRepository slots;

    Long approvedId;
    Long draftId;
    Instant start;
    Instant end;

    @BeforeEach
    void setUp() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "public-owner@example.com");
        approvedId = createListing(mvc, auth, puneCityId(cities));
        makeComplete(mvc, auth, approvedId);
        approveListing(listings, approvedId);
        draftId = createListing(mvc, auth, puneCityId(cities));
        // Day after tomorrow 10:00-13:00 IST: always in the future and on quarter-hour boundaries.
        LocalDate day = LocalDate.now(IST).plusDays(2);
        start = day.atTime(10, 0).atZone(IST).toInstant();
        end = day.atTime(13, 0).atZone(IST).toInstant();
    }

    private ResultActions quote(Long id, Instant s, Instant e, String extra) throws Exception {
        return mvc.perform(get("/api/v1/listings/" + id + "/quote?start=" + s + "&end=" + e + extra));
    }

    @Test
    void approvedListingIsPublicWithoutAuthAndHidesInternals() throws Exception {
        mvc.perform(get("/api/v1/listings/" + approvedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(approvedId))
                .andExpect(jsonPath("$.title").value("Test Spot"))
                .andExpect(jsonPath("$.cityName").value("Pune"))
                .andExpect(jsonPath("$.citySlug").value("pune"))
                .andExpect(jsonPath("$.stateSlug").value("maharashtra"))
                .andExpect(jsonPath("$.slotSummary.fourWheeler").value(1))
                .andExpect(jsonPath("$.slotSummary.twoWheeler").value(0))
                .andExpect(jsonPath("$.slotSummary.medium").value(1))
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].url").isNotEmpty())
                .andExpect(jsonPath("$.photos[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.amenities[0]").value("CCTV"))
                .andExpect(jsonPath("$.pricePerHour").value(30))
                .andExpect(jsonPath("$.open24x7").value(true))
                .andExpect(jsonPath("$.ownerFirstName").value("Ravi"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.approvedAt").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.ownerEmail").doesNotExist());
    }

    @Test
    void draftOrUnknownListingIs404() throws Exception {
        mvc.perform(get("/api/v1/listings/" + draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/listings/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/listings/" + draftId + "/quote?start=" + start + "&end=" + end))
                .andExpect(status().isNotFound());
    }

    @Test
    void quoteForFreeWindow() throws Exception {
        quote(approvedId, start, end, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.freeSlots").value(1))
                .andExpect(jsonPath("$.totalSlots").value(1))
                .andExpect(jsonPath("$.quote.baseAmount").value(90.00))
                .andExpect(jsonPath("$.quote.platformFee").value(9.00))
                .andExpect(jsonPath("$.quote.gstAmount").value(1.62))
                .andExpect(jsonPath("$.quote.totalAmount").value(100.62))
                .andExpect(jsonPath("$.quote.pricingMode").value("HOURLY"))
                .andExpect(jsonPath("$.quote.durationMinutes").value(180));
    }

    @Test
    void quoteForVehicleTypeWithoutSlots() throws Exception {
        quote(approvedId, start, end, "&vehicleType=TWO_WHEELER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("NO_VEHICLE_SLOTS"))
                .andExpect(jsonPath("$.quote.totalAmount").value(100.62));
    }

    @Test
    void quoteWithOverlappingWholeListingBlock() throws Exception {
        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listings.getReferenceById(approvedId));
        block.setStartTime(start.plus(1, ChronoUnit.HOURS));
        block.setEndTime(end.plus(1, ChronoUnit.HOURS));
        blocks.saveAndFlush(block);
        quote(approvedId, start, end, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("BLOCKED"))
                .andExpect(jsonPath("$.freeSlots").value(0));
    }

    @Test
    void nonOverlappingBlockDoesNotAffectQuote() throws Exception {
        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listings.getReferenceById(approvedId));
        block.setStartTime(end);
        block.setEndTime(end.plus(2, ChronoUnit.HOURS));
        blocks.saveAndFlush(block);
        quote(approvedId, start, end, "").andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void shortWindowIsRejected() throws Exception {
        quote(approvedId, start, start.plus(30, ChronoUnit.MINUTES), "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void missingStartOrEndIsRejected() throws Exception {
        mvc.perform(get("/api/v1/listings/" + approvedId + "/quote?start=" + start))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        mvc.perform(get("/api/v1/listings/" + approvedId + "/quote"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void liveBookingOnTheOnlySlotMakesQuoteFullyBooked() throws Exception {
        var driver = users.save(TestUsers.newUser("quote-driver@example.com", Role.DRIVER));
        var slot = slots.findByListingIdOrderByLabelAsc(approvedId).get(0);
        bookings.saveAndFlush(BookingTestSupport.booking(driver, listings.getReferenceById(approvedId), slot,
                BookingStatus.CONFIRMED, start.plus(1, ChronoUnit.HOURS), end.plus(1, ChronoUnit.HOURS)));
        quote(approvedId, start, end, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("FULLY_BOOKED"))
                .andExpect(jsonPath("$.freeSlots").value(0))
                .andExpect(jsonPath("$.quote.totalAmount").value(100.62));
        // A touching window is unaffected.
        quote(approvedId, end.plus(1, ChronoUnit.HOURS), end.plus(3, ChronoUnit.HOURS), "")
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void expiredHoldDoesNotBlockQuote() throws Exception {
        var driver = users.save(TestUsers.newUser("quote-driver2@example.com", Role.DRIVER));
        var slot = slots.findByListingIdOrderByLabelAsc(approvedId).get(0);
        var hold = BookingTestSupport.booking(driver, listings.getReferenceById(approvedId), slot,
                BookingStatus.PENDING_PAYMENT, start, end);
        hold.setHoldExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        bookings.saveAndFlush(hold);
        quote(approvedId, start, end, "").andExpect(jsonPath("$.available").value(true));
    }
}
