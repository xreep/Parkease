package com.smartparking.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingPaymentProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The hosted demo: Razorpay is the primary provider, the mock is registered beside it only so that seeded mock
 * payments can be refunded. New checkouts still use Razorpay and the mock pay endpoint stays closed.
 */
@CommittedIntegrationTest
@TestPropertySource(properties = {"app.payments.mock-enabled=false", "app.payments.razorpay.key-id=rzp_test_gate",
        "app.payments.razorpay.key-secret=gate_secret"})
@org.springframework.context.annotation.Import(LegacyMockGateTest.Config.class)
class LegacyMockGateTest {

    /** Stands in for Razorpay (no network): reports the Razorpay type, otherwise the recording mock. */
    static class FakeRazorpay extends RecordingPaymentProvider {
        FakeRazorpay(String secret) {
            super(secret);
        }

        @Override
        public PaymentProviderType type() {
            return PaymentProviderType.RAZORPAY;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentProvider razorpayStandIn(JwtProperties jwt) {
            return new FakeRazorpay(jwt.secret());
        }

        @Bean
        LegacyPaymentProviders demoMock(JwtProperties jwt) {
            return new LegacyPaymentProviders(List.of(new MockPaymentProvider(jwt.secret())));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired PaymentProviders providers;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void newCheckoutsUseTheRealProviderAndTheMockPayEndpointStaysClosed() throws Exception {
        assertThat(providers.primary().type()).isEqualTo(PaymentProviderType.RAZORPAY);
        assertThat(providers.forType(PaymentProviderType.MOCK)).isInstanceOf(MockPaymentProvider.class);

        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "legacy-owner@example.com", "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Legacy Spot", 18.5204, 73.8567, 30);
        Driver driver = BookingApiSupport.driverWithVehicle(mvc, "legacy-driver@example.com");
        Instant start = BookingApiSupport.tomorrowAt(10);
        long bookingId = BookingApiSupport.bookingId(BookingApiSupport.reserveOk(mvc, driver.auth(), listingId,
                driver.vehicleId(), start, start.plusSeconds(3600)));

        assertThat(jdbc.queryForObject("select provider from payments where booking_id = ?", String.class, bookingId))
                .isEqualTo("RAZORPAY");
        mvc.perform(post("/api/v1/payments/mock/pay").header("Authorization", driver.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":" + bookingId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
