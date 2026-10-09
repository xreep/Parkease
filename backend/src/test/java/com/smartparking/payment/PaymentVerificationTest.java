package com.smartparking.payment;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.orderId;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.BookingApiSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.booking.BookingJobs;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What the server insists on before it believes "this payment was captured": the provider itself is asked (order,
 * amount, currency, status), an authorized payment is captured first, and money that arrives for a booking that can
 * no longer be paid is refunded on the spot. Also the reconciliation job that finds payments whose confirmation
 * never reached us.
 */
@CommittedIntegrationTest
class PaymentVerificationTest {

    private static final String SECRET = "whsec_verification";
    private static final String DRIVER_EMAIL = "pv-driver@example.com";

    /** A provider whose answers the test scripts. */
    static class StubProvider extends MockPaymentProvider {
        volatile PaymentProviderType type = PaymentProviderType.MOCK;
        volatile Function<String, ProviderPayment> fetcher = ProviderPayment::assumedCaptured;
        volatile Function<String, List<ProviderPayment>> orderPayments = orderId -> List.of();
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicInteger orderFetches = new AtomicInteger();
        final List<String> captures = new CopyOnWriteArrayList<>();
        final List<String> refundedPaymentIds = new CopyOnWriteArrayList<>();
        final AtomicInteger refundFailures = new AtomicInteger();

        StubProvider(String jwtSecret) {
            super(jwtSecret);
        }

        void reset() {
            type = PaymentProviderType.MOCK;
            fetcher = ProviderPayment::assumedCaptured;
            orderPayments = orderId -> List.of();
            fetches.set(0);
            orderFetches.set(0);
            captures.clear();
            refundedPaymentIds.clear();
            refundFailures.set(0);
        }

        @Override
        public PaymentProviderType type() {
            return type;
        }

        @Override
        public ProviderPayment fetchPayment(String paymentId) {
            fetches.incrementAndGet();
            return fetcher.apply(paymentId);
        }

        @Override
        public void capture(String paymentId, long amountPaise, String currency) {
            captures.add(paymentId + ":" + amountPaise + ":" + currency);
        }

        @Override
        public List<ProviderPayment> fetchOrderPayments(String orderId) {
            orderFetches.incrementAndGet();
            return orderPayments.apply(orderId);
        }

        @Override
        public ProviderRefund refund(String paymentId, long amountPaise, String reason) {
            refundedPaymentIds.add(paymentId);
            if (refundFailures.get() > 0) {
                refundFailures.decrementAndGet();
                throw new com.smartparking.common.error.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "PAYMENT_PROVIDER_ERROR", "Provider is down");
            }
            return super.refund(paymentId, amountPaise, reason);
        }

        @Override
        public boolean verifyWebhook(String rawBody, String signature) {
            return Signatures.matches(Signatures.hmacSha256Hex(SECRET, rawBody), signature);
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubProvider stubProvider(JwtProperties jwt) {
            return new StubProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired RecordingEmailSender emails;
    @Autowired StubProvider provider;
    @Autowired BookingJobs jobs;

    private Driver driver;
    private Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        provider.reset();
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "pv-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Verify Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        provider.reset();
        DatabaseCleaner.clean(jdbc);
    }

    private record Held(long bookingId, String orderId) {
    }

    private Held hold(int hour) throws Exception {
        Instant start = tomorrowAt(hour);
        String checkout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200));
        return new Held(bookingId(checkout), orderId(checkout));
    }

    private ResultActions verifyPayment(Held held, String pay) throws Exception {
        return verify(mvc, driver.auth(), held.bookingId(), held.orderId(), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"));
    }

    private static ProviderPayment provided(String paymentId, String status, String orderId, long paise,
                                            String currency, String method) {
        return new ProviderPayment(paymentId, status, orderId, paise, currency, method);
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, id);
    }

    private String paymentStatus(long bookingId) {
        return jdbc.queryForObject("select status from payments where booking_id = ?", String.class, bookingId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void assertNothingConfirmed(Held held) {
        assertThat(bookingStatus(held.bookingId())).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CREATED");
        assertThat(count("select count(*) from invoices")).isZero();
        assertThat(count("select count(*) from owner_earnings")).isZero();
    }

    // ---- verification against the provider ---------------------------------------------------------------------

    @Test
    void aPaymentWhoseAmountDiffersFromTheOrderIsRejected() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 100L, "INR", "upi");

        verifyPayment(held, pay).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));

        assertNothingConfirmed(held);
        assertThat(provider.captures).isEmpty();
    }

    @Test
    void aPaymentForAnotherOrderOrCurrencyOrInAnUnpaidStateIsRejected() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());

        provider.fetcher = id -> provided(id, "captured", "order_someone_else", 6708L, "INR", "upi");
        verifyPayment(held, pay).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 6708L, "USD", "card");
        verifyPayment(held, pay).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        for (String state : List.of("created", "failed", "refunded")) {
            provider.fetcher = id -> provided(id, state, held.orderId(), 6708L, "INR", "upi");
            verifyPayment(held, pay).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        }

        assertNothingConfirmed(held);
        assertThat(provider.captures).isEmpty();
    }

    @Test
    void anAuthorizedPaymentIsCapturedForTheOrderAmountAndThenConfirmed() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        String paymentId = JsonPath.read(pay, "$.paymentId");
        provider.fetcher = id -> provided(id, "authorized", held.orderId(), 6708L, "INR", "card");

        verifyPayment(held, pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(provider.captures).containsExactly(paymentId + ":6708:INR");
        Map<String, Object> payment = jdbc.queryForMap(
                "select status, method, provider_payment_id from payments where booking_id = ?", held.bookingId());
        assertThat(payment).containsEntry("status", "CAPTURED").containsEntry("method", "card")
                .containsEntry("provider_payment_id", paymentId);
    }

    @Test
    void aCapturedPaymentIsConfirmedWithoutCapturingAndKeepsTheProvidersMethod() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 6708L, "INR", "upi");

        verifyPayment(held, pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(provider.captures).isEmpty();
        assertThat(jdbc.queryForObject("select method from payments where booking_id = ?", String.class,
                held.bookingId())).isEqualTo("upi");
    }

    @Test
    void verifyingAnAlreadyConfirmedPaymentAgainDoesNotAskTheProviderAgain() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 6708L, "INR", "upi");

        verifyPayment(held, pay).andExpect(status().isOk());
        verifyPayment(held, pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(provider.fetches.get()).isEqualTo(1);
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
    }

    @Test
    void aCapturedWebhookWithTheWrongAmountIsAcknowledgedButChangesNothing() throws Exception {
        Held held = hold(10);
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 100L, "INR", "upi");
        String body = """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
                {"id":"pay_wh_bad","order_id":"%s","method":"upi","status":"captured"}}}}""".formatted(held.orderId());

        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8))
                        .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(SECRET, body))
                        .header("X-Razorpay-Event-Id", "evt_pv_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertNothingConfirmed(held);
    }

    // ---- money for a booking that can no longer be paid ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "REJECTED", "COMPLETED"})
    void aCaptureForABookingThatIsNoLongerPayableIsRefundedInFullAndTheBookingStaysAsItWas(String status)
            throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        jdbc.update("update bookings set status = ? where id = ?", status, held.bookingId());
        emails.clear();

        verifyPayment(held, pay).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(67.08));

        assertThat(bookingStatus(held.bookingId())).isEqualTo(status);
        Map<String, Object> refund = jdbc.queryForMap(
                "select r.status, r.amount, r.reason from refunds r join payments p on p.id = r.payment_id "
                        + "where p.booking_id = ?", held.bookingId());
        assertThat(refund).containsEntry("status", "PROCESSED")
                .containsEntry("reason", "Booking no longer payable (" + status + ")");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("67.08");
        assertThat(provider.refundedPaymentIds).hasSize(1);
        assertThat(count("select count(*) from invoices")).isZero();
        assertThat(count("select count(*) from owner_earnings")).isZero();
        List<Map<String, Object>> events = jdbc.queryForList(
                "select from_status, to_status, actor, note from booking_events where booking_id = ? "
                        + "order by id", held.bookingId());
        assertThat(events).hasSize(3); // reserved, payment received, refund issued
        assertThat(events.subList(1, 3)).allSatisfy(e -> assertThat(e).containsEntry("actor", "SYSTEM")
                .containsEntry("from_status", status).containsEntry("to_status", status));
        assertThat(events.get(2)).containsEntry("note", "Refund of ₹67.08 issued");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Payment refunded – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains(
                "We couldn't hold your slot, so your payment of ₹67.08 has been refunded in full.");

        verifyPayment(held, pay).andExpect(status().isOk()); // replay: still one refund, one email
        assertThat(count("select count(*) from refunds")).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
    }

    // ---- a second payment for an order that is already paid ------------------------------------------------------

    private ResultActions capturedWebhook(Held held, String paymentId, String eventId) throws Exception {
        String body = """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
                {"id":"%s","order_id":"%s","method":"card","status":"captured"}}}}""".formatted(paymentId, held.orderId());
        return mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(SECRET, body))
                .header("X-Razorpay-Event-Id", eventId));
    }

    private Held paidWithProviderEchoing() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 6708L, "INR", "upi");
        verifyPayment(held, pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        provider.refundedPaymentIds.clear();
        return held;
    }

    @Test
    void aSecondCapturedPaymentOnAnAlreadyPaidOrderIsRefundedInFullAndTheBookingIsUntouched() throws Exception {
        Held held = paidWithProviderEchoing();

        capturedWebhook(held, "pay_dup_1", "evt_dup_1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));

        assertThat(provider.refundedPaymentIds).containsExactly("pay_dup_1");
        Map<String, Object> refund = jdbc.queryForMap(
                "select r.status, r.amount, r.provider_payment_id from refunds r join payments p on p.id = r.payment_id "
                        + "where p.booking_id = ?", held.bookingId());
        assertThat(refund).containsEntry("status", "PROCESSED").containsEntry("provider_payment_id", "pay_dup_1");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("67.08");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CAPTURED");
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select refund_amount from bookings where id = ?", BigDecimal.class,
                held.bookingId())).isEqualByComparingTo("0");
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? and note like '%pay_dup_1%'",
                String.class, held.bookingId())).hasSize(1);

        capturedWebhook(held, "pay_dup_1", "evt_dup_2").andExpect(status().isOk()); // redelivered under another event id
        assertThat(provider.refundedPaymentIds).containsExactly("pay_dup_1");
        assertThat(count("select count(*) from refunds")).isEqualTo(1);
    }

    @Test
    void aFailedRefundOfAnExtraPaymentIsRetriedAndNeverChangesTheBookingsPaymentState() throws Exception {
        Held held = paidWithProviderEchoing();
        provider.refundFailures.set(1);

        capturedWebhook(held, "pay_dup_2", "evt_dup_3").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from refunds", String.class)).isEqualTo("FAILED");

        jobs.retryFailedRefunds();

        assertThat(provider.refundedPaymentIds).containsExactly("pay_dup_2", "pay_dup_2");
        assertThat(jdbc.queryForObject("select status from refunds", String.class)).isEqualTo("PROCESSED");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("select refund_amount from bookings where id = ?", BigDecimal.class,
                held.bookingId())).isEqualByComparingTo("0");
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
    }

    // ---- reconciliation ------------------------------------------------------------------------------------------

    private void asRazorpay(Held held) {
        provider.type = PaymentProviderType.RAZORPAY;
        jdbc.update("update payments set provider = 'RAZORPAY' where booking_id = ?", held.bookingId());
    }

    private void providerHasCaptured(Held held, String paymentId) {
        provider.orderPayments = order -> order.equals(held.orderId())
                ? List.of(provided(paymentId, "captured", order, 6708L, "INR", "upi")) : List.of();
        provider.fetcher = id -> provided(id, "captured", held.orderId(), 6708L, "INR", "upi");
    }

    @Test
    void reconciliationConfirmsAHoldWhosePaymentNeverGotThroughToUs() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        providerHasCaptured(held, "pay_lost_1");
        emails.clear();

        jobs.reconcileLostPayments();

        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForMap("select status, provider_payment_id, method from payments where booking_id = ?",
                held.bookingId())).containsEntry("status", "CAPTURED").containsEntry("provider_payment_id", "pay_lost_1")
                .containsEntry("method", "upi");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking confirmed – ParkEase");

        jobs.reconcileLostPayments(); // nothing left to do
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
    }

    @Test
    void reconciliationAlsoRevivesAnExpiredHoldThatWasPaidLate() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        providerHasCaptured(held, "pay_lost_2");
        jdbc.update("update bookings set status = 'EXPIRED', hold_expires_at = now() - interval '5 minutes' where id = ?",
                held.bookingId());
        jdbc.update("update payments set status = 'FAILED' where booking_id = ?", held.bookingId());

        jobs.reconcileLostPayments();

        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CAPTURED");
    }

    @Test
    void reconciliationLeavesMockOrdersOldOrdersAndSettledOnesAlone() throws Exception {
        Held mockOrder = hold(10);
        Held old = hold(14);
        Held settled = hold(18);
        for (Held h : List.of(old, settled)) {
            jdbc.update("update payments set provider = 'RAZORPAY' where booking_id = ?", h.bookingId());
        }
        provider.type = PaymentProviderType.RAZORPAY;
        jdbc.update("update payments set created_at = now() - interval '25 hours' where booking_id = ?",
                old.bookingId());
        jdbc.update("update payments set status = 'CAPTURED' where booking_id = ?", settled.bookingId());
        jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", settled.bookingId());
        provider.orderPayments = order -> List.of(provided("pay_x", "captured", order, 6708L, "INR", "upi"));

        jobs.reconcileLostPayments();

        assertThat(provider.orderFetches.get()).isZero();
        assertThat(bookingStatus(mockOrder.bookingId())).isEqualTo("PENDING_PAYMENT");
        assertThat(bookingStatus(old.bookingId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void reconciliationIgnoresPaymentAttemptsThatDidNotCaptureAndSurvivesProviderErrors() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        provider.orderPayments = order -> List.of(provided("pay_f", "failed", order, 6708L, "INR", "upi"),
                provided("pay_c", "created", order, 6708L, "INR", "upi"));

        jobs.reconcileLostPayments();
        assertThat(bookingStatus(held.bookingId())).isEqualTo("PENDING_PAYMENT");

        provider.orderPayments = order -> {
            throw new com.smartparking.common.error.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "PAYMENT_PROVIDER_ERROR", "down");
        };
        jobs.reconcileLostPayments(); // must not throw
        assertThat(bookingStatus(held.bookingId())).isEqualTo("PENDING_PAYMENT");
    }
}
