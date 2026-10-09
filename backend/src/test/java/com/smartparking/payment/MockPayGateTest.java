package com.smartparking.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import java.time.Instant;
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
 * With {@code app.payments.mock-enabled=false} the mock pay endpoint must be gone even if a mock provider bean is
 * somehow active (Razorpay keys are set so the context starts; the test swaps in a mock provider on purpose).
 */
@CommittedIntegrationTest
@TestPropertySource(properties = {"app.payments.mock-enabled=false", "app.payments.razorpay.key-id=rzp_test_gate",
        "app.payments.razorpay.key-secret=gate_secret"})
class MockPayGateTest {

    @TestConfiguration
    static class MockProviderConfig {
        @Bean
        @Primary
        PaymentProvider mockProvider(JwtProperties jwt) {
            return new MockPaymentProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void mockPayIsNotFoundWhenTheMockIsDisabled() throws Exception {
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "gate-owner@example.com", "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Gate Spot", 18.5204, 73.8567, 30);
        Driver driver = BookingApiSupport.driverWithVehicle(mvc, "gate-driver@example.com");
        Instant start = BookingApiSupport.tomorrowAt(10);
        long bookingId = BookingApiSupport.bookingId(BookingApiSupport.reserveOk(mvc, driver.auth(), listingId,
                driver.vehicleId(), start, start.plusSeconds(3600)));

        // Without the gate this would be 200: the booking is the driver's own and the provider is a mock.
        mvc.perform(post("/api/v1/payments/mock/pay").header("Authorization", driver.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":" + bookingId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
