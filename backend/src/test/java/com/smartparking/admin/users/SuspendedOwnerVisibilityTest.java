package com.smartparking.admin.users;

import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.reserve;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Listings of a suspended owner disappear from every public surface and take no new bookings. */
@IntegrationTest
class SuspendedOwnerVisibilityTest {

    static final double LAT = 18.5204;
    static final double LNG = 73.8567;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired EntityManager em;

    Long listingId;
    Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        String owner = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "so-owner@example.com", "OWNER")));
        listingId = approvedListingAt(mvc, owner, listings, puneCityId(cities), "Owner Spot", LAT, LNG, 30);
        driver = driverWithVehicle(mvc, "so-driver@example.com");
    }

    private void setOwnerStatus(String status) {
        em.flush();
        jdbc.update("update users set status = ? where email = 'so-owner@example.com'", status);
        em.clear();
    }

    private String window() {
        return "start=" + tomorrowAt(10) + "&end=" + tomorrowAt(12);
    }

    @Test
    void visibleWhileTheOwnerIsActive() throws Exception {
        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG))
                .andExpect(jsonPath("$.content[*].id", hasItem(listingId.intValue())));
    }

    @Test
    void hiddenFromSearchDetailAvailabilityAndReviews() throws Exception {
        setOwnerStatus("SUSPENDED");
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(listingId.intValue()))));
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG + "&" + window()))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(listingId.intValue()))));
        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/listings/" + listingId + "/quote?" + window())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews")).andExpect(status().isNotFound());
    }

    @Test
    void takesNoNewReservations() throws Exception {
        setOwnerStatus("SUSPENDED");
        Instant start = tomorrowAt(10);
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_UNAVAILABLE"));
    }

    @Test
    void reappearsWhenTheOwnerIsActivatedAgain() throws Exception {
        setOwnerStatus("SUSPENDED");
        setOwnerStatus("ACTIVE");
        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG))
                .andExpect(jsonPath("$.content[*].id", hasItem(listingId.intValue())));
    }
}
