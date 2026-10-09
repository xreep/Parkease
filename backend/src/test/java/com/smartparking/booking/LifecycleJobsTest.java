package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import com.smartparking.support.MutableClock;
import com.smartparking.notification.NotificationType;
import com.smartparking.notification.Notifier;
import com.smartparking.support.RecordingEmailSender;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@CommittedIntegrationTest
@Import(LifecycleJobsTest.ClockConfig.class)
class LifecycleJobsTest {

    private static final String OWNER_EMAIL = "lc-owner@example.com";
    private static final String DRIVER_EMAIL = "lc-driver@example.com";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired RecordingEmailSender emails;
    @Autowired BookingJobs jobs;
    @Autowired MutableClock clock;
    @MockitoSpyBean Notifier notifier;
    @MockitoSpyBean BookingRepository bookingRepository;

    private String ownerAuth;
    private Long listingId;
    private Driver driver;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Lifecycle Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        DatabaseCleaner.clean(jdbc);
    }

    private void manualApproval() {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(false);
        listings.saveAndFlush(listing);
    }

    private long hold(Instant start) throws Exception {
        return bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
    }

    /** A paid, auto-approved booking tomorrow at {@code startHour} for two hours. */
    private long confirmed(int startHour) throws Exception {
        long id = hold(tomorrowAt(startHour));
        payOk(mvc, driver.auth(), id);
        emails.clear();
        return id;
    }

    private long awaitingApproval(Instant start) throws Exception {
        manualApproval();
        long id = hold(start);
        payOk(mvc, driver.auth(), id);
        emails.clear();
        return id;
    }

    private Map<String, Object> booking(long id) {
        return jdbc.queryForMap("select * from bookings where id = ?", id);
    }

    private Instant instant(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    private List<String> notificationTypes(String type) {
        return jdbc.queryForList("select n.type from notifications n join users u on u.id = n.user_id "
                + "where n.type = ? order by n.id", String.class, type);
    }

    private int notificationCount(String email, String type) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? and n.type = ?", Integer.class, email, type);
    }

    // ---- advanceLifecycle -----------------------------------------------------------------------------------

    @Test
    void confirmedBookingBeforeItsStartIsLeftAlone() throws Exception {
        long id = confirmed(10);
        clock.set(tomorrowAt(10).minusSeconds(1));

        jobs.advanceLifecycle();

        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
        assertThat(booking(id).get("completed_at")).isNull();
    }

    @Test
    void confirmedBookingBecomesActiveAtItsStart() throws Exception {
        long id = confirmed(10);
        clock.set(tomorrowAt(10));

        jobs.advanceLifecycle();

        assertThat(booking(id)).containsEntry("status", "ACTIVE");
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor, note from booking_events where booking_id = ? order by id desc limit 1",
                id);
        assertThat(event).containsEntry("from_status", "CONFIRMED").containsEntry("to_status", "ACTIVE")
                .containsEntry("actor", "SYSTEM").containsEntry("note", "Parking time started");
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.HELD);

        jobs.advanceLifecycle(); // idempotent: no second event
        assertThat(jdbc.queryForObject("select count(*) from booking_events where booking_id = ? and to_status = 'ACTIVE'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void activeBookingCompletesAtItsEndAndTheEarningBecomesPayable() throws Exception {
        long id = confirmed(10);
        clock.set(tomorrowAt(10).plusSeconds(60));
        jobs.advanceLifecycle();
        assertThat(booking(id)).containsEntry("status", "ACTIVE");

        Instant end = tomorrowAt(12);
        clock.set(end);
        jobs.advanceLifecycle();

        Map<String, Object> row = booking(id);
        assertThat(row).containsEntry("status", "COMPLETED");
        assertThat(instant(row.get("completed_at"))).isEqualTo(end);
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT);
        Map<String, Object> event = jdbc.queryForMap(
                "select from_status, to_status, actor from booking_events where booking_id = ? order by id desc limit 1", id);
        assertThat(event).containsEntry("from_status", "ACTIVE").containsEntry("to_status", "COMPLETED")
                .containsEntry("actor", "SYSTEM");
        Map<String, Object> notification = jdbc.queryForMap(
                "select n.title, n.body, n.link from notifications n join users u on u.id = n.user_id "
                        + "where u.email = ? and n.type = 'BOOKING_COMPLETED'", DRIVER_EMAIL);
        assertThat((String) notification.get("body")).contains("Thanks for parking with ParkEase");
        assertThat(notification.get("link")).isEqualTo("/driver/bookings/" + id + "#review");

        jobs.advanceLifecycle(); // idempotent
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_COMPLETED")).isEqualTo(1);
    }

    @Test
    void bookingsThatEndedMoreThanADayAgoCompleteQuietlyWithoutTellingTheDriver() throws Exception {
        long old = confirmed(10);
        long recent = confirmed(14); // ends at 16:00
        // Both are over; the first ended 25+ hours ago (a first deploy sweeping up history), the second just now.
        clock.set(tomorrowAt(12).plus(Duration.ofHours(25)).plusSeconds(1));
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(2))),
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(5))), recent);

        jobs.advanceLifecycle();

        assertThat(booking(old)).containsEntry("status", "COMPLETED");
        assertThat(booking(recent)).containsEntry("status", "COMPLETED");
        assertThat(earnings.findByBookingId(old).orElseThrow().getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT);
        assertThat(jdbc.queryForList("select n.link from notifications n where n.type = 'BOOKING_COMPLETED'",
                String.class)).containsExactly("/driver/bookings/" + recent + "#review");
    }

    @Test
    void confirmedBookingWhoseWholeWindowPassedWhileTheJobWasDownCompletesDirectly() throws Exception {
        long id = confirmed(10);
        clock.set(tomorrowAt(15));

        jobs.advanceLifecycle();

        assertThat(booking(id)).containsEntry("status", "COMPLETED");
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT);
        assertThat(jdbc.queryForList("select from_status || '>' || to_status from booking_events where booking_id = ? "
                + "and actor = 'SYSTEM' and to_status in ('ACTIVE', 'COMPLETED')", String.class, id))
                .containsExactly("CONFIRMED>COMPLETED");
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_COMPLETED")).isEqualTo(1);
    }

    @Test
    void completingOnlyMovesAHeldEarning() throws Exception {
        long released = confirmed(10);
        long paidOut = confirmed(14);
        jdbc.update("update owner_earnings set status = 'PAID' where booking_id = ?", paidOut);
        jdbc.update("update owner_earnings set status = 'REVERSED' where booking_id = ?", released);
        clock.set(tomorrowAt(18));

        jobs.advanceLifecycle();

        assertThat(booking(released)).containsEntry("status", "COMPLETED");
        assertThat(booking(paidOut)).containsEntry("status", "COMPLETED");
        assertThat(earnings.findByBookingId(released).orElseThrow().getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(earnings.findByBookingId(paidOut).orElseThrow().getStatus()).isEqualTo(EarningStatus.PAID);
    }

    @Test
    void cancelledRejectedAndUnpaidBookingsAreUntouched() throws Exception {
        long cancelled = confirmed(10);
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", cancelled);
        long rejected = confirmed(14);
        jdbc.update("update bookings set status = 'REJECTED' where id = ?", rejected);
        long unpaid = hold(tomorrowAt(18));
        long awaiting = awaitingApproval(tomorrowAt(22));
        clock.set(tomorrowAt(23).plus(Duration.ofHours(3)));

        jobs.advanceLifecycle();

        assertThat(booking(cancelled)).containsEntry("status", "CANCELLED");
        assertThat(booking(rejected)).containsEntry("status", "REJECTED");
        assertThat(booking(unpaid)).containsEntry("status", "PENDING_PAYMENT");
        assertThat(booking(awaiting)).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_COMPLETED")).isZero();
    }

    // ---- approval deadline cap ------------------------------------------------------------------------------

    @Test
    void approvalDeadlineIsCappedAtTheStartForABookingStartingSoon() throws Exception {
        manualApproval();
        Instant start = Instant.now().plus(Duration.ofMinutes(30));
        long id = hold(tomorrowAt(10));
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?", Timestamp.from(start),
                Timestamp.from(start.plusSeconds(7200)), id);
        payOk(mvc, driver.auth(), id);

        Map<String, Object> row = booking(id);
        assertThat(row).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(instant(row.get("approval_deadline"))).isEqualTo(start);
    }

    @Test
    void approvalDeadlineIsTheFullWindowWhenTheStartIsFarAway() throws Exception {
        long id = awaitingApproval(tomorrowAt(10));
        Instant paidAt = instant(jdbc.queryForObject("select captured_at from payments where booking_id = ?",
                Timestamp.class, id));

        assertThat(instant(booking(id).get("approval_deadline"))).isEqualTo(paidAt.plus(Duration.ofHours(2)));
    }

    @Test
    void requestStillUnansweredAtItsStartIsRejectedForTheStartReason() throws Exception {
        manualApproval();
        Instant start = Instant.now().plus(Duration.ofMinutes(30));
        long id = hold(tomorrowAt(10));
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?", Timestamp.from(start),
                Timestamp.from(start.plusSeconds(7200)), id);
        payOk(mvc, driver.auth(), id);
        clock.set(start.plusSeconds(1));

        jobs.autoRejectOverdue();

        assertThat(booking(id)).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "SYSTEM")
                .containsEntry("cancel_reason", "The booking start time passed before the owner responded");
    }

    // ---- sendReminders --------------------------------------------------------------------------------------

    @Test
    void driverIsRemindedOnceWhenParkingStartsWithinTheHour() throws Exception {
        long id = confirmed(10);
        Instant start = tomorrowAt(10);
        clock.set(start.minus(Duration.ofMinutes(45)));

        jobs.sendReminders();
        jobs.sendReminders();

        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_STARTING_SOON")).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Your parking starts soon – ParkEase");
        String text = emails.lastTo(DRIVER_EMAIL).textBody();
        String code = (String) booking(id).get("booking_code");
        String address = jdbc.queryForObject("select address from parking_listings where id = ?", String.class, listingId);
        assertThat(text).contains(code).contains(address).contains("(IST)");
        assertThat(instant(booking(id).get("reminder_sent_at"))).isEqualTo(clock.instant());
    }

    @Test
    void noReminderForBookingsStartingInTwoHoursOrMore() throws Exception {
        long id = confirmed(10);
        clock.set(tomorrowAt(10).minus(Duration.ofHours(2)));

        jobs.sendReminders();

        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_STARTING_SOON")).isZero();
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
        assertThat(booking(id).get("reminder_sent_at")).isNull();

        clock.set(tomorrowAt(10).minus(Duration.ofMinutes(61)));
        jobs.sendReminders();
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_STARTING_SOON")).isZero();

        clock.set(tomorrowAt(10).minus(Duration.ofMinutes(60)));
        jobs.sendReminders();
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_STARTING_SOON")).isEqualTo(1);
    }

    @Test
    void noReminderOnceParkingHasStartedOrForCancelledBookings() throws Exception {
        long started = confirmed(10);
        long cancelled = confirmed(12);
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", cancelled);
        clock.set(tomorrowAt(11).plus(Duration.ofMinutes(30))); // 10:00 booking under way, 12:00 one cancelled

        jobs.sendReminders();

        assertThat(booking(started).get("reminder_sent_at")).isNull();
        assertThat(booking(cancelled).get("reminder_sent_at")).isNull();
        assertThat(notificationCount(DRIVER_EMAIL, "BOOKING_STARTING_SOON")).isZero();
    }

    @Test
    void ownerIsNudgedOnceWhenTheApprovalDeadlineIsWithinHalfAnHour() throws Exception {
        long id = awaitingApproval(tomorrowAt(10));
        Instant deadline = instant(booking(id).get("approval_deadline"));

        clock.set(deadline.minus(Duration.ofMinutes(31)));
        jobs.sendReminders();
        assertThat(notificationCount(OWNER_EMAIL, "OWNER_APPROVAL_REMINDER")).isZero();

        clock.set(deadline.minus(Duration.ofMinutes(25)));
        jobs.sendReminders();
        jobs.sendReminders();

        assertThat(notificationCount(OWNER_EMAIL, "OWNER_APPROVAL_REMINDER")).isEqualTo(1);
        assertThat(emails.sentTo(OWNER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Respond to a booking request – ParkEase");
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
        assertThat(instant(booking(id).get("approval_nudge_sent_at"))).isEqualTo(clock.instant());
        assertThat(notificationTypes("BOOKING_STARTING_SOON")).isEmpty();
    }

    @Test
    void noNudgeForRequestsAlreadyDecidedOrPastTheirDeadline() throws Exception {
        long decided = awaitingApproval(tomorrowAt(10));
        jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", decided);
        long late = awaitingApproval(tomorrowAt(14));
        Instant deadline = instant(booking(late).get("approval_deadline"));
        clock.set(deadline.plusSeconds(1));

        jobs.sendReminders();

        assertThat(notificationCount(OWNER_EMAIL, "OWNER_APPROVAL_REMINDER")).isZero();
        assertThat(booking(late).get("approval_nudge_sent_at")).isNull();
    }

    // ---- legacy requests that outlive their start ------------------------------------------------------------

    /** A request created before the cap existed: its deadline is later than its start, and the start has passed. */
    private long legacyRequestPastItsStart() throws Exception {
        long id = awaitingApproval(tomorrowAt(10));
        jdbc.update("update bookings set start_time = now() - interval '10 minutes', "
                + "end_time = now() + interval '110 minutes', approval_deadline = now() + interval '1 hour' "
                + "where id = ?", id);
        return id;
    }

    @Test
    void ownerCannotApproveARequestWhoseStartHasPassedEvenBeforeItsDeadline() throws Exception {
        long id = legacyRequestPastItsStart();

        mvc.perform(post("/api/v1/owner/bookings/" + id + "/approve").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.detail").value("This booking's start time has passed"));

        assertThat(booking(id)).containsEntry("status", "AWAITING_APPROVAL");
    }

    @Test
    void legacyRequestPastItsStartIsAutoRejectedAndRefundedInFull() throws Exception {
        long id = legacyRequestPastItsStart();

        jobs.autoRejectOverdue();

        assertThat(booking(id)).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "SYSTEM")
                .containsEntry("cancel_reason", "The booking start time passed before the owner responded");
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, id))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select r.status from refunds r join payments p on p.id = r.payment_id "
                + "where p.booking_id = ?", String.class, id)).isEqualTo("PROCESSED");
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.REVERSED);
    }

    @Test
    void requestPastBothItsDeadlineAndItsStartKeepsTheTimeoutReason() throws Exception {
        long id = awaitingApproval(tomorrowAt(10));
        clock.set(tomorrowAt(10).plusSeconds(1)); // job was down: the 2 h window ended long before the start passed

        jobs.autoRejectOverdue();

        assertThat(booking(id)).containsEntry("status", "REJECTED")
                .containsEntry("cancel_reason", "The owner didn't respond within 2 hours");
    }

    // ---- earnings and failure isolation ----------------------------------------------------------------------

    @Test
    void heldEarningWithNothingLeftIsReversedNotReleasedOnCompletion() throws Exception {
        long id = confirmed(10);
        jdbc.update("update owner_earnings set net = 0 where booking_id = ?", id);
        clock.set(tomorrowAt(13));

        jobs.advanceLifecycle();

        assertThat(booking(id)).containsEntry("status", "COMPLETED");
        assertThat(earnings.findByBookingId(id).orElseThrow().getStatus()).isEqualTo(EarningStatus.REVERSED);
    }

    @Test
    void oneBookingFailingDoesNotStopTheOthersInTheBatch() throws Exception {
        long bad = confirmed(10);
        long good = confirmed(14);
        String badCode = (String) booking(bad).get("booking_code");
        doAnswer(invocation -> {
            if (((String) invocation.getArgument(3)).contains(badCode)) {
                throw new IllegalStateException("boom");
            }
            return invocation.callRealMethod();
        }).when(notifier).notify(any(), eq(NotificationType.BOOKING_COMPLETED), anyString(), anyString(), any(), any());
        clock.set(tomorrowAt(18));

        jobs.advanceLifecycle();

        assertThat(booking(bad)).containsEntry("status", "CONFIRMED"); // rolled back, retried on the next run
        assertThat(booking(good)).containsEntry("status", "COMPLETED");
        assertThat(earnings.findByBookingId(bad).orElseThrow().getStatus()).isEqualTo(EarningStatus.HELD);
        assertThat(earnings.findByBookingId(good).orElseThrow().getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT);
    }

    @Test
    void aFailingCompletePhaseDoesNotSkipTheStartPhase() throws Exception {
        long id = confirmed(10);
        doThrow(new IllegalStateException("db hiccup")).when(bookingRepository).findDueToCompleteIds(any(), any());
        clock.set(tomorrowAt(10).plusSeconds(60));

        jobs.advanceLifecycle(); // must not throw

        assertThat(booking(id)).containsEntry("status", "ACTIVE");
    }

    @Test
    void aFailingReminderPhaseDoesNotSkipTheOwnerNudge() throws Exception {
        long id = awaitingApproval(tomorrowAt(10));
        Instant deadline = instant(booking(id).get("approval_deadline"));
        doThrow(new IllegalStateException("db hiccup")).when(bookingRepository)
                .findDueForReminderIds(any(), any(), any());
        clock.set(deadline.minus(Duration.ofMinutes(10)));

        jobs.sendReminders(); // must not throw

        assertThat(notificationCount(OWNER_EMAIL, "OWNER_APPROVAL_REMINDER")).isEqualTo(1);
    }

    @Test
    void aFailingStartPhaseDoesNotSkipTheCompletionsThatRanBeforeIt() throws Exception {
        long ended = confirmed(10);
        doThrow(new IllegalStateException("db hiccup")).when(bookingRepository).findDueToStartIds(any(), any());
        clock.set(tomorrowAt(13));

        jobs.advanceLifecycle(); // must not throw

        assertThat(booking(ended)).containsEntry("status", "COMPLETED");
    }
}
