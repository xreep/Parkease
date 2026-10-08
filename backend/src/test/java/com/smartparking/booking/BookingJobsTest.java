package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.MutableClock;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@CommittedIntegrationTest
@Import(BookingJobsTest.JobTestConfig.class)
class BookingJobsTest {

    private static final String OWNER_EMAIL = "bj-owner@example.com";
    private static final String DRIVER_EMAIL = "bj-driver@example.com";

    /** The mock provider with a movable clock, and refunds that can be told to fail. */
    static class FlakyProvider extends MockPaymentProvider {
        final AtomicInteger refundCalls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        FlakyProvider(String jwtSecret) {
            super(jwtSecret);
        }

        @Override
        public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
            refundCalls.incrementAndGet();
            if (failures.get() > 0) {
                failures.decrementAndGet();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Provider is down");
            }
            return super.refund(paymentId, amountPaise, reason);
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

    private Long listingId;
    private Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        provider.refundCalls.set(0);
        provider.failures.set(0);
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
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

    @Test
    void jobsAreNoOpsWhenThereIsNothingToDo() {
        jobs.expireHolds();
        jobs.autoRejectOverdue();
        jobs.retryFailedRefunds();
        assertThat(provider.refundCalls.get()).isZero();
    }
}
