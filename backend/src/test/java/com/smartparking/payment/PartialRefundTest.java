package com.smartparking.payment;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingLocks;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingPaymentProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Several refunds on one payment: amounts, statuses, earnings, retries, adoption and webhooks. */
@CommittedIntegrationTest
@Import(PartialRefundTest.ProviderConfig.class)
class PartialRefundTest {

    private static final String DRIVER_EMAIL = "pr-driver@example.com";
    private static final AtomicInteger EVENT_IDS = new AtomicInteger();

    @TestConfiguration
    static class ProviderConfig {
        @Bean
        @Primary
        RecordingPaymentProvider recordingProvider(JwtProperties jwt) {
            return new RecordingPaymentProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired RefundService refundService;
    @Autowired RefundRepository refunds;
    @Autowired PaymentRepository payments;
    @Autowired OwnerEarningRepository earnings;
    @Autowired BookingLocks locks;
    @Autowired RecordingPaymentProvider provider;

    private Driver driver;
    private Long listingId;
    private long bookingId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        provider.reset();
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "pr-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Partial Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        Instant start = tomorrowAt(10);
        bookingId = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), bookingId); // 67.08 captured: base 60.00, platform fee 6.00, GST 1.08
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** Refunds {@code amount} of the booking's payment under the usual locks; returns the refund row's id. */
    private long refund(String amount) {
        return tx.execute(s -> {
            Booking booking = locks.lock(bookingId);
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            return refundService.refund(booking, payment, new BigDecimal(amount), BookingActor.OWNER, "Partial test",
                    null).getId();
        });
    }

    private String paymentStatus() {
        return jdbc.queryForObject("select status from payments where booking_id = ?", String.class, bookingId);
    }

    private BigDecimal bookingRefund() {
        return jdbc.queryForObject("select refund_amount from bookings where id = ?", BigDecimal.class, bookingId);
    }

    private OwnerEarning earning() {
        return earnings.findByBookingId(bookingId).orElseThrow();
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId);
    }

    private List<Long> paise() {
        return provider.calls.stream().map(RecordingPaymentProvider.Call::paise).toList();
    }

    private static String receipt(long refundId) {
        return "parkease-refund-" + refundId;
    }

    @Test
    void refundsTheProviderListsAndWeHaveRowsForAreNotOrphans() {
        provider.listIssuedRefunds = true;

        refund("10.00");
        refund("5.00");

        assertThat(paise()).containsExactly(1000L, 500L); // the second was not held back by the first
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("15.00");
    }

    // ---- amounts and statuses -------------------------------------------------------------------------------

    @Test
    void refundsInPartsTakeThePaymentThroughPartiallyRefundedToRefunded() {
        refund("20.00");

        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("20.00");
        OwnerEarning afterFirst = earning();
        assertThat(afterFirst.getNet()).isEqualByComparingTo("40.00");
        assertThat(afterFirst.getCommission()).isEqualByComparingTo("6.00");
        assertThat(afterFirst.getStatus()).isEqualTo(EarningStatus.HELD);

        refund("47.08");

        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("67.08");
        assertThat(earning().getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(earning().getNet()).isEqualByComparingTo("0.00");
        assertThat(paise()).containsExactly(2000L, 4708L);
        assertThat(jdbc.queryForList("select status from refunds order by id", String.class))
                .containsExactly("PROCESSED", "PROCESSED");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? order by id", String.class,
                bookingId)).contains("Refund of ₹20.00 issued", "Refund of ₹47.08 issued");
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'BOOKING_REFUNDED'",
                Integer.class)).isZero(); // callers without a notice announce the outcome themselves
    }

    @Test
    void anEarningLosesTheRefundedBaseOnlyAndIsReversedWhenNothingIsLeft() {
        refund("60.00"); // the whole base, none of the fees

        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        OwnerEarning earning = earning();
        assertThat(earning.getNet()).isEqualByComparingTo("0.00");
        assertThat(earning.getCommission()).isEqualByComparingTo("6.00");
        assertThat(earning.getGross()).isEqualByComparingTo("60.00");
        assertThat(earning.getStatus()).isEqualTo(EarningStatus.REVERSED);

        refund("7.08"); // the fees too: nothing changes for the earning any more

        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(earning().getNet()).isEqualByComparingTo("0.00");
        assertThat(earning().getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(earning().getNet()).isEqualByComparingTo("0.00");
    }

    @Test
    void amountsOutsideWhatIsStillRefundableAreRefused() {
        assertThatThrownBy(() -> refund("0.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund("-1.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund("67.09")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(s -> {
            Booking booking = locks.lock(bookingId);
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            return refundService.refund(booking, payment, null, BookingActor.OWNER, "No amount", null);
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.calls).isEmpty();

        refund("50.00");
        assertThatThrownBy(() -> refund("17.09")).isInstanceOf(IllegalArgumentException.class);
        refund("17.08");
        assertThatThrownBy(() -> refund("0.01")).isInstanceOf(IllegalArgumentException.class);

        assertThat(paise()).containsExactly(5000L, 1708L);
        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isEqualTo(2);
    }

    @Test
    void aPaymentThatWasNeverCapturedCannotBeRefunded() {
        jdbc.update("update payments set status = 'FAILED' where booking_id = ?", bookingId);

        assertThatThrownBy(() -> refund("10.00")).isInstanceOf(IllegalStateException.class);
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void aRefundTheProviderRefusedDoesNotCountUntilARetryGetsItThrough() {
        provider.failures.set(1);
        long failed = refund("20.00");

        assertThat(refundStatus(failed)).isEqualTo("FAILED");
        assertThat(paymentStatus()).isEqualTo("CAPTURED");
        assertThat(bookingRefund()).isEqualByComparingTo("0.00");
        assertThat(earning().getNet()).isEqualByComparingTo("40.00"); // the money is owed, so the earning follows

        long second = refund("10.00");
        assertThat(refundStatus(second)).isEqualTo("PROCESSED");
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("10.00");

        assertThat(refundService.retry(failed)).isTrue();

        assertThat(refundStatus(failed)).isEqualTo("PROCESSED");
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("30.00");
        assertThat(paise()).containsExactly(2000L, 1000L, 2000L);
        assertThat(provider.calls.get(2).idempotencyKey()).endsWith("-2"); // a retry never reuses the first key
    }

    @Test
    void aRetryThatWouldRefundMoreThanThePaymentHoldsIsNotMade() {
        provider.failures.set(1);
        long failed = refund("20.00");
        refund("67.08"); // the other refunds now leave no room for the failed one

        assertThat(refundService.retry(failed)).isFalse();

        assertThat(refundStatus(failed)).isEqualTo("FAILED");
        assertThat(paise()).containsExactly(2000L, 6708L);
        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("67.08");
    }

    // ---- adoption -------------------------------------------------------------------------------------------

    @Test
    void eachFailedRefundAdoptsItsOwnRefundAtTheProviderAndNeverItsSiblings() {
        provider.failures.set(2); // both attempts "timed out" although the provider did book them
        long first = refund("20.00");
        long second = refund("10.00");
        assertThat(refundStatus(first)).isEqualTo("FAILED");
        assertThat(refundStatus(second)).isEqualTo("FAILED");
        provider.existingRefunds = List.of(
                new ProviderRefund("rfnd_theirs_1", RefundStatus.PROCESSED, 2000L, receipt(first), Map.of()),
                new ProviderRefund("rfnd_theirs_2", RefundStatus.PROCESSED, 1000L, receipt(second), Map.of()));

        assertThat(refundService.retry(first)).isTrue();
        assertThat(refundService.retry(second)).isTrue();

        assertThat(jdbc.queryForObject("select provider_refund_id from refunds where id = ?", String.class, first))
                .isEqualTo("rfnd_theirs_1");
        assertThat(jdbc.queryForObject("select provider_refund_id from refunds where id = ?", String.class, second))
                .isEqualTo("rfnd_theirs_2");
        assertThat(provider.calls).hasSize(2); // adopted, not refunded again
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("30.00");
    }

    @Test
    void aRefundOfAnotherAmountAtTheProviderIsNotAdoptedAndNeedsManualReview() {
        provider.failures.set(1);
        long failed = refund("20.00");
        provider.existingRefunds = List.of(
                new ProviderRefund("rfnd_dashboard", RefundStatus.PROCESSED, 1500L, null, Map.of()));

        assertThat(refundService.retry(failed)).isFalse();

        assertThat(refundStatus(failed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_reason from refunds where id = ?", String.class, failed))
                .isEqualTo(RefundService.NO_MATCH_REASON);
        assertThat(provider.calls).hasSize(1);
    }

    // ---- webhooks -------------------------------------------------------------------------------------------

    private ResultActions webhook(String event, String providerRefundId) throws Exception {
        String body = """
                {"entity":"event","event":"%s","payload":{"refund":{"entity":{"id":"%s","payment_id":"pay_x"}}}}"""
                .formatted(event, providerRefundId);
        return mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(RecordingPaymentProvider.WEBHOOK_SECRET, body))
                .header("X-Razorpay-Event-Id", "evt_pr_" + EVENT_IDS.incrementAndGet()));
    }

    private String providerRefundId(long refundId) {
        return jdbc.queryForObject("select provider_refund_id from refunds where id = ?", String.class, refundId);
    }

    @Test
    void aProcessedWebhookForAPartialRefundKeepsThePaymentPartiallyRefunded() throws Exception {
        provider.pendingRefunds = true;
        long pending = refund("20.00");
        assertThat(refundStatus(pending)).isEqualTo("PENDING");
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");

        webhook("refund.processed", providerRefundId(pending)).andExpect(status().isOk());

        assertThat(refundStatus(pending)).isEqualTo("PROCESSED");
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("20.00");
        assertThat(earning().getNet()).isEqualByComparingTo("40.00");
    }

    @Test
    void webhooksForSeveralPartialRefundsAreAppliedPerRefundAndRecomputedFromAllOfThem() throws Exception {
        provider.pendingRefunds = true;
        long first = refund("20.00");
        long second = refund("10.00");

        webhook("refund.processed", providerRefundId(first)).andExpect(status().isOk());
        webhook("refund.failed", providerRefundId(second)).andExpect(status().isOk());

        assertThat(refundStatus(first)).isEqualTo("PROCESSED");
        assertThat(refundStatus(second)).isEqualTo("FAILED");
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("20.00");

        webhook("refund.failed", providerRefundId(first)).andExpect(status().isOk()); // late, out of order
        assertThat(paymentStatus()).isEqualTo("CAPTURED");
        assertThat(bookingRefund()).isEqualByComparingTo("0.00");

        webhook("refund.processed", providerRefundId(first)).andExpect(status().isOk());
        webhook("refund.processed", providerRefundId(second)).andExpect(status().isOk());
        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("30.00");
    }

    @Test
    void aFailedWebhookForTheLastPartTakesThePaymentBackToPartiallyRefunded() throws Exception {
        provider.pendingRefunds = true;
        long first = refund("50.00");
        long second = refund("17.08");
        assertThat(paymentStatus()).isEqualTo("REFUNDED");

        webhook("refund.failed", providerRefundId(second)).andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("50.00");
        assertThat(refundStatus(first)).isEqualTo("PENDING");
    }

    @Test
    void processedWebhooksForTwoPartsThatCoverThePaymentLeaveItRefundedWithTheFullAmount() throws Exception {
        provider.pendingRefunds = true;
        long first = refund("50.00");
        long second = refund("17.08");

        webhook("refund.processed", providerRefundId(first)).andExpect(status().isOk());
        webhook("refund.processed", providerRefundId(second)).andExpect(status().isOk());

        assertThat(refundStatus(first)).isEqualTo("PROCESSED");
        assertThat(refundStatus(second)).isEqualTo("PROCESSED");
        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(bookingRefund()).isEqualByComparingTo("67.08");
    }
}
