package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ParkingListing;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class OwnerBookingControllerTest {

    private static final String OWNER_EMAIL = "ob-owner@example.com";
    private static final String OTHER_OWNER_EMAIL = "ob-owner2@example.com";
    private static final String DRIVER_EMAIL = "ob-driver@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired RecordingEmailSender emails;

    private String ownerAuth;
    private String otherOwnerAuth;
    private Long listingId;
    private Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        otherOwnerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OTHER_OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Approval Spot", 18.5204, 73.8567, 30);
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(false);
        listings.saveAndFlush(listing);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    /** A paid booking waiting for the owner; returns its id. */
    private long awaitingApproval(int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), id);
        emails.clear();
        return id;
    }

    private ResultActions listAs(String auth, String query) throws Exception {
        return mvc.perform(get("/api/v1/owner/bookings" + query).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions act(String auth, long id, String action, String body) throws Exception {
        var request = post("/api/v1/owner/bookings/" + id + "/" + action).header(HttpHeaders.AUTHORIZATION, auth);
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, id);
    }

    @Test
    void ownerSeesTheRequestWithoutPriceBreakdownOfFees() throws Exception {
        long id = awaitingApproval(10);

        listAs(ownerAuth, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.content[0].listingId").value(listingId))
                .andExpect(jsonPath("$.content[0].listingTitle").value("Approval Spot"))
                .andExpect(jsonPath("$.content[0].slotLabel").value("A-01"))
                .andExpect(jsonPath("$.content[0].vehicleType").value("FOUR_WHEELER"))
                .andExpect(jsonPath("$.content[0].plateNumber").value("MH12AB1234"))
                .andExpect(jsonPath("$.content[0].driverFirstName").value("Ravi"))
                .andExpect(jsonPath("$.content[0].baseAmount").value(60.0))
                .andExpect(jsonPath("$.content[0].approvalDeadline").isNotEmpty())
                .andExpect(jsonPath("$.content[0].totalAmount").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
        listAs(ownerAuth, "?view=upcoming").andExpect(jsonPath("$.content", hasSize(0)));
        listAs(ownerAuth, "?view=past").andExpect(jsonPath("$.content", hasSize(0)));
        listAs(ownerAuth, "?status=AWAITING_APPROVAL").andExpect(jsonPath("$.content", hasSize(1)));
        listAs(ownerAuth, "?status=CONFIRMED").andExpect(jsonPath("$.content", hasSize(0)));
        listAs(otherOwnerAuth, "").andExpect(jsonPath("$.content", hasSize(0)));
        listAs(driver.auth(), "").andExpect(status().isForbidden());
    }

    @Test
    void requestsAreOrderedByDeadlineAndUnpaidHoldsAreNeverShown() throws Exception {
        long later = awaitingApproval(14);
        long earlier = awaitingApproval(10);
        jdbc.update("update bookings set approval_deadline = approval_deadline - interval '30 minutes' where id = ?",
                earlier);
        Instant start = tomorrowAt(20);
        reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)); // unpaid hold

        listAs(ownerAuth, "?view=requests")
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(earlier))
                .andExpect(jsonPath("$.content[1].id").value(later));
        listAs(ownerAuth, "?view=past").andExpect(jsonPath("$.content", hasSize(0)));
        listAs(ownerAuth, "?status=PENDING_PAYMENT").andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void approveConfirmsTheBookingAndEmailsTheDriver() throws Exception {
        long id = awaitingApproval(10);

        act(ownerAuth, id, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.approvalDeadline").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("select status, confirmed_at from bookings where id = ?", id);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("confirmed_at")).isNotNull();
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor from booking_events where booking_id = ? order by id desc limit 1", id);
        assertThat(event).containsEntry("from_status", "AWAITING_APPROVAL").containsEntry("to_status", "CONFIRMED")
                .containsEntry("actor", "OWNER");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Your booking is confirmed – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains("Approval Spot", "A-01", "/driver/bookings/" + id);
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.HELD);
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();

        listAs(ownerAuth, "?view=upcoming")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
        listAs(ownerAuth, "?view=requests").andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void rejectRefundsTheDriverInFullAndReversesTheEarning() throws Exception {
        long id = awaitingApproval(10);

        act(ownerAuth, id, "reject", "{\"reason\":\"Gate is closed that day\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Map<String, Object> row = jdbc.queryForMap(
                "select status, cancelled_by, cancel_reason, refund_amount from bookings where id = ?", id);
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "OWNER")
                .containsEntry("cancel_reason", "Gate is closed that day");
        assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("67.08");
        Map<String, Object> refund = jdbc.queryForMap(
                "select r.status, r.amount, r.attempts from refunds r join payments p on p.id = r.payment_id "
                        + "where p.booking_id = ?", id);
        assertThat(refund).containsEntry("status", "PROCESSED").containsEntry("attempts", 1);
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("67.08");
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, id))
                .isEqualTo("REFUNDED");
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking request declined – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody())
                .contains("Gate is closed that day", "A full refund of ₹67.08 is on its way");

        assertThat(jdbc.queryForList("select to_status from booking_events where booking_id = ? order by id",
                String.class, id)).containsSubsequence("AWAITING_APPROVAL", "REJECTED");
        // The slot is free again for another reservation of the same window.
        Instant start = tomorrowAt(10);
        reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200));
        listAs(ownerAuth, "?view=past")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("REJECTED"));
    }

    @Test
    void anotherOwnersBookingIsNotFound() throws Exception {
        long id = awaitingApproval(10);

        act(otherOwnerAuth, id, "approve", null).andExpect(status().isNotFound());
        act(otherOwnerAuth, id, "reject", "{\"reason\":\"No\"}").andExpect(status().isNotFound());
        act(ownerAuth, 999_999L, "approve", null).andExpect(status().isNotFound());
        act(driver.auth(), id, "approve", null).andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
    }

    @Test
    void secondDecisionOrDecisionAfterTheDeadlineIsAnInvalidStatus() throws Exception {
        long id = awaitingApproval(10);
        act(ownerAuth, id, "approve", null).andExpect(status().isOk());

        act(ownerAuth, id, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        act(ownerAuth, id, "reject", "{\"reason\":\"Changed my mind\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();

        long late = awaitingApproval(14);
        jdbc.update("update bookings set approval_deadline = now() - interval '1 minute' where id = ?", late);
        act(ownerAuth, late, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(statusOf(late)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void rejectNeedsAReasonOfReasonableLength() throws Exception {
        long id = awaitingApproval(10);

        act(ownerAuth, id, "reject", "{\"reason\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        act(ownerAuth, id, "reject", "{}").andExpect(status().isBadRequest());
        act(ownerAuth, id, "reject", "{\"reason\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
        act(ownerAuth, id, "reject", "{\"reason\":\"" + "x".repeat(500) + "\"}").andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("REJECTED");
    }

    @Test
    void concurrentRejectionsRefundExactlyOnce() throws Exception {
        long id = awaitingApproval(10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Integer> reject = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return act(ownerAuth, id, "reject", "{\"reason\":\"Racing\"}").andReturn().getResponse().getStatus();
            };
            Future<Integer> a = pool.submit(reject);
            Future<Integer> b = pool.submit(reject);
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
    }
}
