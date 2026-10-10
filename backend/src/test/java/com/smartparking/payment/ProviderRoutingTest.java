package com.smartparking.payment;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingLocks;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingPaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A refund goes to the provider that took the payment, whatever the provider for new checkouts is. The primary
 * recorder plays the provider of new checkouts (type MOCK), a second recorder registered as a legacy provider plays
 * the other type (RAZORPAY): the same split as production with Razorpay as primary and the demo's mock beside it, seen
 * from the other side.
 */
@CommittedIntegrationTest
@Import(ProviderRoutingTest.Config.class)
class ProviderRoutingTest {

    /** A recording provider that reports itself as Razorpay. */
    static class TaggedRazorpay extends RecordingPaymentProvider {
        TaggedRazorpay(String secret) {
            super(secret);
        }

        @Override
        public PaymentProviderType type() {
            return PaymentProviderType.RAZORPAY;
        }
    }

    @TestConfiguration
    static class Config {
        static final TaggedRazorpay LEGACY = new TaggedRazorpay("legacy-secret");

        @Bean
        @Primary
        RecordingPaymentProvider recordingProvider(JwtProperties jwt) {
            return new RecordingPaymentProvider(jwt.secret());
        }

        @Bean
        LegacyPaymentProviders legacyProviders() {
            return new LegacyPaymentProviders(List.of(LEGACY));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired RefundService refundService;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired BookingLocks locks;
    @Autowired RecordingPaymentProvider primary;

    private final RecordingPaymentProvider legacy = Config.LEGACY;
    private long mockBooking;
    private long razorpayBooking;
    private String mockPaymentId;
    private String razorpayPaymentId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        primary.reset();
        legacy.reset();
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "route-owner@example.com", "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Route Spot", 18.5204, 73.8567, 30);
        Driver driver = driverWithVehicle(mvc, "route-driver@example.com");
        Instant start = tomorrowAt(10);
        mockBooking = pay(driver, listingId, start);
        razorpayBooking = pay(driver, listingId, start.plusSeconds(3 * 3600));
        jdbc.update("update payments set provider = 'RAZORPAY' where booking_id = ?", razorpayBooking);
        mockPaymentId = paymentId(mockBooking);
        razorpayPaymentId = paymentId(razorpayBooking);
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private long pay(Driver driver, Long listingId, Instant start) throws Exception {
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), id);
        return id;
    }

    private String paymentId(long bookingId) {
        return jdbc.queryForObject("select provider_payment_id from payments where booking_id = ?", String.class,
                bookingId);
    }

    private long refund(long bookingId, String amount) {
        return tx.execute(s -> {
            Booking booking = locks.lock(bookingId);
            Payment payment = payments.findByBookingId(bookingId).orElseThrow();
            return refundService.refund(booking, payment, new BigDecimal(amount), BookingActor.OWNER, "Routing test",
                    null).getId();
        });
    }

    private static List<String> paymentsAsked(RecordingPaymentProvider provider) {
        return provider.calls.stream().map(RecordingPaymentProvider.Call::paymentId).toList();
    }

    @Test
    void aRefundGoesToTheProviderThatTookThePayment() {
        refund(mockBooking, "20.00");
        refund(razorpayBooking, "30.00");

        assertThat(paymentsAsked(primary)).containsExactly(mockPaymentId);
        assertThat(paymentsAsked(legacy)).containsExactly(razorpayPaymentId);
        assertThat(jdbc.queryForList("select status from refunds", String.class)).containsOnly("PROCESSED");
    }

    @Test
    void theCheckOfExistingRefundsAsksTheSameProvider() {
        legacy.failFetch = true; // the other gateway being down must not matter for a payment it never took

        long refundId = refund(mockBooking, "20.00");

        assertThat(jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId))
                .isEqualTo("PROCESSED");
        assertThat(paymentsAsked(primary)).containsExactly(mockPaymentId);
        assertThat(paymentsAsked(legacy)).isEmpty();
    }

    @Test
    void aFailedRefundIsRetriedAtTheSameProvider() {
        legacy.failures.set(1);
        long refundId = refund(razorpayBooking, "30.00");
        assertThat(jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId))
                .isEqualTo("FAILED");

        assertThat(refundService.retry(refundId)).isTrue();

        assertThat(jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId))
                .isEqualTo("PROCESSED");
        assertThat(paymentsAsked(legacy)).containsExactly(razorpayPaymentId, razorpayPaymentId);
        assertThat(paymentsAsked(primary)).isEmpty();
    }
}
