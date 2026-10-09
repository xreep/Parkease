package com.smartparking.admin.reviews;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.user.UserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class AdminReviewModerationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    String admin;
    String ownerAuth;
    Long listingId;
    Driver driver1;
    Driver driver2;
    long review5;
    long review2;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        admin = adminAuth(mvc, users, encoder, "rm-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "rm-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Review Spot", 18.5204, 73.8567, 30);
        driver1 = driverWithVehicle(mvc, "rm-driver1@example.com");
        driver2 = driverWithVehicle(mvc, "rm-driver2@example.com");
        review5 = review(driver1, 10, 5, "Great spot, easy access");
        review2 = review(driver2, 14, 2, "Gate was locked");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private long review(Driver who, int startHour, int rating, String comment) throws Exception {
        Instant start = tomorrowAt(startHour);
        long id = bookingId(reserveOk(mvc, who.auth(), listingId, who.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, who.auth(), id);
        Instant end = Instant.now().minus(Duration.ofHours(1));
        jdbc.update("update bookings set status = 'COMPLETED', start_time = ?, end_time = ?, completed_at = ? "
                + "where id = ?", Timestamp.from(end.minusSeconds(7200)), Timestamp.from(end), Timestamp.from(end), id);
        String body = mvc.perform(post("/api/v1/bookings/" + id + "/review").header(HttpHeaders.AUTHORIZATION, who.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + ",\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions hide(long id, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/reviews/" + id + "/hide").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions unhide(long id) throws Exception {
        return mvc.perform(post("/api/v1/admin/reviews/" + id + "/unhide").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private Map<String, Object> listingRow() {
        return jdbc.queryForMap("select avg_rating, review_count from parking_listings where id = ?", listingId);
    }

    @Test
    void listsReviewsWithListingAndHiddenInfoAndFilters() throws Exception {
        hide(review2, "{\"reason\":\"Offensive\"}").andExpect(status().isOk());

        adminGet("/api/v1/admin/reviews").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(review2)) // newest first
                .andExpect(jsonPath("$.content[0].rating").value(2))
                .andExpect(jsonPath("$.content[0].comment").value("Gate was locked"))
                .andExpect(jsonPath("$.content[0].authorName").value("Ravi K."))
                .andExpect(jsonPath("$.content[0].listingId").value(listingId))
                .andExpect(jsonPath("$.content[0].listingTitle").value("Review Spot"))
                .andExpect(jsonPath("$.content[0].bookingCode").exists())
                .andExpect(jsonPath("$.content[0].hidden").value(true))
                .andExpect(jsonPath("$.content[0].hiddenReason").value("Offensive"))
                .andExpect(jsonPath("$.content[1].hidden").value(false))
                .andExpect(jsonPath("$.content[1].hiddenReason").value(org.hamcrest.Matchers.nullValue()));
        adminGet("/api/v1/admin/reviews?hidden=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(review2));
        adminGet("/api/v1/admin/reviews?hidden=false").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(review5));
        adminGet("/api/v1/admin/reviews?q=GATE").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(review2));
        mvc.perform(get("/api/v1/admin/reviews").param("q", "review spot").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/admin/reviews").param("q", "%").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/reviews?q=nothing-like-this").andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/reviews?size=1&page=1").andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void hidingRecomputesTheAggregatesAndLeavesThePublicList() throws Exception {
        assertThat(listingRow()).containsEntry("review_count", 2);
        hide(review2, "{\"reason\":\"  Offensive language  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(review2))
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.hiddenReason").value("Offensive language"));

        assertThat(listingRow()).containsEntry("review_count", 1);
        assertThat(((Number) listingRow().get("avg_rating")).doubleValue()).isEqualTo(5.0);
        assertThat(jdbc.queryForObject("select hidden_at is not null from reviews where id = ?", Boolean.class,
                review2)).isTrue();
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews"))
                .andExpect(jsonPath("$.summary.reviewCount").value(1))
                .andExpect(jsonPath("$.summary.avgRating").value(5.0))
                .andExpect(jsonPath("$.summary.distribution.2").value(0))
                .andExpect(jsonPath("$.reviews.totalElements").value(1))
                .andExpect(jsonPath("$.reviews.content[0].id").value(review5));
        mvc.perform(get("/api/v1/listings/" + listingId)).andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.avgRating").value(5.0));
        mvc.perform(get("/api/v1/owner/stats").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(jsonPath("$.totals.reviewCount").value(1))
                .andExpect(jsonPath("$.totals.avgRating").value(5.0));
        assertThat(jdbc.queryForMap("select action, target_type, target_id from admin_actions"))
                .containsEntry("action", "REVIEW_HIDDEN").containsEntry("target_type", "REVIEW")
                .containsEntry("target_id", review2);
    }

    @Test
    void theOwnerStillSeesAHiddenReviewFlaggedAsHidden() throws Exception {
        hide(review2, "{\"reason\":\"Offensive\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/owner/reviews").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(review2))
                .andExpect(jsonPath("$.content[0].hidden").value(true))
                .andExpect(jsonPath("$.content[1].hidden").value(false));
    }

    @Test
    void unhidingRestoresTheAggregates() throws Exception {
        hide(review2, "{\"reason\":\"Offensive\"}").andExpect(status().isOk());
        unhide(review2).andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(false))
                .andExpect(jsonPath("$.hiddenReason").value(org.hamcrest.Matchers.nullValue()));

        assertThat(listingRow()).containsEntry("review_count", 2);
        assertThat(((Number) listingRow().get("avg_rating")).doubleValue()).isEqualTo(3.5);
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews")).andExpect(jsonPath("$.reviews.totalElements").value(2));
        assertThat(jdbc.queryForList("select action from admin_actions order by id", String.class))
                .containsExactly("REVIEW_HIDDEN", "REVIEW_UNHIDDEN");
    }

    @Test
    void validationAndStateRules() throws Exception {
        hide(review2, "{}").andExpect(status().isBadRequest());
        hide(review2, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        hide(review2, "{\"reason\":\"" + "x".repeat(301) + "\"}").andExpect(status().isBadRequest());
        hide(999999L, "{\"reason\":\"No\"}").andExpect(status().isNotFound());
        unhide(999999L).andExpect(status().isNotFound());
        unhide(review2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        hide(review2, "{\"reason\":\"Offensive\"}").andExpect(status().isOk());
        hide(review2, "{\"reason\":\"Offensive\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyAdminsMayModerateReviews() throws Exception {
        mvc.perform(get("/api/v1/admin/reviews").header(HttpHeaders.AUTHORIZATION, driver1.auth()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reviews/" + review2 + "/hide").header(HttpHeaders.AUTHORIZATION, ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reviews/" + review2 + "/unhide").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/reviews")).andExpect(status().isUnauthorized());
    }
}
