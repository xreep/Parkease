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
import org.junit.jupiter.params.provider.CsvSource;
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
        /** When set, capture() fails with this provider message. */
        volatile String captureFailure;
        volatile boolean pendingRefunds;

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
            captureFailure = null;
            pendingRefunds = false;
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
            if (captureFailure != null) {
                throw new PaymentProviderException(captureFailure);
            }
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
                throw new PaymentProviderException("Insufficient balance for the refund");
            }
            ProviderRefund accepted = super.refund(paymentId, amountPaise, reason);
            return pendingRefunds ? new ProviderRefund(accepted.refundId(), RefundStatus.PENDING) : accepted;
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

    @Test
    void aRefundStillSettlingIsDescribedAsBeingRefunded() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", held.bookingId());
        provider.pendingRefunds = true;
        emails.clear();

        verifyPayment(held, pay).andExpect(status().isOk());

        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains(
                "This booking was already cancelled, so your payment of ₹67.08 is being refunded in full.");
    }

    @Test
    void aRefundThatFailedFirstEmailsTheDriverOnlyOnceTheRetryGetsThrough() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", held.bookingId());
        provider.refundFailures.set(1);
        emails.clear();

        verifyPayment(held, pay).andExpect(status().isOk());

        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
        // The provider's own explanation is kept for support.
        assertThat(jdbc.queryForObject("select failure_reason from refunds", String.class))
                .isEqualTo("Insufficient balance for the refund");

        jobs.retryFailedRefunds();

        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Payment refunded – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains(
                "This booking was already cancelled, so your payment of ₹67.08 has been refunded in full.");
        jobs.retryFailedRefunds();
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
    }

    @Test
    void realModeRefusesAPaymentTheProviderDoesNotDescribeFully() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        asRazorpay(held); // a Razorpay deployment's payments are Razorpay's; the fetcher still answers like the mock

        verifyPayment(held, pay).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));

        assertNothingConfirmed(held);
    }

    @Test
    void aCaptureThatFailsBecauseAConcurrentRequestCapturedFirstIsStillConfirmed() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.captureFailure = "The payment has already been captured";
        AtomicInteger calls = new AtomicInteger();
        provider.fetcher = id -> provided(id, calls.incrementAndGet() == 1 ? "authorized" : "captured", held.orderId(),
                6708L, "INR", "upi");

        verifyPayment(held, pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(provider.captures).hasSize(1);
        assertThat(provider.fetches.get()).isEqualTo(2);
    }

    @Test
    void aCaptureThatFailsForGoodIsAnErrorAndTheProvidersReasonIsKept() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.captureFailure = "Capture amount exceeds the authorized amount";
        provider.fetcher = id -> provided(id, "authorized", held.orderId(), 6708L, "INR", "upi");

        verifyPayment(held, pay).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_ERROR"));

        assertNothingConfirmed(held);
        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class,
                held.bookingId())).isEqualTo("Capture amount exceeds the authorized amount");
    }

    @Test
    void aFailedPaymentKeepsTheProvidersErrorDescription() throws Exception {
        Held held = hold(10);
        String pay = mockPay(mvc, driver.auth(), held.bookingId());
        provider.fetcher = id -> new ProviderPayment(id, "failed", held.orderId(), 6708L, "INR", "card",
                "Card declined by bank");

        verifyPayment(held, pay).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class,
                held.bookingId())).isEqualTo("Card declined by bank");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CREATED");
    }

    // ---- money for a booking that can no longer be paid ---------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"CANCELLED,cancelled", "REJECTED,declined", "COMPLETED,completed"})
    void aCaptureForABookingThatIsNoLongerPayableIsRefundedInFullAndTheBookingStaysAsItWas(String status,
                                                                                         String word)
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
                "This booking was already " + word + ", so your payment of ₹67.08 has been refunded in full.")
                .doesNotContain("couldn't hold your slot");

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
    void anExtraPaymentThatIsOnlyAuthorizedIsNeitherCapturedNorRefundedItIsLeftToVoid() throws Exception {
        Held held = paidWithProviderEchoing();
        provider.fetcher = id -> provided(id, "authorized", held.orderId(), 6708L, "INR", "card");

        capturedWebhook(held, "pay_dup_auth", "evt_dup_a").andExpect(status().isOk());

        assertThat(provider.captures).isEmpty();
        assertThat(provider.refundedPaymentIds).isEmpty();
        assertThat(count("select count(*) from refunds")).isZero();
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CAPTURED");
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
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

        jobs.reconcileRecentPayments();

        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForMap("select status, provider_payment_id, method from payments where booking_id = ?",
                held.bookingId())).containsEntry("status", "CAPTURED").containsEntry("provider_payment_id", "pay_lost_1")
                .containsEntry("method", "upi");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking confirmed – ParkEase");

        jobs.reconcileRecentPayments(); // nothing left to do
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

        jobs.reconcileRecentPayments();

        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("CAPTURED");
    }

    @Test
    void reconciliationRefundsAHoldTheDriverCancelledButHadAlreadyPaidAtTheProvider() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        // The checkout was closed before /verify; the driver then gave the hold up, which failed the open payment.
        mvc.perform(post("/api/v1/bookings/" + held.bookingId() + "/cancel")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk());
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CANCELLED");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("FAILED");
        providerHasCaptured(held, "pay_after_cancel");
        emails.clear();

        jobs.reconcileRecentPayments();

        assertThat(bookingStatus(held.bookingId())).isEqualTo("CANCELLED");
        assertThat(paymentStatus(held.bookingId())).isEqualTo("REFUNDED");
        assertThat(provider.refundedPaymentIds).containsExactly("pay_after_cancel");
        assertThat(jdbc.queryForMap("select amount, status from refunds"))
                .containsEntry("status", "PROCESSED");
        assertThat(jdbc.queryForObject("select amount from refunds", BigDecimal.class)).isEqualByComparingTo("67.08");
        assertThat(jdbc.queryForObject("select refund_amount from bookings where id = ?", BigDecimal.class,
                held.bookingId())).isEqualByComparingTo("67.08");
        assertThat(count("select count(*) from invoices")).isZero();
        assertThat(count("select count(*) from owner_earnings")).isZero();
        assertThat(count("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? and n.type = 'BOOKING_REFUNDED'", DRIVER_EMAIL)).isEqualTo(1);

        jobs.reconcileRecentPayments(); // settled now: nothing more to do
        assertThat(provider.refundedPaymentIds).hasSize(1);
        assertThat(count("select count(*) from refunds")).isEqualTo(1);
    }

    @Test
    void reconciliationLeavesAHoldCancelledBeforeAnyPaymentWithNothingCapturedAlone() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        mvc.perform(post("/api/v1/bookings/" + held.bookingId() + "/cancel")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk());
        provider.orderPayments = order -> List.of(provided("pay_f", "failed", order, 6708L, "INR", "upi"));

        jobs.reconcileRecentPayments();

        assertThat(provider.orderFetches.get()).isEqualTo(1);
        assertThat(count("select count(*) from refunds")).isZero();
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CANCELLED");
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

        jobs.reconcileRecentPayments();

        assertThat(provider.orderFetches.get()).isZero();
        assertThat(bookingStatus(mockOrder.bookingId())).isEqualTo("PENDING_PAYMENT");
        assertThat(bookingStatus(old.bookingId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void reconciliationLooksAtRecentOrdersEveryFiveMinutesAndOlderOnesInTheHourlyRun() throws Exception {
        Held recent = hold(10);
        Held older = hold(14);
        for (Held h : List.of(recent, older)) {
            asRazorpay(h);
        }
        jdbc.update("update payments set created_at = now() - interval '2 hours' where booking_id = ?", older.bookingId());
        provider.orderPayments = order -> List.of(provided("pay_for_" + order, "captured", order, 6708L, "INR", "upi"));
        provider.fetcher = id -> provided(id, "captured", id.substring("pay_for_".length()), 6708L, "INR", "upi");

        jobs.reconcileRecentPayments();
        assertThat(bookingStatus(recent.bookingId())).isEqualTo("CONFIRMED");
        assertThat(bookingStatus(older.bookingId())).isEqualTo("PENDING_PAYMENT");

        jobs.reconcileOlderPayments();
        assertThat(bookingStatus(older.bookingId())).isEqualTo("CONFIRMED");
        // Anything beyond 24 hours is given up on.
        Held ancient = hold(18);
        asRazorpay(ancient);
        jdbc.update("update payments set created_at = now() - interval '25 hours' where booking_id = ?", ancient.bookingId());
        jobs.reconcileOlderPayments();
        assertThat(bookingStatus(ancient.bookingId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void whenThereAreMoreCandidatesThanTheCapTheNewestOrdersAreHandledFirst() throws Exception {
        Held first = hold(10);
        Held second = hold(14);
        Held third = hold(18);
        for (Held h : List.of(first, second, third)) {
            asRazorpay(h);
        }
        provider.orderPayments = order -> List.of(provided("pay_for_" + order, "captured", order, 6708L, "INR", "upi"));
        provider.fetcher = id -> provided(id, "captured", id.substring("pay_for_".length()), 6708L, "INR", "upi");

        jobs.reconcileBand(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60), 2);

        assertThat(bookingStatus(third.bookingId())).isEqualTo("CONFIRMED");
        assertThat(bookingStatus(second.bookingId())).isEqualTo("CONFIRMED");
        assertThat(bookingStatus(first.bookingId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void reconciliationIgnoresPaymentAttemptsThatDidNotCaptureAndSurvivesProviderErrors() throws Exception {
        Held held = hold(10);
        asRazorpay(held);
        provider.orderPayments = order -> List.of(provided("pay_f", "failed", order, 6708L, "INR", "upi"),
                provided("pay_c", "created", order, 6708L, "INR", "upi"));

        jobs.reconcileRecentPayments();
        assertThat(bookingStatus(held.bookingId())).isEqualTo("PENDING_PAYMENT");

        provider.orderPayments = order -> {
            throw new com.smartparking.common.error.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "PAYMENT_PROVIDER_ERROR", "down");
        };
        jobs.reconcileRecentPayments(); // must not throw
        assertThat(bookingStatus(held.bookingId())).isEqualTo("PENDING_PAYMENT");
    }
}
