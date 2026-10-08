package com.smartparking.payment;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.orderId;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
        var request = post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
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
}
