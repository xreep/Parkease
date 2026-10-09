package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.verify;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.payment.MockPaymentProvider;
import com.smartparking.payment.PaymentProvider;
import com.smartparking.payment.ProviderRefund;
import com.smartparking.payment.RefundService;
import com.smartparking.payment.RefundStatus;
import com.smartparking.payment.Signatures;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.MutableClock;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
@Import(BookingJobsTest.JobTestConfig.class)
class BookingJobsTest {

    private static final String OWNER_EMAIL = "bj-owner@example.com";
    private static final String DRIVER_EMAIL = "bj-driver@example.com";
    private static final String WEBHOOK_SECRET = "whsec_jobs_test";

    /** The mock provider with a movable clock, and refunds that can be told to fail. */
    static class FlakyProvider extends MockPaymentProvider {
        final AtomicInteger refundCalls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        /** When true, accepted refunds are PENDING (settling) instead of PROCESSED. */
        volatile boolean pendingRefunds;
        volatile long lastRefundPaise;
        volatile String lastRefundPaymentId;

        FlakyProvider(String jwtSecret) {
            super(jwtSecret);
        }

        @Override
        public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
            refundCalls.incrementAndGet();
            lastRefundPaise = amountPaise;
            lastRefundPaymentId = paymentId;
            if (failures.get() > 0) {
                failures.decrementAndGet();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Provider is down");
            }
            ProviderRefund accepted = super.refund(paymentId, amountPaise, reason);
            return pendingRefunds ? new ProviderRefund(accepted.refundId(), RefundStatus.PENDING) : accepted;
        }

        @Override
        public boolean verifyWebhook(String rawBody, String signature) {
            return Signatures.matches(Signatures.hmacSha256Hex(WEBHOOK_SECRET, rawBody), signature);
        }
    }

    @TestConfiguration
    static class JobTestConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FlakyProvider flakyProvider(JwtProperties jwt) {
            return new FlakyProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired RecordingEmailSender emails;
    @Autowired BookingJobs jobs;
    @Autowired MutableClock clock;
    @Autowired FlakyProvider provider;
    @Autowired OwnerBookingService ownerBookings;
    @Autowired RefundService refundService;

    private String ownerAuth;
    private Long listingId;
    private Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        provider.refundCalls.set(0);
        provider.failures.set(0);
        provider.pendingRefunds = false;
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Job Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        DatabaseCleaner.clean(jdbc);
    }

    private void manualApproval() {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(false);
        listings.saveAndFlush(listing);
    }

    private long hold(int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        return bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
    }

    private long paidAwaitingApproval(int startHour) throws Exception {
        manualApproval();
        long id = hold(startHour);
        payOk(mvc, driver.auth(), id);
        emails.clear();
        return id;
    }

    private Map<String, Object> booking(long id) {
        return jdbc.queryForMap("select * from bookings where id = ?", id);
    }

    private String paymentStatus(long bookingId) {
        return jdbc.queryForObject("select status from payments where booking_id = ?", String.class, bookingId);
    }

    private Map<String, Object> refund(long bookingId) {
        return jdbc.queryForMap("select r.* from refunds r join payments p on p.id = r.payment_id where p.booking_id = ?",
                bookingId);
    }

    // ---- expireHolds ----------------------------------------------------------------------------------------

    @Test
    void expireHoldsExpiresOnlyLapsedUnpaidHoldsAndRecordsAnEvent() throws Exception {
        long lapsed = hold(10);
        long paid = hold(14);
        payOk(mvc, driver.auth(), paid);
        clock.advance(Duration.ofMinutes(9));
        jobs.expireHolds();
        assertThat(booking(lapsed)).containsEntry("status", "PENDING_PAYMENT");

        clock.advance(Duration.ofMinutes(2)); // 11 minutes after the reservation
        jobs.expireHolds();
        long fresh = hold(18);
        jobs.expireHolds();

        assertThat(booking(lapsed)).containsEntry("status", "EXPIRED");
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor, note from booking_events where booking_id = ? order by id desc limit 1",
                lapsed);
        assertThat(event).containsEntry("from_status", "PENDING_PAYMENT").containsEntry("to_status", "EXPIRED")
                .containsEntry("actor", "SYSTEM").containsEntry("note", "Payment window expired");
        Map<String, Object> payment = jdbc.queryForMap(
                "select status, failure_reason from payments where booking_id = ?", lapsed);
        assertThat(payment).containsEntry("status", "FAILED").containsEntry("failure_reason", "Hold expired");
        assertThat(booking(paid)).containsEntry("status", "CONFIRMED");
        assertThat(booking(fresh)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(paymentStatus(fresh)).isEqualTo("CREATED");

        jobs.expireHolds(); // idempotent
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ?", Integer.class, lapsed))
                .isEqualTo(2);
    }

    @Test
    void holdThatTheSlotAllocatorAlreadyExpiredStillGetsItsEventAndFailedPayment() throws Exception {
        long lapsed = hold(10);
        clock.advance(Duration.ofMinutes(11));
        // Booking the same window again frees the slot by expiring the lapsed hold in bulk (no event, payment untouched).
        long again = hold(10);
        assertThat(booking(lapsed)).containsEntry("status", "EXPIRED");
        assertThat(paymentStatus(lapsed)).isEqualTo("CREATED");

        jobs.expireHolds();

        assertThat(again).isNotEqualTo(lapsed);
        assertThat(booking(again)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(paymentStatus(lapsed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? and to_status = 'EXPIRED'",
                String.class, lapsed)).containsExactly("Payment window expired");
        jobs.expireHolds();
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ?", Integer.class, lapsed))
                .isEqualTo(2);
    }

    @Test
    void sweptHoldWhosePaymentIsAlreadyFailedStillGetsItsExpiredEvent() throws Exception {
        long lapsed = hold(10);
        clock.advance(Duration.ofMinutes(11));
        long again = hold(10); // bulk-expires the lapsed hold without an event
        jdbc.update("update payments set status = 'FAILED', failure_reason = 'Card declined' where booking_id = ?", lapsed);
        assertThat(booking(lapsed)).containsEntry("status", "EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ? and to_status = 'EXPIRED'",
                Integer.class, lapsed)).isZero();

        jobs.expireHolds();

        assertThat(booking(again)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? and to_status = 'EXPIRED'",
                String.class, lapsed)).containsExactly("Payment window expired");
        // The payment keeps its own failure reason.
        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class, lapsed))
                .isEqualTo("Card declined");
        jobs.expireHolds(); // idempotent
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ?", Integer.class, lapsed))
                .isEqualTo(2);
    }

    // ---- autoRejectOverdue ----------------------------------------------------------------------------------

    @Test
    void autoRejectOverdueLeavesPendingRequestsAlone() throws Exception {
        long id = paidAwaitingApproval(10);
        clock.advance(Duration.ofMinutes(119));

        jobs.autoRejectOverdue();

        assertThat(booking(id)).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
    }

    @Test
    void autoRejectOverdueRejectsAndRefundsInFull() throws Exception {
        long overdue = paidAwaitingApproval(10);
        clock.advance(Duration.ofHours(2).plusSeconds(1));

        jobs.autoRejectOverdue();

        Map<String, Object> row = booking(overdue);
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "SYSTEM")
                .containsEntry("cancel_reason", "The owner didn't respond within 2 hours");
        assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("67.08");
        assertThat(refund(overdue)).containsEntry("status", "PROCESSED").containsEntry("attempts", 1);
        assertThat(paymentStatus(overdue)).isEqualTo("REFUNDED");
        assertThat(earnings.findByBookingId(overdue).orElseThrow().getStatus()).isEqualTo(EarningStatus.REVERSED);
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor from booking_events where booking_id = ? and from_status = 'AWAITING_APPROVAL' and to_status = 'REJECTED'",
                overdue);
        assertThat(event).containsEntry("from_status", "AWAITING_APPROVAL").containsEntry("actor", "SYSTEM");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking request expired – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains("A full refund of ₹67.08 is on its way");

        jobs.autoRejectOverdue(); // idempotent: no second refund, no second email
        assertThat(provider.refundCalls.get()).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isEqualTo(1);
    }

    @Test
    void autoRejectOverdueOnlyTouchesTheOverdueOne() throws Exception {
        long first = paidAwaitingApproval(10);
        long second = hold(14);
        payOk(mvc, driver.auth(), second);
        jdbc.update("update bookings set approval_deadline = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), first);

        jobs.autoRejectOverdue();

        assertThat(booking(first)).containsEntry("status", "REJECTED");
        assertThat(booking(second)).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(paymentStatus(second)).isEqualTo("CAPTURED");
    }

    // ---- retryFailedRefunds ---------------------------------------------------------------------------------

    @Test
    void failedRefundIsRetriedUntilTheProviderAccepts() throws Exception {
        long id = paidAwaitingApproval(10);
        provider.failures.set(1);
        clock.advance(Duration.ofHours(3));

        jobs.autoRejectOverdue();

        // The booking is rejected either way; the money is recorded as still owed.
        assertThat(booking(id)).containsEntry("status", "REJECTED");
        assertThat(refund(id)).containsEntry("status", "FAILED").containsEntry("attempts", 1);
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("0");

        jobs.retryFailedRefunds();

        assertThat(refund(id)).containsEntry("status", "PROCESSED").containsEntry("attempts", 2);
        assertThat((String) refund(id).get("provider_refund_id")).startsWith("rfnd_mock_");
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("67.08");
        assertThat(provider.refundCalls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isEqualTo(1);

        jobs.retryFailedRefunds(); // nothing left to do
        assertThat(provider.refundCalls.get()).isEqualTo(2);
    }

    @Test
    void refundRetriesStopAfterFiveAttemptsInTotal() throws Exception {
        long id = paidAwaitingApproval(10);
        provider.failures.set(100);
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();

        for (int i = 0; i < 8; i++) {
            jobs.retryFailedRefunds();
        }

        assertThat(provider.refundCalls.get()).isEqualTo(5);
        assertThat(refund(id)).containsEntry("status", "FAILED").containsEntry("attempts", 5);
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
    }

    @Test
    void retryLeavesAlreadyRefundedBookingsAlone() throws Exception {
        long id = paidAwaitingApproval(10);
        provider.failures.set(1);
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();
        // The money was returned some other way (e.g. a manual refund from the dashboard).
        jdbc.update("update payments set status = 'REFUNDED' where booking_id = ?", id);
        jdbc.update("update bookings set refund_amount = total_amount where id = ?", id);

        jobs.retryFailedRefunds();

        assertThat(provider.refundCalls.get()).isEqualTo(1);
    }

    // ---- money path: amounts, boundaries, ordering ----------------------------------------------------------

    @Test
    void refundsAskTheProviderForTheExactTotalInPaise() throws Exception {
        long overdue = paidAwaitingApproval(10);
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();
        assertThat(provider.lastRefundPaise).isEqualTo(6708L);
        assertThat(provider.lastRefundPaymentId).isEqualTo(
                jdbc.queryForObject("select provider_payment_id from payments where booking_id = ?", String.class, overdue));

        clock.reset();
        long rejected = hold(14);
        payOk(mvc, driver.auth(), rejected);
        provider.lastRefundPaise = 0;
        mvc.perform(post("/api/v1/owner/bookings/" + rejected + "/reject")
                        .header("Authorization", ownerAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"No\"}"))
                .andExpect(status().isOk());
        assertThat(provider.lastRefundPaise).isEqualTo(6708L);
        assertThat(provider.refundCalls.get()).isEqualTo(2);
    }

    @Test
    void theApprovalDeadlineItselfIsAlreadyTooLate() throws Exception {
        long id = paidAwaitingApproval(10);
        Long ownerId = jdbc.queryForObject("select id from users where email = ?", Long.class, OWNER_EMAIL);
        Instant deadline = jdbc.queryForObject("select approval_deadline from bookings where id = ?",
                java.sql.Timestamp.class, id).toInstant();
        clock.set(deadline);

        assertThatThrownBy(() -> ownerBookings.approve(ownerId, id))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATUS"));
        assertThat(booking(id)).containsEntry("status", "AWAITING_APPROVAL");

        jobs.autoRejectOverdue();

        assertThat(booking(id)).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "SYSTEM");
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    }

    @Test
    void oneMillisecondBeforeTheDeadlineStillAllowsApprovalAndTheJobLeavesItAlone() throws Exception {
        long id = paidAwaitingApproval(10);
        Long ownerId = jdbc.queryForObject("select id from users where email = ?", Long.class, OWNER_EMAIL);
        Instant deadline = jdbc.queryForObject("select approval_deadline from bookings where id = ?",
                java.sql.Timestamp.class, id).toInstant();
        clock.set(deadline.minusMillis(1));

        jobs.autoRejectOverdue();
        assertThat(booking(id)).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(ownerBookings.approve(ownerId, id).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void lateVerifyAfterTheExpireJobStillConfirmsTheBooking() throws Exception {
        long id = hold(10);
        String pay = mockPay(mvc, driver.auth(), id);
        clock.advance(Duration.ofMinutes(11));
        jobs.expireHolds();
        assertThat(booking(id)).containsEntry("status", "EXPIRED");
        assertThat(paymentStatus(id)).isEqualTo("FAILED");

        verify(mvc, driver.auth(), id, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.invoiceNumber").isNotEmpty());

        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
    }

    @Test
    void retriesPickLeastTriedAndLongestWaitingFirstSoOneBadRefundCannotStarveTheRest() throws Exception {
        long first = paidAwaitingApproval(10);
        long second = hold(14);
        payOk(mvc, driver.auth(), second);
        provider.failures.set(2);
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();
        long firstRefund = ((Number) refund(first).get("id")).longValue();
        long secondRefund = ((Number) refund(second).get("id")).longValue();
        // The first refund has been tried more often than the second.
        jdbc.update("update refunds set attempts = 3 where id = ?", firstRefund);

        assertThat(refundService.retryableRefundIds(10)).containsExactly(secondRefund, firstRefund);
        assertThat(refundService.retryableRefundIds(1)).containsExactly(secondRefund);

        jdbc.update("update refunds set attempts = 2 where id = ?", firstRefund);
        jdbc.update("update refunds set attempts = 2, updated_at = now() + interval '1 hour' where id = ?", secondRefund);
        assertThat(refundService.retryableRefundIds(10)).containsExactly(firstRefund, secondRefund);
    }

    // ---- provider-reported refund failures ------------------------------------------------------------------

    private ResultActions webhook(String event, String providerRefundId, String eventId) throws Exception {
        String body = """
                {"entity":"event","event":"%s","payload":{"refund":{"entity":{"id":"%s","payment_id":"pay_x"}}}}"""
                .formatted(event, providerRefundId);
        return mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(WEBHOOK_SECRET, body))
                .header("X-Razorpay-Event-Id", eventId));
    }

    @Test
    void refundTheProviderLaterReportsAsFailedIsPutBackOnTheRetryQueueAndCompletes() throws Exception {
        long id = paidAwaitingApproval(10);
        provider.pendingRefunds = true;
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();
        // Accepted but still settling: books say refunded.
        assertThat(refund(id)).containsEntry("status", "PENDING");
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("67.08");
        String providerRefundId = (String) refund(id).get("provider_refund_id");
        provider.pendingRefunds = false;

        webhook("refund.failed", providerRefundId, "evt_rf_1").andExpect(status().isOk());

        assertThat(refund(id)).containsEntry("status", "FAILED");
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? order by id desc limit 1",
                String.class, id)).containsExactly("Refund failed at the provider — retrying");

        webhook("refund.failed", providerRefundId, "evt_rf_2").andExpect(status().isOk()); // redelivery: no change
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ? and note like 'Refund failed%'",
                Integer.class, id)).isEqualTo(1);

        jobs.retryFailedRefunds();

        assertThat(refund(id)).containsEntry("status", "PROCESSED").containsEntry("attempts", 2);
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("67.08");
        assertThat(provider.lastRefundPaise).isEqualTo(6708L);
    }

    @Test
    void exhaustingTheFiveAttemptsLeavesAnEventForSupport() throws Exception {
        long id = paidAwaitingApproval(10);
        provider.failures.set(100);
        clock.advance(Duration.ofHours(3));
        jobs.autoRejectOverdue();

        for (int i = 0; i < 6; i++) {
            jobs.retryFailedRefunds();
        }

        assertThat(refund(id)).containsEntry("attempts", 5).containsEntry("status", "FAILED");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? and note like '%manual action%'",
                String.class, id)).containsExactly("Refund failed after 5 attempts — manual action needed");
    }

    @Test
    void jobsAreNoOpsWhenThereIsNothingToDo() {
        jobs.expireHolds();
        jobs.autoRejectOverdue();
        jobs.retryFailedRefunds();
        assertThat(provider.refundCalls.get()).isZero();
    }
}
