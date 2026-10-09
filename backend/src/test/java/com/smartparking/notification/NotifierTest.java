package com.smartparking.notification;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed tests: the facade's whole point is what happens at (and instead of) commit. */
@CommittedIntegrationTest
class NotifierTest {

    private static final String OWNER_EMAIL = "nt-owner@example.com";
    private static final String DRIVER_EMAIL = "nt-driver@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired Notifier notifier;
    @Autowired RecordingEmailSender emails;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        tx = new TransactionTemplate(txManager);
        AuthTestSupport.register(mvc, "nt-user@example.com", "DRIVER");
        user = users.findByEmail("nt-user@example.com").orElseThrow();
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private EmailMessage mail(String subject) {
        return new EmailMessage("nt-user@example.com", subject, "text", "<p>html</p>");
    }

    private int rows(String email) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = ?", Integer.class, email);
    }

    private List<String> notificationsOf(String email) {
        return jdbc.queryForList("select n.type || ' ' || n.link from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? order by n.id", String.class, email);
    }

    // ---- the facade itself ------------------------------------------------------------------------------------

    @Test
    void savesTheNotificationAndSendsTheEmailOnceAfterCommit() {
        tx.executeWithoutResult(status -> {
            notifier.notify(user, NotificationType.BOOKING_CONFIRMED, "Booking confirmed", "Body", "/driver/bookings/7",
                    mail("Subject"));
            assertThat(emails.sentTo("nt-user@example.com")).isEmpty(); // not before the commit
        });

        assertThat(emails.sentTo("nt-user@example.com")).extracting(EmailMessage::subject).containsExactly("Subject");
        var row = jdbc.queryForMap("select type, title, body, link, read_at from notifications");
        assertThat(row).containsEntry("type", "BOOKING_CONFIRMED").containsEntry("title", "Booking confirmed")
                .containsEntry("body", "Body").containsEntry("link", "/driver/bookings/7")
                .containsEntry("read_at", null);
    }

    @Test
    void aRolledBackTransactionLeavesNeitherNotificationNorEmail() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            notifier.notify(user, NotificationType.BOOKING_CONFIRMED, "T", "B", "/x", mail("Subject"));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows("nt-user@example.com")).isZero();
        assertThat(emails.sentTo("nt-user@example.com")).isEmpty();
    }

    @Test
    void withoutATransactionTheRowIsSavedAndTheEmailGoesOutAtOnce() {
        notifier.notify(user, NotificationType.OWNER_VERIFIED, "T", "B", null, mail("Now"));

        assertThat(rows("nt-user@example.com")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select link from notifications", String.class)).isNull();
        assertThat(emails.sentTo("nt-user@example.com")).hasSize(1);
    }

    @Test
    void anInAppOnlyNotificationSendsNoEmail() {
        tx.executeWithoutResult(status ->
                notifier.notify(user, NotificationType.BOOKING_STARTING_SOON, "T", "B", "/x", null));

        assertThat(rows("nt-user@example.com")).isEqualTo(1);
        assertThat(emails.sentTo("nt-user@example.com")).isEmpty();
    }

    @Test
    void longTextIsTruncatedToTheColumnLimits() {
        String emoji = "😀"; // a surrogate pair: must never be cut in half
        notifier.notify(user, NotificationType.BOOKING_CONFIRMED, "t".repeat(300), emoji.repeat(400), "/x", null);

        var row = jdbc.queryForMap("select title, body from notifications");
        String title = (String) row.get("title");
        String body = (String) row.get("body");
        assertThat(title).hasSize(120).endsWith("…");
        assertThat(body.length()).isLessThanOrEqualTo(500);
        assertThat(body).endsWith("…");
        assertThat(body.substring(0, body.length() - 1)).isEqualTo(emoji.repeat((body.length() - 1) / 2));
    }

    @Test
    void aLinkTooLongForItsColumnIsDroppedNotCutShort() {
        String fits = "/driver/bookings/" + "1".repeat(300 - "/driver/bookings/".length());
        notifier.notify(user, NotificationType.BOOKING_CONFIRMED, "Fits", "B", fits, null);
        notifier.notify(user, NotificationType.BOOKING_CONFIRMED, "Too long", "B", fits + "2", null);

        assertThat(jdbc.queryForObject("select link from notifications where title = 'Fits'", String.class))
                .isEqualTo(fits);
        assertThat(jdbc.queryForObject("select link from notifications where title = 'Too long'", String.class))
                .isNull();
    }

    // ---- the refactored flows ---------------------------------------------------------------------------------

    private record Fixture(String ownerAuth, Long listingId, Driver driver) {
    }

    private Fixture fixture(boolean autoApprove) throws Exception {
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        Long listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Notify Spot", 18.5204, 73.8567, 30);
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(autoApprove);
        listings.saveAndFlush(listing);
        Driver driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        emails.clear();
        return new Fixture(ownerAuth, listingId, driver);
    }

    private long paidBooking(Fixture f, int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        long id = bookingId(reserveOk(mvc, f.driver().auth(), f.listingId(), f.driver().vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, f.driver().auth(), id);
        return id;
    }

    @Test
    void anAutoConfirmedBookingNotifiesBothPartiesAndEmailsEachOnce() throws Exception {
        Fixture f = fixture(true);
        long id = paidBooking(f, 10);

        assertThat(notificationsOf(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED /driver/bookings/" + id);
        assertThat(notificationsOf(OWNER_EMAIL)).containsExactly("OWNER_NEW_BOOKING /owner/bookings");
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
        assertThat(emails.sentTo(OWNER_EMAIL)).hasSize(1);
        assertThat(jdbc.queryForObject("select body from notifications where type = 'BOOKING_CONFIRMED'",
                String.class)).contains("Notify Spot").containsPattern("[A-Z0-9]{4,}");
    }

    @Test
    void aRequestAndTheOwnersDecisionNotifyTheDriver() throws Exception {
        Fixture f = fixture(false);
        long approved = paidBooking(f, 10);
        long declined = paidBooking(f, 14);

        assertThat(notificationsOf(OWNER_EMAIL)).containsExactly("OWNER_APPROVAL_NEEDED /owner/bookings",
                "OWNER_APPROVAL_NEEDED /owner/bookings");
        assertThat(notificationsOf(DRIVER_EMAIL)).containsExactly("BOOKING_REQUESTED /driver/bookings/" + approved,
                "BOOKING_REQUESTED /driver/bookings/" + declined);
        emails.clear();

        mvc.perform(post("/api/v1/owner/bookings/" + approved + "/approve")
                .header(HttpHeaders.AUTHORIZATION, f.ownerAuth())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/owner/bookings/" + declined + "/reject")
                .header(HttpHeaders.AUTHORIZATION, f.ownerAuth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Closed\"}")).andExpect(status().isOk());

        assertThat(notificationsOf(DRIVER_EMAIL)).endsWith("BOOKING_APPROVED /driver/bookings/" + approved,
                "BOOKING_DECLINED /driver/bookings/" + declined);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(2);
    }
}
