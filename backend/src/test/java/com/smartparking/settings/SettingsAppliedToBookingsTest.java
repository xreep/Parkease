package com.smartparking.settings;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.reserve;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Edited settings drive new quotes and holds; amounts already stored on bookings never change. */
@CommittedIntegrationTest
class SettingsAppliedToBookingsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformSettings settings;

    String admin;
    Long listingId;
    Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        resetSettings();
        String owner = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "sa-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, owner, listings, ListingTestSupport.puneCityId(cities),
                "Settings Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "sa-driver@example.com");
        admin = adminAuth(mvc, users, encoder, "sa-admin@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
        resetSettings();
    }

    private void resetSettings() {
        jdbc.update("update platform_settings set value = '10' where key = 'platform_fee_percent'");
        jdbc.update("update platform_settings set value = '10' where key = 'hold_minutes'");
        jdbc.update("update platform_settings set value = '30' where key = 'request_min_lead_minutes'");
        settings.invalidate();
    }

    private void putSettings(String fee, int holdMinutes, int leadMinutes) throws Exception {
        mvc.perform(put("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"platformFeePercent\":%s,\"gstPercent\":18,\"holdMinutes\":%d,\"approvalHours\":2,"
                                + "\"requestMinLeadMinutes\":%d,\"priceGuidelines\":[{\"tier\":1,\"minHourly\":20,\"maxHourly\":150},"
                                + "{\"tier\":2,\"minHourly\":10,\"maxHourly\":100},{\"tier\":3,\"minHourly\":5,\"maxHourly\":80}]}")
                                .formatted(fee, holdMinutes, leadMinutes)))
                .andExpect(status().isOk());
    }

    @Test
    void changedFeeAppliesToNewQuotesAndBookingsOnly() throws Exception {
        Instant start = tomorrowAt(10);
        String first = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200));
        assertThat((Double) JsonPath.read(first, "$.booking.totalAmount")).isEqualTo(67.08); // 60 + 10% + 18% GST on the fee

        putSettings("20", 10, 30);

        // a fresh quote uses the new percentage: 60 + 12.00 fee + 2.16 GST
        mvc.perform(get("/api/v1/listings/" + listingId + "/quote?start=" + tomorrowAt(14) + "&end="
                        + tomorrowAt(16)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.platformFee").value(12.0))
                .andExpect(jsonPath("$.quote.gstAmount").value(2.16))
                .andExpect(jsonPath("$.quote.totalAmount").value(74.16));
        String second = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), tomorrowAt(14), tomorrowAt(16));
        assertThat((Double) JsonPath.read(second, "$.booking.totalAmount")).isEqualTo(74.16);

        // the first booking keeps the amounts it was created with
        mvc.perform(get("/api/v1/bookings/" + bookingId(first)).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(67.08))
                .andExpect(jsonPath("$.platformFee").value(6.0));
    }

    @Test
    void changedHoldMinutesApplyToNewHolds() throws Exception {
        putSettings("10", 25, 30);
        Instant start = tomorrowAt(10);
        String checkout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600));
        Instant holdExpires = Instant.parse(JsonPath.read(checkout, "$.booking.holdExpiresAt"));
        assertThat(Duration.between(Instant.now().plus(Duration.ofMinutes(25)), holdExpires).abs())
                .isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void changedLeadTimeAppliesToRequestToBookListings() throws Exception {
        jdbc.update("update parking_listings set auto_approve = false where id = ?", listingId);
        putSettings("10", 10, 240);
        Instant soon = Instant.now().plus(Duration.ofMinutes(100));
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), soon, soon.plusSeconds(3600))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }
}
