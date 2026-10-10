package com.smartparking.admin.payouts;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import java.time.Instant;
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

/** Earnings of bookings with an unresolved dispute are held back from payouts until the dispute is settled. */
@CommittedIntegrationTest
class PayoutDisputeHoldTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    String admin;
    String ownerAuth;
    Long ownerId;
    Long listingId;
    Driver driver;
    int hour = 6;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        admin = adminAuth(mvc, users, encoder, "pd-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "pd-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Hold Spot", 18.5204, 73.8567, 30);
        ownerId = users.findByEmail("pd-owner@example.com").orElseThrow().getId();
        driver = driverWithVehicle(mvc, "pd-driver@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    /** A paid booking whose earning (net 60.00) is pending payout; returns the booking id. */
    private long pendingBooking() throws Exception {
        Instant start = tomorrowAt(hour);
        hour += 3;
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), id);
        jdbc.update("update owner_earnings set status = 'PENDING_PAYOUT' where booking_id = ?", id);
        return id;
    }

    private long earningId(long booking) {
        return jdbc.queryForObject("select id from owner_earnings where booking_id = ?", Long.class, booking);
    }

    private long dispute(long booking) throws Exception {
        String json = mvc.perform(post("/api/v1/bookings/" + booking + "/disputes")
                        .header(HttpHeaders.AUTHORIZATION, driver.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"NO_ACCESS\",\"description\":\"The gate was locked all evening.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions adminPost(String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                .content(json == null ? "" : json));
    }

    private ResultActions markPaid(Long... ids) throws Exception {
        return adminPost("/api/v1/admin/payouts/mark-paid", "{\"ownerId\":" + ownerId + ",\"earningIds\":"
                + java.util.Arrays.toString(ids) + ",\"reference\":\"UTR-77\"}");
    }

    private String earningStatus(long booking) {
        return jdbc.queryForObject("select status from owner_earnings where booking_id = ?", String.class, booking);
    }

    @Test
    void anOpenDisputeHoldsTheEarningUntilItIsResolvedAndTheRemainderCanThenBePaid() throws Exception {
        long clean = pendingBooking();
        long disputed = pendingBooking();
        long disputeId = dispute(disputed);

        // The payable total leaves the disputed earning out and reports it as held back.
        adminGet("/api/v1/admin/payouts").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].pendingAmount").value(60.0))
                .andExpect(jsonPath("$[0].earningsCount").value(1))
                .andExpect(jsonPath("$[0].disputedAmount").value(60.0));
        adminGet("/api/v1/admin/payouts/" + ownerId + "/earnings").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].bookingId").value(clean))
                .andExpect(jsonPath("$[0].disputed").value(false))
                .andExpect(jsonPath("$[0].net").value(60.0))
                .andExpect(jsonPath("$[1].bookingId").value(disputed))
                .andExpect(jsonPath("$[1].disputed").value(true));

        // All or nothing: one disputed earning in the selection refuses the whole payout.
        markPaid(earningId(clean), earningId(disputed)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EARNING_DISPUTED"));
        assertThat(earningStatus(clean)).isEqualTo("PENDING_PAYOUT");
        assertThat(earningStatus(disputed)).isEqualTo("PENDING_PAYOUT");
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();

        // The resolve screen can see where the earning stands.
        adminGet("/api/v1/admin/disputes/" + disputeId).andExpect(jsonPath("$.earningStatus").value("PENDING_PAYOUT"))
                .andExpect(jsonPath("$.earningNet").value(60.0));
        mvc.perform(get("/api/v1/disputes/" + disputeId).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(jsonPath("$.earningStatus").value(nullValue()))
                .andExpect(jsonPath("$.earningNet").value(nullValue()));
        mvc.perform(get("/api/v1/owner/disputes/" + disputeId).header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(jsonPath("$.earningStatus").value(nullValue()));

        // Resolve with a partial refund: the earning follows the refund and the hold is lifted.
        adminPost("/api/v1/admin/disputes/" + disputeId + "/resolve",
                "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":20,\"notes\":\"Gate was locked\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.earningNet").value(40.0))
                .andExpect(jsonPath("$.earningStatus").value("PENDING_PAYOUT"));
        assertThat(jdbc.queryForObject("select net from owner_earnings where booking_id = ?", java.math.BigDecimal.class,
                disputed)).isEqualByComparingTo("40.00");
        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$[0].pendingAmount").value(100.0))
                .andExpect(jsonPath("$[0].earningsCount").value(2)).andExpect(jsonPath("$[0].disputedAmount").value(0.0));
        adminGet("/api/v1/admin/payouts/" + ownerId + "/earnings").andExpect(jsonPath("$[1].disputed").value(false));

        // The owner is told what the resolution did to the earning.
        assertThat(jdbc.queryForObject("select n.body from notifications n where n.user_id = ? "
                + "and n.type = 'DISPUTE_RESOLVED'", String.class, ownerId))
                .contains("Refund of ₹20.00 issued to the driver; your earning for this booking is now ₹40.00.");

        markPaid(earningId(clean), earningId(disputed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(2)).andExpect(jsonPath("$.paidAmount").value(100.0));
        assertThat(earningStatus(disputed)).isEqualTo("PAID");
    }

    @Test
    void anUnderReviewDisputeStillHoldsAndAnOwnerWithOnlyHeldMoneyIsNotListed() throws Exception {
        long booking = pendingBooking();
        long id = dispute(booking);
        adminPost("/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isOk());

        adminGet("/api/v1/admin/payouts").andExpect(jsonPath("$", hasSize(0)));
        markPaid(earningId(booking)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EARNING_DISPUTED"));

        adminPost("/api/v1/admin/disputes/" + id + "/resolve", "{\"resolution\":\"NO_REFUND\",\"notes\":\"Fine\"}")
                .andExpect(status().isOk());
        markPaid(earningId(booking)).andExpect(status().isOk());
    }

    @Test
    void theResolveScreenSeesWhenTheEarningWasAlreadyPaidOut() throws Exception {
        long booking = pendingBooking();
        markPaid(earningId(booking)).andExpect(status().isOk());
        long id = dispute(booking);

        adminGet("/api/v1/admin/disputes/" + id).andExpect(jsonPath("$.earningStatus").value("PAID"))
                .andExpect(jsonPath("$.earningNet").value(60.0));
        adminPost("/api/v1/admin/disputes/" + id + "/resolve",
                "{\"resolution\":\"REFUND_FULL\",\"notes\":\"Refund anyway\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.earningStatus").value("PAID"));
        assertThat(jdbc.queryForObject("select n.body from notifications n where n.user_id = ? "
                + "and n.type = 'DISPUTE_RESOLVED'", String.class, ownerId))
                .contains("already paid out");
    }

    @Test
    void thePendingPayoutCsvCarriesTheHeldAmount() throws Exception {
        pendingBooking();
        dispute(pendingBooking());

        String csv = adminGet("/api/v1/admin/payouts?format=csv").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).endsWith("Payout method,Payout details,Held for disputes");
        assertThat(lines[1]).contains(",60.00,1,").endsWith(",60.00");
    }
}
