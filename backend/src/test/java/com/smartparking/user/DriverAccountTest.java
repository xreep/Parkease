package com.smartparking.user;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class DriverAccountTest {

    private static final String OWNER_EMAIL = "da-owner@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    private String ownerAuth;
    private Long listingId;
    private Driver driver;
    private Driver other;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Account Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "da-driver@example.com");
        other = driverWithVehicle(mvc, "da-driver2@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private long hold(Driver who, int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        return bookingId(reserveOk(mvc, who.auth(), listingId, who.vehicleId(), start, start.plusSeconds(7200)));
    }

    private long paid(Driver who, int startHour) throws Exception {
        long id = hold(who, startHour);
        payOk(mvc, who.auth(), id);
        return id;
    }

    /** A paid booking turned COMPLETED: it lasted {@code hours} and ended {@code endedAgo} ago. */
    private long completed(Driver who, int startHour, int hours, Duration endedAgo) throws Exception {
        long id = paid(who, startHour);
        Instant end = Instant.now().minus(endedAgo);
        jdbc.update("update bookings set status = 'COMPLETED', start_time = ?, end_time = ?, completed_at = ? "
                        + "where id = ?", Timestamp.from(end.minus(Duration.ofHours(hours))), Timestamp.from(end),
                Timestamp.from(end), id);
        return id;
    }

    private ResultActions getAs(Driver who, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, who.auth()));
    }

    private void review(Driver who, long bookingId) throws Exception {
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/review").header(HttpHeaders.AUTHORIZATION, who.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}")).andExpect(status().isCreated());
    }

    // ---- payments -------------------------------------------------------------------------------------------

    @Test
    void paymentsListsOnlyOwnCapturedOrLaterNewestFirst() throws Exception {
        long older = paid(driver, 6);
        long newer = paid(driver, 8);
        long refunded = paid(driver, 10);
        hold(driver, 12);                    // never paid: its payment is still CREATED
        paid(other, 14);                     // somebody else's
        jdbc.update("update payments set captured_at = now() - interval '3 hours' where booking_id = ?", older);
        jdbc.update("update payments set captured_at = now() - interval '2 hours' where booking_id = ?", newer);
        jdbc.update("update payments set captured_at = now() - interval '1 hour' where booking_id = ?", refunded);
        jdbc.update("update bookings set start_time = now() + interval '3 days', "
                + "end_time = now() + interval '3 days 2 hours' where id = ?", refunded);
        mvc.perform(post("/api/v1/bookings/" + refunded + "/cancel").header(HttpHeaders.AUTHORIZATION, driver.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

        getAs(driver, "/api/v1/me/payments")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].bookingId", contains((int) refunded, (int) newer, (int) older)))
                .andExpect(jsonPath("$.content[0].status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.content[0].refundAmount").value(60.0))
                .andExpect(jsonPath("$.content[0].amount").value(67.08))
                .andExpect(jsonPath("$.content[1].status").value("CAPTURED"))
                .andExpect(jsonPath("$.content[1].refundAmount").value(0.0))
                .andExpect(jsonPath("$.content[1].listingTitle").value("Account Spot"))
                .andExpect(jsonPath("$.content[1].bookingCode").isNotEmpty())
                .andExpect(jsonPath("$.content[1].id").isNumber())
                .andExpect(jsonPath("$.content[1].paidAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].invoiceNumber", startsWith("INV-")))
                .andExpect(jsonPath("$.content[1].receiptAvailable").value(true));
        getAs(driver, "/api/v1/me/payments?page=1&size=2")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].bookingId").value(older))
                .andExpect(jsonPath("$.totalPages").value(2));
        getAs(other, "/api/v1/me/payments").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void paymentsWithoutAnInvoiceHaveNoReceipt() throws Exception {
        long id = paid(driver, 6);
        jdbc.update("delete from invoices where booking_id = ?", id);

        getAs(driver, "/api/v1/me/payments")
                .andExpect(jsonPath("$.content[0].invoiceNumber").value(nullValue()))
                .andExpect(jsonPath("$.content[0].receiptAvailable").value(false));
    }

    // ---- stats ----------------------------------------------------------------------------------------------

    @Test
    void statsAddUpBookingsSpendingHoursAndPendingReviews() throws Exception {
        long recent = completed(driver, 6, 2, Duration.ofHours(1));          // reviewable, ended last
        long older = completed(driver, 8, 2, Duration.ofDays(5));            // reviewable
        completed(driver, 10, 2, Duration.ofDays(40));                       // too old to review
        long reviewed = completed(driver, 12, 3, Duration.ofHours(2));
        review(driver, reviewed);
        paid(driver, 14);                                                    // confirmed, ahead
        long cancelled = paid(driver, 16);
        jdbc.update("update bookings set start_time = now() + interval '3 days', "
                + "end_time = now() + interval '3 days 2 hours' where id = ?", cancelled);
        mvc.perform(post("/api/v1/bookings/" + cancelled + "/cancel").header(HttpHeaders.AUTHORIZATION, driver.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        hold(driver, 18);                                                    // unpaid: not a booking yet
        long abandoned = hold(driver, 22);                                   // cancelled before paying: neither
        mvc.perform(post("/api/v1/bookings/" + abandoned + "/cancel").header(HttpHeaders.AUTHORIZATION, driver.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        long foreign = completed(other, 20, 2, Duration.ofHours(1));         // somebody else's

        getAs(driver, "/api/v1/me/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBookings").value(6))
                .andExpect(jsonPath("$.completedBookings").value(4))
                .andExpect(jsonPath("$.amountSpent").value(342.48))   // 6 x 67.08 captured, 60.00 refunded
                .andExpect(jsonPath("$.hoursParked").value(9.0))
                .andExpect(jsonPath("$.pendingReviews").value(2))
                .andExpect(jsonPath("$.reviewBookingId").value(recent));

        review(driver, recent);
        getAs(driver, "/api/v1/me/stats")
                .andExpect(jsonPath("$.pendingReviews").value(1))
                .andExpect(jsonPath("$.reviewBookingId").value(older));
        review(driver, older);
        getAs(driver, "/api/v1/me/stats")
                .andExpect(jsonPath("$.pendingReviews").value(0))
                .andExpect(jsonPath("$.reviewBookingId").value(nullValue()));
        getAs(other, "/api/v1/me/stats")
                .andExpect(jsonPath("$.totalBookings").value(1))
                .andExpect(jsonPath("$.reviewBookingId").value(foreign));
    }

    @Test
    void statsOfADriverWithoutBookingsAreZero() throws Exception {
        getAs(driver, "/api/v1/me/stats")
                .andExpect(jsonPath("$.totalBookings").value(0))
                .andExpect(jsonPath("$.completedBookings").value(0))
                .andExpect(jsonPath("$.amountSpent").value(0.0))
                .andExpect(jsonPath("$.hoursParked").value(0.0))
                .andExpect(jsonPath("$.pendingReviews").value(0))
                .andExpect(jsonPath("$.reviewBookingId").value(nullValue()));
    }

    @Test
    void onlyDriversCanUseThem() throws Exception {
        for (String url : new String[] {"/api/v1/me/payments", "/api/v1/me/stats"}) {
            mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, ownerAuth))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }
}
