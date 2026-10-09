package com.smartparking.payment;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.orderId;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.BookingApiSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.booking.BookingEvents;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.jayway.jsonpath.JsonPath;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class WebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    /** The mock provider, but with a webhook secret so signatures can be checked end to end. */
    @TestConfiguration
    static class WebhookProviderConfig {
        @Bean
        @Primary
        PaymentProvider webhookTestProvider(JwtProperties jwt) {
            return new MockPaymentProvider(jwt.secret()) {
                @Override
                public boolean verifyWebhook(String rawBody, String signature) {
                    return Signatures.matches(Signatures.hmacSha256Hex(SECRET, rawBody), signature);
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired RefundRepository refunds;
    @Autowired PaymentRepository payments;
    @Autowired RecordingEmailSender emails;
    @MockitoSpyBean BookingEvents bookingEvents;

    private Driver driver;
    private Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "wh-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Webhook Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "wh-driver@example.com");
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private record Pending(long bookingId, String orderId) {
    }

    private Pending pendingBooking(int hour) throws Exception {
        Instant start = tomorrowAt(hour);
        String checkout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600));
        return new Pending(bookingId(checkout), orderId(checkout));
    }

    private static String capturedBody(String orderId, String paymentId) {
        return """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
                {"id":"%s","order_id":"%s","method":"upi","status":"captured"}}}}""".formatted(paymentId, orderId);
    }

    private static String failedBody(String orderId) {
        return """
                {"entity":"event","event":"payment.failed","payload":{"payment":{"entity":
                {"id":"pay_failed1","order_id":"%s","status":"failed","error_description":"Card declined by bank"}}}}"""
                .formatted(orderId);
    }

    private static String sign(String body) {
        return Signatures.hmacSha256Hex(SECRET, body);
    }

    private ResultActions send(String body, String eventId) throws Exception {
        return send(body, sign(body), eventId);
    }

    private ResultActions send(String body, String signature, String eventId) throws Exception {
        var request = post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        if (eventId != null) {
            request.header("X-Razorpay-Event-Id", eventId);
        }
        return mvc.perform(request);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, id);
    }

    @Test
    void capturedEventConfirmsThePendingBookingAndIsStored() throws Exception {
        Pending p = pendingBooking(10);

        send(capturedBody(p.orderId(), "pay_wh_1"), "evt_1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        assertThat(bookingStatus(p.bookingId())).isEqualTo("CONFIRMED");
        Map<String, Object> payment = jdbc.queryForMap(
                "select status, provider_payment_id, method from payments where booking_id = ?", p.bookingId());
        assertThat(payment).containsEntry("status", "CAPTURED").containsEntry("provider_payment_id", "pay_wh_1")
                .containsEntry("method", "upi");
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor from booking_events where booking_id = ? and to_status = 'CONFIRMED'",
                String.class, p.bookingId())).isEqualTo("SYSTEM");
        assertThat(count("select count(*) from webhook_events where provider_event_id = 'evt_1' "
                + "and event_type = 'payment.captured' and processed_at is not null")).isEqualTo(1);
        assertThat(emails.sentTo("wh-driver@example.com")).hasSize(1);
    }

    @Test
    void sameEventIdTwiceIsADuplicateThatChangesNothing() throws Exception {
        Pending p = pendingBooking(10);
        String body = capturedBody(p.orderId(), "pay_wh_1");
        send(body, "evt_1").andExpect(status().isOk());
        emails.clear();

        send(body, "evt_1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(count("select count(*) from webhook_events")).isEqualTo(1);
        assertThat(emails.sentTo("wh-driver@example.com")).isEmpty();
    }

    @Test
    void withoutAnEventIdTheBodyHashIdentifiesTheEvent() throws Exception {
        Pending p = pendingBooking(10);
        String body = capturedBody(p.orderId(), "pay_wh_1");

        send(body, null).andExpect(jsonPath("$.status").value("ok"));
        send(body, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(count("select count(*) from webhook_events")).isEqualTo(1);
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
    }

    @Test
    void aDifferentEventForAnAlreadyConfirmedPaymentDoesNotRepeatSideEffects() throws Exception {
        Pending p = pendingBooking(10);
        payOk(mvc, driver.auth(), p.bookingId());
        emails.clear();
        String paymentId = jdbc.queryForObject(
                "select provider_payment_id from payments where booking_id = ?", String.class, p.bookingId());

        send(capturedBody(p.orderId(), paymentId), "evt_late")
                .andExpect(status().isOk());

        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(count("select count(*) from owner_earnings")).isEqualTo(1);
        assertThat(emails.sentTo("wh-driver@example.com")).isEmpty();
    }

    @Test
    void badOrMissingSignatureIsRejectedAndNothingIsStored() throws Exception {
        Pending p = pendingBooking(10);
        String body = capturedBody(p.orderId(), "pay_wh_1");

        send(body, "0".repeat(64), "evt_1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        send(body, null, "evt_1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        assertThat(bookingStatus(p.bookingId())).isEqualTo("PENDING_PAYMENT");
        assertThat(count("select count(*) from webhook_events")).isZero();
    }

    @Test
    void failedEventMarksThePaymentFailedButLeavesTheBookingAlone() throws Exception {
        Pending p = pendingBooking(10);

        send(failedBody(p.orderId()), "evt_f1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));

        Map<String, Object> payment = jdbc.queryForMap(
                "select status, failure_reason from payments where booking_id = ?", p.bookingId());
        assertThat(payment).containsEntry("status", "FAILED").containsEntry("failure_reason", "Card declined by bank");
        assertThat(bookingStatus(p.bookingId())).isEqualTo("PENDING_PAYMENT");

        // The driver can still pay the same order afterwards.
        send(capturedBody(p.orderId(), "pay_wh_2"), "evt_f2").andExpect(status().isOk());
        assertThat(bookingStatus(p.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, p.bookingId()))
                .isEqualTo("CAPTURED");
    }

    @Test
    void aFailureAfterCaptureDoesNotUndoThePayment() throws Exception {
        Pending p = pendingBooking(10);
        send(capturedBody(p.orderId(), "pay_wh_1"), "evt_1").andExpect(status().isOk());

        send(failedBody(p.orderId()), "evt_2").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, p.bookingId()))
                .isEqualTo("CAPTURED");
        assertThat(bookingStatus(p.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void refundEventsUpdateTheRefundRow() throws Exception {
        Pending p = pendingBooking(10);
        payOk(mvc, driver.auth(), p.bookingId());
        Payment payment = payments.findByOrderId(p.orderId()).orElseThrow();
        Refund pending = new Refund();
        pending.setPayment(payment);
        pending.setProviderRefundId("rfnd_wh_1");
        pending.setAmount(new BigDecimal("10.00"));
        pending.setStatus(RefundStatus.PENDING);
        refunds.saveAndFlush(pending);
        Refund second = new Refund();
        second.setPayment(payment);
        second.setProviderRefundId("rfnd_wh_2");
        second.setAmount(new BigDecimal("5.00"));
        second.setStatus(RefundStatus.PENDING);
        refunds.saveAndFlush(second);

        send(refundBody("refund.processed", "rfnd_wh_1"), "evt_r1").andExpect(status().isOk());
        send(refundBody("refund.failed", "rfnd_wh_2"), "evt_r2").andExpect(status().isOk());
        send(refundBody("refund.processed", "rfnd_unknown"), "evt_r3")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));

        assertThat(refunds.findByProviderRefundId("rfnd_wh_1").orElseThrow().getStatus())
                .isEqualTo(RefundStatus.PROCESSED);
        assertThat(refunds.findByProviderRefundId("rfnd_wh_2").orElseThrow().getStatus())
                .isEqualTo(RefundStatus.FAILED);
        // The payment's state follows the refunds that still count (10.00 of 67.08 here).
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, payment.getId()))
                .isEqualTo("PARTIALLY_REFUNDED");
        assertThat(jdbc.queryForObject("select refund_amount from bookings where id = ?", BigDecimal.class,
                p.bookingId())).isEqualByComparingTo("10.00");
    }

    private static String refundBody(String event, String refundId) {
        return """
                {"entity":"event","event":"%s","payload":{"refund":{"entity":{"id":"%s","payment_id":"pay_x"}}}}"""
                .formatted(event, refundId);
    }

    @Test
    void unknownEventsAndUnknownOrdersAreAcknowledged() throws Exception {
        String unknown = "{\"entity\":\"event\",\"event\":\"order.paid\",\"payload\":{}}";
        send(unknown, "evt_u1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));
        send(capturedBody("order_nobody_knows", "pay_zzz"), "evt_u2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));

        assertThat(count("select count(*) from webhook_events")).isEqualTo(2);
        assertThat(count("select count(*) from invoices")).isZero();
    }

    @Test
    void aFailureWhileApplyingTheEventIsA5xxAndTheSameEventSucceedsOnRetry() throws Exception {
        Pending p = pendingBooking(10);
        String body = capturedBody(p.orderId(), "pay_wh_retry");
        // Fail after the invoice and earning were written, to prove the whole attempt rolls back.
        doThrow(new IllegalStateException("boom")).doCallRealMethod()
                .when(bookingEvents).record(any(), any(), any(), any(), any());

        send(body, "evt_retry")
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("WEBHOOK_PROCESSING_FAILED"));

        assertThat(bookingStatus(p.bookingId())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, p.bookingId()))
                .isEqualTo("CREATED");
        assertThat(count("select count(*) from invoices")).isZero();
        assertThat(count("select count(*) from owner_earnings")).isZero();
        assertThat(count("select count(*) from webhook_events where provider_event_id = 'evt_retry' "
                + "and processed_at is null")).isEqualTo(1);

        send(body, "evt_retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));

        assertThat(bookingStatus(p.bookingId())).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(count("select count(*) from webhook_events where provider_event_id = 'evt_retry' "
                + "and processed_at is not null")).isEqualTo(1);
        assertThat(emails.sentTo("wh-driver@example.com")).hasSize(1);

        send(body, "evt_retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
    }

    @Test
    void oversizedEventIdsAreStoredAsTheirHash() throws Exception {
        Pending p = pendingBooking(10);
        String body = capturedBody(p.orderId(), "pay_wh_1");
        String longId = "evt_" + "x".repeat(200);

        send(body, longId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        send(body, longId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(jdbc.queryForObject("select provider_event_id from webhook_events", String.class))
                .hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void nonAsciiBodiesAreVerifiedAndStoredAsUtf8() throws Exception {
        Pending p = pendingBooking(10);
        String body = """
                {"entity":"event","event":"payment.failed","payload":{"payment":{"entity":
                {"id":"pay_f","order_id":"%s","status":"failed","error_description":"₹ declined"}}}}"""
                .formatted(p.orderId());

        send(body, "evt_utf8").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));

        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class,
                p.bookingId())).isEqualTo("₹ declined");
        assertThat(jdbc.queryForObject("select payload from webhook_events", String.class)).contains("₹ declined");
    }

    @Test
    void webhookAndClientVerificationRacingConfirmTheOrderOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                Pending p = pendingBooking(8 + 3 * round);
                String pay = mockPay(mvc, driver.auth(), p.bookingId());
                String paymentId = JsonPath.read(pay, "$.paymentId");
                String body = capturedBody(p.orderId(), paymentId);
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Callable<Integer>> calls = List.of(
                        () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return verify(mvc, driver.auth(), p.bookingId(), p.orderId(), paymentId,
                                    JsonPath.read(pay, "$.signature")).andReturn().getResponse().getStatus();
                        },
                        () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return send(body, "evt_race_" + p.bookingId()).andReturn().getResponse().getStatus();
                        });
                for (Future<Integer> f : pool.invokeAll(calls)) {
                    assertThat(f.get()).isEqualTo(200);
                }
                assertThat(bookingStatus(p.bookingId())).isEqualTo("CONFIRMED");
                Map<String, Object> payment = jdbc.queryForMap(
                        "select status, provider_payment_id, captured_at from payments where booking_id = ?", p.bookingId());
                assertThat(payment).containsEntry("status", "CAPTURED").containsEntry("provider_payment_id", paymentId);
                assertThat(payment.get("captured_at")).isNotNull();
                assertThat(count("select count(*) from invoices where booking_id = ?", p.bookingId())).isEqualTo(1);
                assertThat(count("select count(*) from owner_earnings where booking_id = ?", p.bookingId())).isEqualTo(1);
                assertThat(count("select count(*) from booking_events where booking_id = ? and to_status = 'CONFIRMED'",
                        p.bookingId())).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(emails.sentTo("wh-driver@example.com")).hasSize(3);
        assertThat(emails.sentTo("wh-owner@example.com")).hasSize(3);
    }
}
