package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.reserve;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.payment.MockPaymentProvider;
import com.smartparking.payment.PaymentProvider;
import com.smartparking.payment.ProviderOrder;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@CommittedIntegrationTest
class ReserveOrderFailureTest {

    /** The mock provider, but order creation can be switched to fail like an unreachable gateway. */
    static class FlakyProvider extends MockPaymentProvider {
        volatile boolean failOrders;

        FlakyProvider(String jwtSecret) {
            super(jwtSecret);
        }

        @Override
        public ProviderOrder createOrder(String receipt, long amountPaise, Map<String, String> notes) {
            if (failOrders) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Gateway is down");
            }
            return super.createOrder(receipt, amountPaise, notes);
        }
    }

    @TestConfiguration
    static class FlakyProviderConfig {
        @Bean
        @Primary
        PaymentProvider flakyProvider(JwtProperties jwt) {
            return new FlakyProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired PaymentProvider provider;
    @MockitoSpyBean SlotAllocator allocator;
    @MockitoSpyBean BookingMapper mapper;

    private Long listingId;
    private Driver driver;
    private Instant start;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        ((FlakyProvider) provider).failOrders = false;
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "rf-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Failure Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "rf-driver@example.com");
        start = tomorrowAt(10);
    }

    @AfterEach
    void tearDown() {
        ((FlakyProvider) provider).failOrders = false;
        DatabaseCleaner.clean(jdbc);
    }

    private void assertHoldReleased() {
        Map<String, Object> booking = jdbc.queryForMap("select id, status from bookings");
        assertThat(booking.get("status")).isEqualTo("EXPIRED");
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor, note from booking_events where booking_id = ? "
                        + "order by id desc limit 1", booking.get("id"));
        assertThat(event).containsEntry("from_status", "PENDING_PAYMENT").containsEntry("to_status", "EXPIRED")
                .containsEntry("actor", "SYSTEM").containsEntry("note", "Payment order failed");
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isZero();
    }

    @Test
    void aFailedOrderExpiresTheHoldFreesTheSlotAndKeepsTheProviderError() throws Exception {
        ((FlakyProvider) provider).failOrders = true;

        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_ERROR"));

        assertHoldReleased();
        // The slot is free again: a retry once the gateway is back succeeds for the very same window.
        ((FlakyProvider) provider).failOrders = false;
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isCreated());
    }

    @Test
    void aFailingReleaseDoesNotMaskTheOriginalError() throws Exception {
        ((FlakyProvider) provider).failOrders = true;
        doThrow(new IllegalStateException("release broke")).when(allocator).release(any(), any());

        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_ERROR"));
    }

    @Test
    void aFailureAfterTheOrderWasCreatedAlsoReleasesTheHold() throws Exception {
        doThrow(new IllegalStateException("db hiccup")).when(mapper).toDetail(any());

        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertHoldReleased(); // the payment row written in the failed stage rolled back with it
    }
}
