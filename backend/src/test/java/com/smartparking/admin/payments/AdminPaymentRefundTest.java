package com.smartparking.admin.payments;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.smartparking.support.RecordingPaymentProvider;
import com.smartparking.support.RecordingProviderTestConfig;
import com.smartparking.user.UserRepository;
import java.time.Instant;
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
class AdminPaymentRefundTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RecordingPaymentProvider provider;

    String admin;
    String ownerAuth;
    Long listingId;
    Driver driver;
    Driver other;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        provider.reset();
        admin = adminAuth(mvc, users, encoder, "pr2-admin@example.com");
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "pr2-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Pay Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "pr2-driver@example.com");
        other = driverWithVehicle(mvc, "pr2-other@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private long paid(Driver who, int hour) throws Exception {
        Instant start = tomorrowAt(hour);
        long id = bookingId(reserveOk(mvc, who.auth(), listingId, who.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, who.auth(), id);
        return id;
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions retry(long refundId) throws Exception {
        return mvc.perform(post("/api/v1/admin/refunds/" + refundId + "/retry").header(HttpHeaders.AUTHORIZATION, admin));
    }

    /** Cancels the booking while the provider is down, leaving one FAILED refund; returns its id. */
    private long failedRefund(long bookingId) throws Exception {
        provider.failures.set(1);
        mvc.perform(post("/api/v1/admin/bookings/" + bookingId + "/cancel").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Test\"}")).andExpect(status().isOk());
        return jdbc.queryForObject("select id from refunds where status = 'FAILED'", Long.class);
    }

    // ---- payments -----------------------------------------------------------------------------------------

    @Test
    void listsAndSearchesPayments() throws Exception {
        long a = paid(driver, 10);
        long b = paid(other, 12);
        String code = jdbc.queryForObject("select booking_code from bookings where id = ?", String.class, a);
        String order = jdbc.queryForObject("select provider_order_id from payments where booking_id = ?", String.class, b);

        adminGet("/api/v1/admin/payments").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].bookingId").value(b)) // newest first
                .andExpect(jsonPath("$.content[0].providerOrderId").value(order))
                .andExpect(jsonPath("$.content[0].providerPaymentId").exists())
                .andExpect(jsonPath("$.content[0].driverEmail").value("pr2-other@example.com"))
                .andExpect(jsonPath("$.content[0].provider").value("MOCK"))
                .andExpect(jsonPath("$.content[0].status").value("CAPTURED"))
                .andExpect(jsonPath("$.content[0].amount").value(67.08))
                .andExpect(jsonPath("$.content[0].refundAmount").value(0))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].capturedAt").exists());
        adminGet("/api/v1/admin/payments?q=" + code).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(a))
                .andExpect(jsonPath("$.content[0].bookingCode").value(code));
        adminGet("/api/v1/admin/payments?q=" + order.toUpperCase()).andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/payments?q=PR2-DRIVER").andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/payments?status=REFUNDED").andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/payments?status=CAPTURED").andExpect(jsonPath("$.totalElements").value(2));
        adminGet("/api/v1/admin/payments?from=2000-01-01&to=2000-01-02").andExpect(jsonPath("$.totalElements").value(0));
        adminGet("/api/v1/admin/payments?from=2999-01-02&to=2999-01-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        adminGet("/api/v1/admin/payments?size=1").andExpect(jsonPath("$.totalPages").value(2));
    }

    // ---- refunds ------------------------------------------------------------------------------------------

    @Test
    void listsRefundsWithTheirLastError() throws Exception {
        long id = paid(driver, 10);
        long refund = failedRefund(id);
        paid(other, 12);

        adminGet("/api/v1/admin/refunds").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(refund))
                .andExpect(jsonPath("$.content[0].bookingId").value(id))
                .andExpect(jsonPath("$.content[0].bookingCode").exists())
                .andExpect(jsonPath("$.content[0].paymentId").exists())
                .andExpect(jsonPath("$.content[0].amount").value(67.08))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].attempts").value(1))
                .andExpect(jsonPath("$.content[0].providerRefundId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].lastError").isNotEmpty());
        adminGet("/api/v1/admin/refunds?status=FAILED").andExpect(jsonPath("$.totalElements").value(1));
        adminGet("/api/v1/admin/refunds?status=PROCESSED").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void retryingAFailedRefundGetsTheMoneyOut() throws Exception {
        long id = paid(driver, 10);
        long refund = failedRefund(id);

        retry(refund).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(refund))
                .andExpect(jsonPath("$.status").value("PROCESSED")).andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.providerRefundId").exists()).andExpect(jsonPath("$.lastError").value(nullValue()));
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, id))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select refund_amount from bookings where id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("67.08");
        Map<String, Object> audit = jdbc.queryForMap("select action, target_type, target_id, details from admin_actions "
                + "where action = 'REFUND_RETRIED'");
        assertThat(audit).containsEntry("target_type", "REFUND").containsEntry("target_id", refund);
        assertThat((String) audit.get("details")).contains("PROCESSED");
        assertThat(provider.calls).hasSize(2);
    }

    @Test
    void aRefundThatIsNotFailedCannotBeRetried() throws Exception {
        long id = paid(driver, 10);
        failedRefund(id);
        long refund = jdbc.queryForObject("select id from refunds", Long.class);
        retry(refund).andExpect(status().isOk());
        retry(refund).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RETRYABLE"));
        retry(999999L).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from admin_actions where action = 'REFUND_RETRIED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void aManualRetryWorksEvenAfterTheAutomaticAttemptsAreUsedUp() throws Exception {
        long id = paid(driver, 10);
        long refund = failedRefund(id);
        jdbc.update("update refunds set attempts = 5 where id = ?", refund);

        retry(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.attempts").value(6));
    }

    @Test
    void aRetryThatFailsAgainIsReportedAsStillFailed() throws Exception {
        long id = paid(driver, 10);
        long refund = failedRefund(id);
        provider.failures.set(1);
        retry(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(2)).andExpect(jsonPath("$.lastError").isNotEmpty());
        assertThat(jdbc.queryForObject("select details from admin_actions where action = 'REFUND_RETRIED' "
                + "order by id desc limit 1", String.class)).contains("FAILED");
        retry(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(jdbc.queryForObject("select details from admin_actions where action = 'REFUND_RETRIED' "
                + "order by id desc limit 1", String.class)).contains("PROCESSED");
    }

    @Test
    void theAuditTextFollowsTheRefundsFinalStatus() throws Exception {
        long id = paid(driver, 10);
        long refund = failedRefund(id);
        provider.pendingRefunds = true; // the provider accepts the retry but is still settling it
        retry(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(jdbc.queryForObject("select details from admin_actions where action = 'REFUND_RETRIED'",
                String.class)).contains("PENDING");
    }

    @Test
    void paymentAndRefundEndpointsAreAdminOnly() throws Exception {
        for (String auth : new String[] {driver.auth(), ownerAuth}) {
            mvc.perform(get("/api/v1/admin/payments").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/refunds").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/refunds/1/retry").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/payments")).andExpect(status().isUnauthorized());
    }
}
