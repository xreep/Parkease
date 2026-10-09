package com.smartparking.admin;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.reserve;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AdminListingModerationTest {

    static final double LAT = 18.5204;
    static final double LNG = 73.8567;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired EntityManager em;

    String admin;
    String ownerAuth;
    Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminAuth(mvc, users, encoder, "lm-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "lm-owner@example.com", "OWNER")));
        listingId = approvedListingAt(mvc, ownerAuth, listings, puneCityId(cities), "Moderated Spot", LAT, LNG, 30);
    }

    private ResultActions suspend(Long id, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/listings/" + id + "/suspend").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions reinstate(Long id) throws Exception {
        return mvc.perform(post("/api/v1/admin/listings/" + id + "/reinstate").header(HttpHeaders.AUTHORIZATION, admin));
    }

    /** Writes the listing status straight to the database and forgets cached entities so the app re-reads it. */
    private void setStatus(Long id, String status) {
        em.flush();
        jdbc.update("update parking_listings set status = ? where id = ?", status, id);
        em.clear();
    }

    private String statusOf(Long id) {
        em.flush();
        return jdbc.queryForObject("select status from parking_listings where id = ?", String.class, id);
    }

    private String ownerNotifications() {
        return String.join(",", jdbc.queryForList(
                "select n.type || '|' || n.body || '|' || coalesce(n.link, '') from notifications n "
                        + "join users u on u.id = n.user_id where u.email = 'lm-owner@example.com' order by n.id",
                String.class));
    }

    @Test
    void suspendingAnApprovedListingHidesItAndNotifiesTheOwner() throws Exception {
        suspend(listingId, "{\"reason\":\"  Misleading photos  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.id").value(listingId))
                .andExpect(jsonPath("$.listing.status").value("SUSPENDED"));
        assertThat(statusOf(listingId)).isEqualTo("SUSPENDED");
        assertThat(ownerNotifications()).contains("LISTING_SUSPENDED|").contains("Misleading photos")
                .contains("/owner/listings");
        assertThat(jdbc.queryForMap("select action, target_type, target_id, details from admin_actions"))
                .containsEntry("action", "LISTING_SUSPENDED").containsEntry("target_type", "LISTING")
                .containsEntry("target_id", listingId);

        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/search?lat=" + LAT + "&lng=" + LNG))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(listingId.intValue()))));
    }

    @Test
    void aPausedListingCanBeSuspendedToo() throws Exception {
        setStatus(listingId, "PAUSED");
        suspend(listingId, "{\"reason\":\"Complaints\"}").andExpect(status().isOk());
        assertThat(statusOf(listingId)).isEqualTo("SUSPENDED");
    }

    @Test
    void onlyApprovedOrPausedListingsCanBeSuspended() throws Exception {
        for (String s : new String[] {"DRAFT", "PENDING_REVIEW", "REJECTED", "SUSPENDED"}) {
            setStatus(listingId, s);
            suspend(listingId, "{\"reason\":\"No\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
            assertThat(statusOf(listingId)).isEqualTo(s);
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    @Test
    void suspendRequiresAReasonAndAnExistingListing() throws Exception {
        suspend(listingId, "{}").andExpect(status().isBadRequest());
        suspend(listingId, "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        suspend(listingId, "{\"reason\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
        suspend(999999L, "{\"reason\":\"No\"}").andExpect(status().isNotFound());
        assertThat(statusOf(listingId)).isEqualTo("APPROVED");
    }

    @Test
    void reinstatingASuspendedListingMakesItLiveAgain() throws Exception {
        suspend(listingId, "{\"reason\":\"Complaints\"}").andExpect(status().isOk());
        reinstate(listingId).andExpect(status().isOk()).andExpect(jsonPath("$.listing.status").value("APPROVED"));
        assertThat(statusOf(listingId)).isEqualTo("APPROVED");
        assertThat(ownerNotifications()).contains("LISTING_SUSPENDED|").contains("LISTING_REINSTATED|");
        assertThat(jdbc.queryForList("select action from admin_actions order by id", String.class))
                .containsExactly("LISTING_SUSPENDED", "LISTING_REINSTATED");
        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(status().isOk());
    }

    @Test
    void onlySuspendedListingsCanBeReinstated() throws Exception {
        reinstate(listingId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        reinstate(999999L).andExpect(status().isNotFound());
    }

    @Test
    void aSuspendedListingTakesNoNewReservations() throws Exception {
        Driver driver = driverWithVehicle(mvc, "lm-driver@example.com");
        suspend(listingId, "{\"reason\":\"Complaints\"}").andExpect(status().isOk());
        Instant start = tomorrowAt(10);
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_UNAVAILABLE"));
    }

    @Test
    void onlyAdminsMayModerateListings() throws Exception {
        for (String auth : new String[] {ownerAuth}) {
            mvc.perform(post("/api/v1/admin/listings/" + listingId + "/suspend").header(HttpHeaders.AUTHORIZATION, auth)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/listings/" + listingId + "/reinstate").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
        }
        assertThat(statusOf(listingId)).isEqualTo("APPROVED");
    }
}
