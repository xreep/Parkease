package com.smartparking.admin.bookings;

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

import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.support.RecordingPaymentProvider;
import com.smartparking.support.RecordingProviderTestConfig;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
@Import(RecordingProviderTestConfig.class)
class AdminBookingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RecordingEmailSender emails;
    @Autowired RecordingPaymentProvider provider;

    String admin;
    String ownerAuth;
    Long puneListing;
    Long mumbaiListing;
    Driver driver;
    Driver other;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        provider.reset();
        emails.clear();
        admin = adminAuth(mvc, users, encoder, "ab-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "ab-owner@example.com", "OWNER")));
        puneListing = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Pune Spot", 18.5204, 73.8567, 30);
        long mumbai = jdbc.queryForObject("select id from cities where slug = 'mumbai'", Long.class);
        mumbaiListing = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Mumbai Spot", 18.5304, 73.8567, 30);
        jdbc.update("update parking_listings set city_id = ? where id = ?", mumbai, mumbaiListing);
        driver = driverWithVehicle(mvc, "ab-driver@example.com");
        other = driverWithVehicle(mvc, "ab-other@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private long hold(Driver who, Long listing, int hour) throws Exception {
        Instant start = tomorrowAt(hour);
        return bookingId(reserveOk(mvc, who.auth(), listing, who.vehicleId(), start, start.plusSeconds(7200)));
    }

    private long paid(Driver who, Long listing, int hour) throws Exception {
        long id = hold(who, listing, hour);
        payOk(mvc, who.auth(), id);
        return id;
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions cancel(long id, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/bookings/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private List<String> notificationsOf(String email) {
        return jdbc.queryForList("select n.type from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? order by n.id", String.class, email);
    }

    private Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    private void setStatus(long id, String status) {
        jdbc.update("update bookings set status = ? where id = ?", status, id);
    }

    // ---- list and detail ----------------------------------------------------------------------------------

    @Test
    void listsSearchesAndFiltersBookings() throws Exception {
        long a = paid(driver, puneListing, 10);
        long b = hold(other, mumbaiListing, 12);
        String codeA = jdbc.queryForObject("select booking_code from bookings where id = ?", String.class, a);

        adminGet("/api/v1/admin/bookings").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(b)) // newest first
                .andExpect(jsonPath("$.content[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("CREATED"))
                .andExpect(jsonPath("$.content[0].cityName").value("Mumbai"))
                .andExpect(jsonPath("$.content[1].id").value(a))
                .andExpect(jsonPath("$.content[1].bookingCode").value(codeA))
                .andExpect(jsonPath("$.content[1].listingTitle").value("Pune Spot"))
                .andExpect(jsonPath("$.content[1].driverName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.content[1].driverEmail").value("ab-driver@example.com"))
                .andExpect(jsonPath("$.content[1].ownerName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.content[1].totalAmount").value(67.08))
                .andExpect(jsonPath("$.content[1].refundAmount").value(0))
                .andExpect(jsonPath("$.content[1].paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.content[1].startTime").exists())
                .andExpect(jsonPath("$.content[1].createdAt").exists());

        adminGet("/api/v1/admin/bookings?status=CONFIRMED").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a));
        adminGet("/api/v1/admin/bookings?q=" + codeA.toLowerCase()).andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/bookings?q=" + codeA.substring(0, 5)).andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/bookings?q=AB-OTHER@").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b));
        adminGet("/api/v1/admin/bookings?q=nobody").andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/admin/bookings").param("q", "%").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        long mumbai = jdbc.queryForObject("select id from cities where slug = 'mumbai'", Long.class);
        adminGet("/api/v1/admin/bookings?cityId=" + mumbai).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b));
        adminGet("/api/v1/admin/bookings?size=1&page=1").andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void filtersByStartDateInIstAndRejectsABackwardsRange() throws Exception {
        long a = paid(driver, puneListing, 10);
        String day = jdbc.queryForObject("select to_char(start_time at time zone 'Asia/Kolkata', 'YYYY-MM-DD') "
                + "from bookings where id = ?", String.class, a);
        String before = java.time.LocalDate.parse(day).minusDays(1).toString();
        String after = java.time.LocalDate.parse(day).plusDays(1).toString();
        adminGet("/api/v1/admin/bookings?from=" + day + "&to=" + day).andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/bookings?from=" + after).andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/bookings?to=" + before).andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/bookings?from=" + after + "&to=" + before).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void detailShowsPaymentRefundsTimelineAndDisputes() throws Exception {
        long id = paid(driver, puneListing, 10);
        cancel(id, "{\"reason\":\"Venue closed\"}").andExpect(status().isOk());
        jdbc.update("insert into disputes (booking_id, raised_by, category, description) "
                + "select ?, driver_id, 'OTHER', 'Something went wrong here' from bookings where id = ?", id, id);

        adminGet("/api/v1/admin/bookings/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.listingId").value(puneListing))
                .andExpect(jsonPath("$.slotLabel").value("A-01"))
                .andExpect(jsonPath("$.baseAmount").value(60.0))
                .andExpect(jsonPath("$.platformFee").value(6.0))
                .andExpect(jsonPath("$.gstAmount").value(1.08))
                .andExpect(jsonPath("$.cancelReason").value("Venue closed"))
                .andExpect(jsonPath("$.cancelledBy").value("ADMIN"))
                .andExpect(jsonPath("$.payment.provider").value("MOCK"))
                .andExpect(jsonPath("$.payment.providerOrderId").exists())
                .andExpect(jsonPath("$.payment.providerPaymentId").exists())
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.amount").value(67.08))
                .andExpect(jsonPath("$.payment.capturedAt").exists())
                .andExpect(jsonPath("$.refunds", hasSize(1)))
                .andExpect(jsonPath("$.refunds[0].amount").value(67.08))
                .andExpect(jsonPath("$.refunds[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$.refunds[0].attempts").value(1))
                .andExpect(jsonPath("$.refunds[0].providerRefundId").exists())
                .andExpect(jsonPath("$.refunds[0].reason").exists())
                .andExpect(jsonPath("$.refunds[0].createdAt").exists())
                .andExpect(jsonPath("$.events[0].toStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.events[*].actor").value(org.hamcrest.Matchers.hasItem("ADMIN")))
                .andExpect(jsonPath("$.disputes", hasSize(1)))
                .andExpect(jsonPath("$.disputes[0].category").value("OTHER"));
        adminGet("/api/v1/admin/bookings/999999").andExpect(status().isNotFound());
    }

    @Test
    void anUnpaidBookingHasNoPayment() throws Exception {
        long id = hold(driver, puneListing, 10);
        jdbc.update("delete from payments where booking_id = ?", id);
        adminGet("/api/v1/admin/bookings/" + id).andExpect(jsonPath("$.payment").value(nullValue()))
                .andExpect(jsonPath("$.refunds", hasSize(0)));
        adminGet("/api/v1/admin/bookings").andExpect(jsonPath("$.content[0].paymentStatus").value(nullValue()));
    }

    // ---- admin cancel -------------------------------------------------------------------------------------

    @Test
    void cancellingAConfirmedBookingRefundsEverythingReversesTheEarningAndTellsBothParties() throws Exception {
        long id = paid(driver, puneListing, 10);
        emails.clear();
        cancel(id, "{\"reason\":\"  Venue closed for repairs  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("ADMIN"))
                .andExpect(jsonPath("$.cancelReason").value("Venue closed for repairs"))
                .andExpect(jsonPath("$.refundAmount").value(67.08))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"));

        assertThat(row("select status, cancelled_by from bookings where id = ?", id))
                .containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "ADMIN");
        assertThat(row("select status, net from owner_earnings where booking_id = ?", id))
                .containsEntry("status", "REVERSED");
        assertThat(row("select count(*) c from refunds where status = 'PROCESSED'")).containsEntry("c", 1L);
        assertThat(row("select actor, from_status, to_status from booking_events where to_status = 'CANCELLED' "
                + "and from_status = 'CONFIRMED'"))
                .containsEntry("actor", "ADMIN").containsEntry("from_status", "CONFIRMED");
        assertThat(provider.calls).hasSize(1);
        assertThat(provider.calls.get(0).paise()).isEqualTo(6708L);
        assertThat(notificationsOf("ab-driver@example.com")).contains("BOOKING_CANCELLED_BY_ADMIN");
        assertThat(notificationsOf("ab-owner@example.com")).contains("BOOKING_CANCELLED_BY_ADMIN");
        assertThat(emails.sentTo("ab-driver@example.com")).anyMatch(m -> m.textBody().contains("Venue closed"));
        assertThat(emails.sentTo("ab-owner@example.com")).isNotEmpty();
        Map<String, Object> audit = row("select action, target_type, target_id, details from admin_actions");
        assertThat(audit).containsEntry("action", "BOOKING_CANCELLED").containsEntry("target_type", "BOOKING")
                .containsEntry("target_id", id);
        assertThat((String) audit.get("details")).contains("Venue closed");
    }

    @Test
    void cancellingAnUnpaidHoldMovesNoMoneyAndSkipsTheOwner() throws Exception {
        long id = hold(driver, puneListing, 10);
        cancel(id, "{\"reason\":\"Test\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(0));
        assertThat(row("select count(*) c from refunds")).containsEntry("c", 0L);
        assertThat(row("select status from payments where booking_id = ?", id)).containsEntry("status", "FAILED");
        assertThat(provider.calls).isEmpty();
        assertThat(notificationsOf("ab-driver@example.com")).contains("BOOKING_CANCELLED_BY_ADMIN");
        assertThat(notificationsOf("ab-owner@example.com")).doesNotContain("BOOKING_CANCELLED_BY_ADMIN");
    }

    @Test
    void cancellingARequestAwaitingApprovalRefundsIt() throws Exception {
        jdbc.update("update parking_listings set auto_approve = false where id = ?", puneListing);
        long id = paid(driver, puneListing, 10);
        assertThat(row("select status from bookings where id = ?", id)).containsEntry("status", "AWAITING_APPROVAL");
        cancel(id, "{\"reason\":\"Test\"}").andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(67.08));
        assertThat(row("select status from payments where booking_id = ?", id)).containsEntry("status", "REFUNDED");
    }

    @Test
    void cancellingAnActiveBookingRefundsIt() throws Exception {
        long id = paid(driver, puneListing, 10);
        setStatus(id, "ACTIVE");
        cancel(id, "{\"reason\":\"Test\"}").andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(67.08));
        assertThat(row("select status from owner_earnings where booking_id = ?", id)).containsEntry("status", "REVERSED");
    }

    @Test
    void onlyTheRemainingMoneyIsRefundedWhenPartOfItAlreadyWas() throws Exception {
        long id = paid(driver, puneListing, 10);
        jdbc.update("insert into refunds (payment_id, amount, status, provider_refund_id, reason) "
                + "select id, 20.00, 'PROCESSED', 'rfnd_old', 'earlier' from payments where booking_id = ?", id);
        jdbc.update("update payments set status = 'PARTIALLY_REFUNDED' where booking_id = ?", id);
        jdbc.update("update bookings set refund_amount = 20.00 where id = ?", id);
        cancel(id, "{\"reason\":\"Test\"}").andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(67.08));
        assertThat(provider.calls.get(0).paise()).isEqualTo(4708L);
        assertThat(row("select status from payments where booking_id = ?", id)).containsEntry("status", "REFUNDED");
    }

    @Test
    void closedBookingsCannotBeCancelled() throws Exception {
        long id = paid(driver, puneListing, 10);
        for (String s : new String[] {"COMPLETED", "CANCELLED", "REJECTED", "EXPIRED"}) {
            setStatus(id, s);
            cancel(id, "{\"reason\":\"Test\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
        }
        assertThat(row("select count(*) c from refunds")).containsEntry("c", 0L);
        assertThat(row("select count(*) c from admin_actions")).containsEntry("c", 0L);
    }

    @Test
    void cancelValidationAndAuthorisation() throws Exception {
        long id = paid(driver, puneListing, 10);
        cancel(id, "{}").andExpect(status().isBadRequest());
        cancel(id, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        cancel(id, "{\"reason\":\"" + "x".repeat(301) + "\"}").andExpect(status().isBadRequest());
        cancel(999999L, "{\"reason\":\"Test\"}").andExpect(status().isNotFound());
        for (String auth : new String[] {driver.auth(), ownerAuth}) {
            mvc.perform(get("/api/v1/admin/bookings").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/bookings/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/bookings/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, auth)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/bookings")).andExpect(status().isUnauthorized());
        assertThat(row("select status from bookings where id = ?", id)).containsEntry("status", "CONFIRMED");
    }
}
