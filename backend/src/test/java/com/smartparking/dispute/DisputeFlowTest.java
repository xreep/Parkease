package com.smartparking.dispute;

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
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.user.UserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class DisputeFlowTest {

    static final String DESCRIPTION = "The gate was locked and nobody answered the phone.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RecordingEmailSender emails;

    String admin;
    String ownerAuth;
    String otherOwnerAuth;
    Driver driver;
    Driver other;
    Long listingId;
    long bookingId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        emails.clear();
        admin = adminAuth(mvc, users, encoder, "dp-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "dp-owner@example.com", "OWNER")));
        otherOwnerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "dp-owner2@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Dispute Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "dp-driver@example.com");
        other = driverWithVehicle(mvc, "dp-driver2@example.com");
        Instant start = tomorrowAt(10);
        bookingId = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), bookingId); // CONFIRMED, 67.08 paid: base 60.00, fee 6.00, GST 1.08
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private void setBooking(String status, Instant end) {
        jdbc.update("update bookings set status = ?, start_time = ?, end_time = ? where id = ?", status,
                Timestamp.from(end.minusSeconds(7200)), Timestamp.from(end), bookingId);
    }

    private ResultActions raise(Driver who, long booking, String json) throws Exception {
        return mvc.perform(post("/api/v1/bookings/" + booking + "/disputes").header(HttpHeaders.AUTHORIZATION, who.auth())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String category, String description) {
        return "{\"category\":\"" + category + "\",\"description\":\"" + description + "\"}";
    }

    private long raiseOk() throws Exception {
        String json = raise(driver, bookingId, body("NO_ACCESS", DESCRIPTION)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private ResultActions getAs(String auth, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions postAs(String auth, String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    private ResultActions respond(String auth, long id, String text) throws Exception {
        return postAs(auth, "/api/v1/owner/disputes/" + id + "/respond", "{\"response\":\"" + text + "\"}");
    }

    private ResultActions resolve(long id, String json) throws Exception {
        return postAs(admin, "/api/v1/admin/disputes/" + id + "/resolve", json);
    }

    private List<String> notificationsOf(String email) {
        return jdbc.queryForList("select n.type from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? order by n.id", String.class, email);
    }

    private Map<String, Object> payment() {
        return jdbc.queryForMap("select status from payments where booking_id = ?", bookingId);
    }

    private Map<String, Object> bookingRow() {
        return jdbc.queryForMap("select refund_amount, status from bookings where id = ?", bookingId);
    }

    private Map<String, Object> earning() {
        return jdbc.queryForMap("select net, status from owner_earnings where booking_id = ?", bookingId);
    }

    // ---- raising --------------------------------------------------------------------------------------------

    @Test
    void aDriverRaisesADisputeAndTheOwnerIsTold() throws Exception {
        raise(driver, bookingId, body("NO_ACCESS", DESCRIPTION)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.bookingCode").exists())
                .andExpect(jsonPath("$.listingTitle").value("Dispute Spot"))
                .andExpect(jsonPath("$.category").value("NO_ACCESS"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.description").value(DESCRIPTION))
                .andExpect(jsonPath("$.raisedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.ownerResponse").value(nullValue()))
                .andExpect(jsonPath("$.resolution").value(nullValue()))
                .andExpect(jsonPath("$.adminNotes").value(nullValue()))
                .andExpect(jsonPath("$.refundableRemaining").value(nullValue()))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(notificationsOf("dp-owner@example.com")).contains("DISPUTE_OPENED");
        assertThat(emails.sentTo("dp-owner@example.com")).anyMatch(m -> m.subject().toLowerCase().contains("dispute"));
        getAs(admin, "/api/v1/admin/disputes").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void confirmedActiveAndRecentlyCompletedBookingsAreDisputable() throws Exception {
        raiseOk(); // CONFIRMED
        jdbc.update("update disputes set status = 'RESOLVED'");
        setBooking("ACTIVE", Instant.now().plus(Duration.ofHours(1)));
        raise(driver, bookingId, body("OVERSTAY", DESCRIPTION)).andExpect(status().isCreated());
        jdbc.update("update disputes set status = 'RESOLVED'");
        setBooking("COMPLETED", Instant.now().minus(Duration.ofDays(7)).plus(Duration.ofMinutes(5)));
        raise(driver, bookingId, body("DAMAGE", DESCRIPTION)).andExpect(status().isCreated());
    }

    @Test
    void notAllowedAfterTheSevenDayWindowOrInOtherStatuses() throws Exception {
        setBooking("COMPLETED", Instant.now().minus(Duration.ofDays(7)).minus(Duration.ofMinutes(5)));
        raise(driver, bookingId, body("OTHER", DESCRIPTION)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_ALLOWED"));
        for (String s : new String[] {"PENDING_PAYMENT", "AWAITING_APPROVAL", "CANCELLED", "REJECTED", "EXPIRED"}) {
            setBooking(s, Instant.now().plus(Duration.ofDays(1)));
            raise(driver, bookingId, body("OTHER", DESCRIPTION)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DISPUTE_NOT_ALLOWED"));
        }
        assertThat(jdbc.queryForObject("select count(*) from disputes", Integer.class)).isZero();
    }

    @Test
    void onlyOneUnresolvedDisputePerBooking() throws Exception {
        long first = raiseOk();
        raise(driver, bookingId, body("PAYMENT", DESCRIPTION)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN"));
        postAs(admin, "/api/v1/admin/disputes/" + first + "/review", null).andExpect(status().isOk());
        raise(driver, bookingId, body("PAYMENT", DESCRIPTION)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN")); // UNDER_REVIEW still counts
        resolve(first, "{\"resolution\":\"NO_REFUND\",\"notes\":\"Checked, fine\"}").andExpect(status().isOk());
        raise(driver, bookingId, body("PAYMENT", DESCRIPTION)).andExpect(status().isCreated());
    }

    @Test
    void validationAndAuthorisationOfRaising() throws Exception {
        raise(driver, bookingId, body("NO_ACCESS", "too short")).andExpect(status().isBadRequest());
        raise(driver, bookingId, body("NO_ACCESS", "x".repeat(2001))).andExpect(status().isBadRequest());
        raise(driver, bookingId, body("BOGUS", DESCRIPTION)).andExpect(status().isBadRequest());
        raise(driver, bookingId, "{\"description\":\"" + DESCRIPTION + "\"}").andExpect(status().isBadRequest());
        raise(other, bookingId, body("NO_ACCESS", DESCRIPTION)).andExpect(status().isNotFound());
        raise(driver, 999999L, body("NO_ACCESS", DESCRIPTION)).andExpect(status().isNotFound());
        raise(new Driver(ownerAuth, null), bookingId, body("NO_ACCESS", DESCRIPTION)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/disputes").contentType(MediaType.APPLICATION_JSON)
                .content(body("NO_ACCESS", DESCRIPTION))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from disputes", Integer.class)).isZero();
    }

    @Test
    void theBookingDetailShowsDisputesAndWhetherAnotherCanBeRaised() throws Exception {
        getAs(driver.auth(), "/api/v1/bookings/" + bookingId).andExpect(jsonPath("$.disputable").value(true))
                .andExpect(jsonPath("$.disputes", hasSize(0)));
        long id = raiseOk();
        getAs(driver.auth(), "/api/v1/bookings/" + bookingId).andExpect(jsonPath("$.disputable").value(false))
                .andExpect(jsonPath("$.disputes", hasSize(1)))
                .andExpect(jsonPath("$.disputes[0].id").value(id))
                .andExpect(jsonPath("$.disputes[0].category").value("NO_ACCESS"))
                .andExpect(jsonPath("$.disputes[0].status").value("OPEN"))
                .andExpect(jsonPath("$.disputes[0].bookingCode").exists());
        jdbc.update("update disputes set status = 'RESOLVED', resolved_at = now()");
        getAs(driver.auth(), "/api/v1/bookings/" + bookingId).andExpect(jsonPath("$.disputable").value(true));
        setBooking("CANCELLED", Instant.now().plus(Duration.ofDays(1)));
        getAs(driver.auth(), "/api/v1/bookings/" + bookingId).andExpect(jsonPath("$.disputable").value(false));
    }

    @Test
    void driversListAndReadTheirOwnDisputesOnly() throws Exception {
        long id = raiseOk();
        getAs(driver.auth(), "/api/v1/disputes").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].listingTitle").value("Dispute Spot"));
        getAs(driver.auth(), "/api/v1/disputes/" + id).andExpect(jsonPath("$.description").value(DESCRIPTION));
        getAs(other.auth(), "/api/v1/disputes").andExpect(jsonPath("$.totalElements").value(0));
        getAs(other.auth(), "/api/v1/disputes/" + id).andExpect(status().isNotFound());
        getAs(ownerAuth, "/api/v1/disputes").andExpect(status().isForbidden());
    }

    // ---- owner ------------------------------------------------------------------------------------------------

    @Test
    void theOwnerSeesAndRespondsOnceAndTheDriverIsTold() throws Exception {
        long id = raiseOk();
        getAs(ownerAuth, "/api/v1/owner/disputes").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        getAs(ownerAuth, "/api/v1/owner/disputes?status=RESOLVED").andExpect(jsonPath("$.totalElements").value(0));
        getAs(ownerAuth, "/api/v1/owner/disputes/" + id).andExpect(jsonPath("$.description").value(DESCRIPTION))
                .andExpect(jsonPath("$.raisedByName").value("Ravi K."))
                .andExpect(jsonPath("$.adminNotes").value(nullValue()));

        respond(ownerAuth, id, "I was on site; the gate was open.").andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerResponse").value("I was on site; the gate was open."))
                .andExpect(jsonPath("$.ownerRespondedAt").exists());
        assertThat(notificationsOf("dp-driver@example.com")).contains("DISPUTE_RESPONSE");
        assertThat(emails.sentTo("dp-driver@example.com")).isNotEmpty();
        respond(ownerAuth, id, "Another try").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESPONDED"));
        getAs(driver.auth(), "/api/v1/disputes/" + id)
                .andExpect(jsonPath("$.ownerResponse").value("I was on site; the gate was open."));
    }

    @Test
    void ownerRulesAndAuthorisation() throws Exception {
        long id = raiseOk();
        respond(otherOwnerAuth, id, "Not mine").andExpect(status().isNotFound());
        getAs(otherOwnerAuth, "/api/v1/owner/disputes/" + id).andExpect(status().isNotFound());
        getAs(otherOwnerAuth, "/api/v1/owner/disputes").andExpect(jsonPath("$.totalElements").value(0));
        respond(ownerAuth, id, "").andExpect(status().isBadRequest());
        respond(ownerAuth, id, "x".repeat(1001)).andExpect(status().isBadRequest());
        respond(driver.auth(), id, "I am the driver").andExpect(status().isForbidden());
        postAs(admin, "/api/v1/owner/disputes/" + id + "/respond", "{\"response\":\"hi\"}")
                .andExpect(status().isForbidden());

        resolve(id, "{\"resolution\":\"WARNING\",\"notes\":\"Warned the owner\"}").andExpect(status().isOk());
        respond(ownerAuth, id, "Too late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_ALLOWED"));
    }

    // ---- admin ----------------------------------------------------------------------------------------------

    @Test
    void adminListsFiltersAndReviews() throws Exception {
        long id = raiseOk();
        getAs(admin, "/api/v1/admin/disputes?status=OPEN").andExpect(jsonPath("$.totalElements").value(1));
        getAs(admin, "/api/v1/admin/disputes?status=RESOLVED").andExpect(jsonPath("$.totalElements").value(0));
        getAs(admin, "/api/v1/admin/disputes/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundableRemaining").value(67.08))
                .andExpect(jsonPath("$.adminNotes").value(nullValue()))
                .andExpect(jsonPath("$.raisedByName").value("Ravi Kumar"));

        postAs(admin, "/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        postAs(admin, "/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        getAs(admin, "/api/v1/admin/disputes?status=UNDER_REVIEW").andExpect(jsonPath("$.totalElements").value(1));
        postAs(admin, "/api/v1/admin/disputes/999999/review", null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("select action from admin_actions", String.class))
                .containsExactly("DISPUTE_UNDER_REVIEW");
    }

    @Test
    void adminEndpointsAreAdminOnly() throws Exception {
        long id = raiseOk();
        for (String auth : new String[] {driver.auth(), ownerAuth}) {
            getAs(auth, "/api/v1/admin/disputes").andExpect(status().isForbidden());
            getAs(auth, "/api/v1/admin/disputes/" + id).andExpect(status().isForbidden());
            postAs(auth, "/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isForbidden());
            postAs(auth, "/api/v1/admin/disputes/" + id + "/resolve",
                    "{\"resolution\":\"NO_REFUND\",\"notes\":\"x\"}").andExpect(status().isForbidden());
        }
    }

    @Test
    void noRefundAndWarningMoveNoMoney() throws Exception {
        long id = raiseOk();
        resolve(id, "{\"resolution\":\"NO_REFUND\",\"amount\":10,\"notes\":\"  Access worked  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("NO_REFUND"))
                .andExpect(jsonPath("$.resolutionAmount").value(nullValue()))
                .andExpect(jsonPath("$.adminNotes").value("Access worked"))
                .andExpect(jsonPath("$.resolvedAt").exists());
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
        assertThat(payment()).containsEntry("status", "CAPTURED");
        assertThat(((Number) bookingRow().get("refund_amount")).doubleValue()).isZero();
        assertThat(earning()).containsEntry("status", "HELD");

        jdbc.update("update disputes set status = 'RESOLVED'");
        long second = raise(driver, bookingId, body("OTHER", DESCRIPTION)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().length();
        assertThat(second).isPositive();
        long warnId = jdbc.queryForObject("select max(id) from disputes", Long.class);
        resolve(warnId, "{\"resolution\":\"WARNING\",\"notes\":\"Owner warned\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("WARNING"));
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
    }

    @Test
    void fullRefundReturnsEverythingAndReversesTheEarning() throws Exception {
        long id = raiseOk();
        resolve(id, "{\"resolution\":\"REFUND_FULL\",\"notes\":\"Gate really was locked\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("REFUND_FULL"))
                .andExpect(jsonPath("$.resolutionAmount").value(67.08))
                .andExpect(jsonPath("$.refundableRemaining").value(0));

        Map<String, Object> refund = jdbc.queryForMap("select amount, status, reason from refunds");
        assertThat(((Number) refund.get("amount")).doubleValue()).isEqualTo(67.08);
        assertThat(refund).containsEntry("status", "PROCESSED");
        assertThat(payment()).containsEntry("status", "REFUNDED");
        assertThat(((Number) bookingRow().get("refund_amount")).doubleValue()).isEqualTo(67.08);
        assertThat(bookingRow()).containsEntry("status", "CONFIRMED"); // the booking itself is left alone
        assertThat(earning()).containsEntry("status", "REVERSED");
        assertThat(((Number) earning().get("net")).doubleValue()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from booking_events where actor = 'ADMIN'", Integer.class))
                .isPositive();
        assertThat(notificationsOf("dp-driver@example.com")).contains("DISPUTE_RESOLVED");
        assertThat(notificationsOf("dp-owner@example.com")).contains("DISPUTE_RESOLVED");
        assertThat(emails.sentTo("dp-driver@example.com")).isNotEmpty();
        assertThat(emails.sentTo("dp-owner@example.com")).anyMatch(m -> m.subject().toLowerCase().contains("dispute"));
        Map<String, Object> audit = jdbc.queryForMap("select action, target_type, target_id, details from admin_actions");
        assertThat(audit).containsEntry("action", "DISPUTE_RESOLVED").containsEntry("target_type", "DISPUTE")
                .containsEntry("target_id", id);
        assertThat((String) audit.get("details")).contains("REFUND_FULL");
    }

    @Test
    void partialRefundIsLimitedToWhatIsLeftAndFollowsTheEarningRules() throws Exception {
        long id = raiseOk();
        for (String bad : new String[] {"{\"resolution\":\"REFUND_PARTIAL\",\"notes\":\"n\"}",
                "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":0,\"notes\":\"n\"}",
                "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":-5,\"notes\":\"n\"}",
                "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":67.09,\"notes\":\"n\"}",
                "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":10.005,\"notes\":\"n\"}"}) {
            resolve(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOLUTION"));
        }
        assertThat(jdbc.queryForObject("select status from disputes where id = ?", String.class, id)).isEqualTo("OPEN");

        resolve(id, "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":20.00,\"notes\":\"Half the time lost\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionAmount").value(20.0))
                .andExpect(jsonPath("$.refundableRemaining").value(47.08));
        assertThat(payment()).containsEntry("status", "PARTIALLY_REFUNDED");
        assertThat(((Number) bookingRow().get("refund_amount")).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) earning().get("net")).doubleValue()).isEqualTo(40.0); // gross 60 less refunded base 20
        assertThat(earning()).containsEntry("status", "HELD");
    }

    @Test
    void fullRefundIsTheRemainingAfterEarlierRefundsAndRefusedWhenNothingIsLeft() throws Exception {
        long first = raiseOk();
        resolve(first, "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":20,\"notes\":\"n\"}").andExpect(status().isOk());
        raise(driver, bookingId, body("PAYMENT", DESCRIPTION)).andExpect(status().isCreated());
        long second = jdbc.queryForObject("select max(id) from disputes", Long.class);
        resolve(second, "{\"resolution\":\"REFUND_FULL\",\"notes\":\"Rest\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionAmount").value(47.08));
        assertThat(payment()).containsEntry("status", "REFUNDED");
        assertThat(earning()).containsEntry("status", "REVERSED");

        raise(driver, bookingId, body("PAYMENT", DESCRIPTION)).andExpect(status().isCreated());
        long third = jdbc.queryForObject("select max(id) from disputes", Long.class);
        resolve(third, "{\"resolution\":\"REFUND_FULL\",\"notes\":\"Again\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESOLUTION"));
        resolve(third, "{\"resolution\":\"REFUND_PARTIAL\",\"amount\":1,\"notes\":\"Again\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOLUTION"));
        resolve(third, "{\"resolution\":\"NO_REFUND\",\"notes\":\"Nothing left\"}").andExpect(status().isOk());
    }

    @Test
    void resolveValidationAndImmutability() throws Exception {
        long id = raiseOk();
        resolve(id, "{\"resolution\":\"NO_REFUND\"}").andExpect(status().isBadRequest());
        resolve(id, "{\"resolution\":\"NO_REFUND\",\"notes\":\"  \"}").andExpect(status().isBadRequest());
        resolve(id, "{\"resolution\":\"NO_REFUND\",\"notes\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());
        resolve(id, "{\"resolution\":\"BOGUS\",\"notes\":\"n\"}").andExpect(status().isBadRequest());
        resolve(id, "{\"notes\":\"n\"}").andExpect(status().isBadRequest());
        resolve(999999L, "{\"resolution\":\"NO_REFUND\",\"notes\":\"n\"}").andExpect(status().isNotFound());

        resolve(id, "{\"resolution\":\"NO_REFUND\",\"notes\":\"Done\"}").andExpect(status().isOk());
        resolve(id, "{\"resolution\":\"REFUND_FULL\",\"notes\":\"Changed my mind\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        postAs(admin, "/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isEqualTo(1);
    }

    @Test
    void aResolutionCanBeMadeStraightFromOpenOrAfterReview() throws Exception {
        long id = raiseOk();
        postAs(admin, "/api/v1/admin/disputes/" + id + "/review", null).andExpect(status().isOk());
        resolve(id, "{\"resolution\":\"WARNING\",\"notes\":\"n\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForList("select action from admin_actions order by id", String.class))
                .containsExactly("DISPUTE_UNDER_REVIEW", "DISPUTE_RESOLVED");
    }
}
