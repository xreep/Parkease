package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.PdfTestSupport.textOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import java.nio.charset.StandardCharsets;
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
class DriverBookingQueriesTest {

    private static final String OWNER_EMAIL = "dq-owner@example.com";
    private static final String DRIVER_EMAIL = "dq-driver@example.com";
    private static final String OTHER_EMAIL = "dq-driver2@example.com";

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
                ListingTestSupport.puneCityId(cities), "History Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        other = driverWithVehicle(mvc, OTHER_EMAIL);
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private long hold(Driver who, int startHour, int hours) throws Exception {
        Instant start = tomorrowAt(startHour);
        return bookingId(reserveOk(mvc, who.auth(), listingId, who.vehicleId(), start, start.plusSeconds(3600L * hours)));
    }

    private long paid(Driver who, int startHour, int hours) throws Exception {
        long id = hold(who, startHour, hours);
        payOk(mvc, who.auth(), id);
        return id;
    }

    private ResultActions list(Driver who, String query) throws Exception {
        return mvc.perform(get("/api/v1/bookings" + query).header(HttpHeaders.AUTHORIZATION, who.auth()));
    }

    /** One booking in every interesting state, all by {@code driver}: returns ids in a record. */
    private record Fixture(long confirmedLate, long confirmedEarly, long unpaidHold, long ended, long cancelled,
                           long lapsedHold, long expired) {
    }

    private Fixture fixture() throws Exception {
        long ended = paid(driver, 6, 2);
        jdbc.update("update bookings set status = 'COMPLETED', start_time = now() - interval '5 hours', "
                + "end_time = now() - interval '3 hours' where id = ?", ended);
        long confirmedLate = paid(driver, 14, 1);
        long confirmedEarly = paid(driver, 10, 2);
        long cancelled = paid(driver, 16, 1);
        jdbc.update("update bookings set status = 'CANCELLED', cancelled_by = 'DRIVER' where id = ?", cancelled);
        long unpaidHold = hold(driver, 20, 1);
        long expired = hold(driver, 23, 1);
        jdbc.update("update bookings set status = 'EXPIRED' where id = ?", expired);
        // Lapsed last: reserving again would make the slot allocator expire it in bulk.
        long lapsedHold = hold(driver, 22, 1);
        jdbc.update("update bookings set hold_expires_at = now() - interval '1 minute' where id = ?", lapsedHold);
        return new Fixture(confirmedLate, confirmedEarly, unpaidHold, ended, cancelled, lapsedHold, expired);
    }

    @Test
    void upcomingIsTheDefaultAndOrderedByStartAscending() throws Exception {
        Fixture f = fixture();
        paid(other, 12, 1); // somebody else's booking never shows up

        list(driver, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) f.confirmedEarly, (int) f.confirmedLate,
                        (int) f.unpaidHold)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0));
        list(driver, "?view=upcoming")
                .andExpect(jsonPath("$.content[0].id").value(f.confirmedEarly))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[2].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.content[0].listingTitle").value("History Spot"))
                .andExpect(jsonPath("$.content[0].cityName").value("Pune"))
                .andExpect(jsonPath("$.content[0].plateNumber").value("MH12AB1234"))
                .andExpect(jsonPath("$.content[0].vehicleType").value("FOUR_WHEELER"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(67.08))
                .andExpect(jsonPath("$.content[0].bookingCode").isNotEmpty())
                .andExpect(jsonPath("$.content[0].coverPhotoUrl").isNotEmpty())
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    void pastIsCompletedBookingsNewestFirst() throws Exception {
        Fixture f = fixture();
        long laterCompleted = paid(driver, 8, 1);
        jdbc.update("update bookings set status = 'COMPLETED', start_time = now() - interval '2 hours', "
                + "end_time = now() - interval '1 hour' where id = ?", laterCompleted);

        list(driver, "?view=past")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) laterCompleted, (int) f.ended)))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalElements").value(2));
        list(other, "?view=past").andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void activeIsRunningBookingsSoonestFirst() throws Exception {
        fixture();
        long early = paid(driver, 6, 1);
        jdbc.update("update bookings set status = 'ACTIVE', start_time = now() - interval '30 minutes', "
                + "end_time = now() + interval '30 minutes' where id = ?", early);

        list(driver, "?view=active")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) early)))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
        list(driver, "?view=upcoming").andExpect(jsonPath("$.totalElements").value(3)); // the active one left it
        list(other, "?view=active").andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void cancelledHoldsCancelledRejectedAndExpiredNewestFirst() throws Exception {
        long rejected = paid(driver, 18, 1); // before the fixture: its lapsed hold must stay the last reservation
        jdbc.update("update bookings set status = 'REJECTED', cancelled_by = 'OWNER' where id = ?", rejected);
        Fixture f = fixture();

        list(driver, "?view=cancelled")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) f.expired, (int) f.lapsedHold, (int) rejected,
                        (int) f.cancelled)))
                .andExpect(jsonPath("$.content[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.content[1].status").value("PENDING_PAYMENT")) // a lapsed hold counts as expired
                .andExpect(jsonPath("$.content[2].status").value("REJECTED"))
                .andExpect(jsonPath("$.content[3].status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalElements").value(4));
        list(other, "?view=cancelled").andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void allIncludesBothAndPagesAreHonoured() throws Exception {
        Fixture f = fixture();

        list(driver, "?view=all")
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.content", hasSize(7)))
                .andExpect(jsonPath("$.content[0].id").value(f.expired));
        list(driver, "?view=all&page=1&size=3")
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.totalElements").value(7));
        list(driver, "?view=UPCOMING&size=2")
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void invalidViewAndWrongRoleAreRejected() throws Exception {
        list(driver, "?view=sideways")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VIEW"));
        mvc.perform(get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        mvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void detailShowsTheWholeBookingForItsDriverOnly() throws Exception {
        long id = paid(driver, 10, 2);

        mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.address").value("FC Road, Shivajinagar"))
                .andExpect(jsonPath("$.lat").value(18.5204))
                .andExpect(jsonPath("$.lng").value(73.8567))
                .andExpect(jsonPath("$.slotLabel").value("A-01"))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.invoiceNumber").value(org.hamcrest.Matchers.startsWith("INV-")))
                .andExpect(jsonPath("$.ownerFirstName").value("Ravi"))
                .andExpect(jsonPath("$.baseAmount").value(60.0))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.holdExpiresAt").value(nullValue()))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].fromStatus").value(nullValue()))
                .andExpect(jsonPath("$.events[0].toStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.events[1].fromStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.events[1].toStatus").value("CONFIRMED"));

        mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, other.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        mvc.perform(get("/api/v1/bookings/999999").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unpaidBookingDetailHasNoInvoiceButShowsItsHold() throws Exception {
        long id = hold(driver, 10, 1);

        mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("CREATED"))
                .andExpect(jsonPath("$.invoiceNumber").value(nullValue()))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty());
    }

    // ---- receipt --------------------------------------------------------------------------------------------

    private byte[] receiptBytes(Driver who, long id) throws Exception {
        return mvc.perform(get("/api/v1/bookings/" + id + "/receipt").header(HttpHeaders.AUTHORIZATION, who.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void receiptOfAPaidBookingIsAPdfWithTheInvoiceNumber() throws Exception {
        long id = paid(driver, 10, 2);
        String detail = mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andReturn().getResponse().getContentAsString();
        String invoiceNumber = JsonPath.read(detail, "$.invoiceNumber");

        mvc.perform(get("/api/v1/bookings/" + id + "/receipt").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ParkEase-" + invoiceNumber + ".pdf\""));

        byte[] pdf = receiptBytes(driver, id);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains(invoiceNumber, "History Spot", "A-01", "MH12AB1234", "Total paid", "67.08");
        assertThat(text).doesNotContain("Refund");
    }

    @Test
    void receiptOfARejectedBookingShowsTheRefund() throws Exception {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(false);
        listings.saveAndFlush(listing);
        long id = paid(driver, 10, 1);
        mvc.perform(post("/api/v1/owner/bookings/" + id + "/reject").header(HttpHeaders.AUTHORIZATION, ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Closed\"}"))
                .andExpect(status().isOk());

        String text = textOf(receiptBytes(driver, id));

        assertThat(text).contains("Refund", "33.54");
    }

    @Test
    void receiptNeedsAPaidBookingAndOwnership() throws Exception {
        long unpaid = hold(driver, 10, 1);
        long paidId = paid(driver, 14, 1);

        mvc.perform(get("/api/v1/bookings/" + unpaid + "/receipt").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAID"));
        mvc.perform(get("/api/v1/bookings/" + paidId + "/receipt").header(HttpHeaders.AUTHORIZATION, other.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + paidId + "/receipt").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/999999/receipt").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + paidId + "/receipt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bookings/" + paidId)).andExpect(status().isUnauthorized());
    }
}
