package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.BookingApiSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.common.security.JwtProperties;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.payment.PaymentRepository;
import com.smartparking.payment.ProviderRefund;
import com.smartparking.payment.RefundService;
import com.smartparking.payment.RefundStatus;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import com.smartparking.support.RecordingPaymentProvider;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@CommittedIntegrationTest
@Import(CancellationFlowTest.ProviderConfig.class)
class CancellationFlowTest {

    private static final String OWNER_EMAIL = "cf-owner@example.com";
    private static final String OTHER_OWNER_EMAIL = "cf-owner2@example.com";
    private static final String DRIVER_EMAIL = "cf-driver@example.com";
    private static final String OTHER_DRIVER_EMAIL = "cf-driver2@example.com";

    @TestConfiguration
    static class ProviderConfig {
        @Bean
        @Primary
        RecordingPaymentProvider recordingProvider(JwtProperties jwt) {
            return new RecordingPaymentProvider(jwt.secret());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired RecordingEmailSender emails;
    @Autowired RecordingPaymentProvider provider;
    @Autowired TransactionTemplate tx;
    @Autowired RefundService refundService;
    @Autowired PaymentRepository payments;
    @Autowired BookingLocks locks;
    @Autowired BookingJobs jobs;

    private String ownerAuth;
    private String otherOwnerAuth;
    private Long listingId;
    private Driver driver;
    private Driver other;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        provider.reset();
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        otherOwnerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OTHER_OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Cancel Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        other = driverWithVehicle(mvc, OTHER_DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void configureListing(CancellationPolicy policy, boolean autoApprove) {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setCancellationPolicy(policy);
        listing.setAutoApprove(autoApprove);
        listings.saveAndFlush(listing);
    }

    private long hold(int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        return bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
    }

    /** A paid booking (CONFIRMED on an auto-approve listing, AWAITING_APPROVAL otherwise), emails cleared. */
    private long paid(int startHour) throws Exception {
        long id = hold(startHour);
        payOk(mvc, driver.auth(), id);
        emails.clear();
        return id;
    }

    /** Moves the booking's window so that it starts {@code fromNow} from now (two hours long). */
    private void startsIn(long bookingId, Duration fromNow) {
        Instant start = Instant.now().plus(fromNow);
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?", Timestamp.from(start),
                Timestamp.from(start.plusSeconds(7200)), bookingId);
    }

    private ResultActions preview(String auth, long id) throws Exception {
        return mvc.perform(get("/api/v1/bookings/" + id + "/cancellation-preview")
                .header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions cancel(String auth, long id, String body) throws Exception {
        var request = post("/api/v1/bookings/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, auth);
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ownerCancel(String auth, long id, String body) throws Exception {
        var request = post("/api/v1/owner/bookings/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, auth);
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> booking(long id) {
        return jdbc.queryForMap("select * from bookings where id = ?", id);
    }

    private String paymentStatus(long bookingId) {
        return jdbc.queryForObject("select status from payments where booking_id = ?", String.class, bookingId);
    }

    private int refundCount() {
        return jdbc.queryForObject("select count(*) from refunds", Integer.class);
    }

    private OwnerEarning earning(long bookingId) {
        return earnings.findByBookingId(bookingId).orElseThrow();
    }

    private List<String> notificationTypes(String email) {
        return jdbc.queryForList("select n.type from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? order by n.id", String.class, email);
    }

    private List<String> subjects(String email) {
        return emails.sentTo(email).stream().map(EmailMessage::subject).toList();
    }

    // ---- PENDING_PAYMENT ------------------------------------------------------------------------------------

    @Test
    void cancellingAnUnpaidHoldMovesNoMoneyAndFreesTheSlot() throws Exception {
        long id = hold(10);

        preview(driver.auth(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.policy").doesNotExist())
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.nonRefundableAmount").value(0.0));

        cancel(driver.auth(), id, "{\"reason\":\"Changed my plans\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("DRIVER"))
                .andExpect(jsonPath("$.cancelReason").value("Changed my plans"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.refundAmount").value(0.0));

        assertThat(jdbc.queryForObject("select failure_reason from payments where booking_id = ?", String.class, id))
                .isEqualTo("Cancelled by driver");
        assertThat(refundCount()).isZero();
        assertThat(provider.calls).isEmpty();
        assertThat(jdbc.queryForList("select to_status from booking_events where booking_id = ? order by id",
                String.class, id)).endsWith("CANCELLED");
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Booking cancelled – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains("nothing was charged");
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CANCELLED");
        assertThat(notificationTypes(OWNER_EMAIL)).isEmpty(); // owners never saw the unpaid hold
        assertThat(emails.sentTo(OWNER_EMAIL)).isEmpty();
        // The slot is free again for the same window.
        Instant start = tomorrowAt(10);
        reserveOk(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(7200));
    }

    @Test
    void aPaymentThatArrivesAfterTheDriverCancelledTheHoldIsRefundedAndTheDriverIsToldAboutTheRefund()
            throws Exception {
        long id = hold(10);
        String pay = mockPay(mvc, driver.auth(), id);
        cancel(driver.auth(), id, null).andExpect(status().isOk());
        emails.clear();

        verify(mvc, driver.auth(), id, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(67.08));

        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(6708L);
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Payment refunded – ParkEase");
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CANCELLED", "BOOKING_REFUNDED");
        Map<String, Object> refunded = jdbc.queryForMap("select n.title, n.body, n.link from notifications n "
                + "join users u on u.id = n.user_id where u.email = ? and n.type = 'BOOKING_REFUNDED'", DRIVER_EMAIL);
        assertThat(refunded).containsEntry("title", "Refund issued").containsEntry("link", "/driver/bookings/" + id);
        assertThat((String) refunded.get("body")).contains("₹67.08");
        assertThat(count("select count(*) from owner_earnings")).isZero();
    }

    // ---- AWAITING_APPROVAL ----------------------------------------------------------------------------------

    @Test
    void cancellingARequestNobodyAcceptedRefundsTheWholeTotal() throws Exception {
        configureListing(CancellationPolicy.STRICT, false);
        long id = paid(10);
        assertThat(booking(id)).containsEntry("status", "AWAITING_APPROVAL");

        preview(driver.auth(), id)
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.policy").doesNotExist())
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.refundAmount").value(67.08))
                .andExpect(jsonPath("$.nonRefundableAmount").value(0.0));

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("DRIVER"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(67.08));

        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(6708L);
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Booking cancelled – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains("Your refund of ₹67.08 will be processed to your original payment method.");
        assertThat(subjects(OWNER_EMAIL)).containsExactly("Booking cancelled by driver – ParkEase");
        assertThat(notificationTypes(DRIVER_EMAIL)).contains("BOOKING_CANCELLED").doesNotContain("BOOKING_REFUNDED");
        assertThat(notificationTypes(OWNER_EMAIL)).contains("OWNER_BOOKING_CANCELLED");
        assertThat(jdbc.queryForList("select note from booking_events where booking_id = ? order by id",
                String.class, id)).contains("Cancelled by driver", "Refund of ₹67.08 issued");
    }

    // ---- CONFIRMED ------------------------------------------------------------------------------------------

    @Test
    void moderateMoreThanADayBeforeStartRefundsTheBaseAndKeepsTheFees() throws Exception {
        configureListing(CancellationPolicy.MODERATE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(30).plusMinutes(1));
        assertThat(booking(id)).containsEntry("status", "CONFIRMED");

        preview(driver.auth(), id)
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.policy").value("MODERATE"))
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.refundAmount").value(60.0))
                .andExpect(jsonPath("$.nonRefundableAmount").value(7.08))
                .andExpect(jsonPath("$.hoursBeforeStart").value(30.0));

        cancel(driver.auth(), id, "{\"reason\":\"Trip cancelled\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(60.0))
                .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"));

        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(6000L);
        OwnerEarning earning = earning(id);
        assertThat(earning.getNet()).isEqualByComparingTo("0.00");
        assertThat(earning.getCommission()).isEqualByComparingTo("6.00");
        assertThat(earning.getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody())
                .contains("Your refund of ₹60.00 will be processed to your original payment method.", "₹7.08 is not refundable under the moderate policy");
        assertThat(subjects(OWNER_EMAIL)).containsExactly("Booking cancelled by driver – ParkEase");
        assertThat(emails.lastTo(OWNER_EMAIL).textBody()).contains("Trip cancelled");
        assertThat(notificationTypes(OWNER_EMAIL)).contains("OWNER_BOOKING_CANCELLED");
    }

    @Test
    void moderateThreeHoursBeforeStartRefundsHalfTheBaseAndTheEarningKeepsTheRest() throws Exception {
        configureListing(CancellationPolicy.MODERATE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(3).plusMinutes(1));

        preview(driver.auth(), id)
                .andExpect(jsonPath("$.refundPercent").value(50))
                .andExpect(jsonPath("$.refundAmount").value(30.0))
                .andExpect(jsonPath("$.nonRefundableAmount").value(37.08))
                .andExpect(jsonPath("$.hoursBeforeStart").value(3.0));

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(30.0))
                .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"));

        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(3000L);
        OwnerEarning earning = earning(id);
        assertThat(earning.getNet()).isEqualByComparingTo("30.00");
        assertThat(earning.getCommission()).isEqualByComparingTo("6.00");
        assertThat(earning.getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT);
        assertThat((BigDecimal) booking(id).get("refund_amount")).isEqualByComparingTo("30.00");
    }

    @Test
    void strictTenHoursBeforeStartRefundsNothingAndNeverCallsTheProvider() throws Exception {
        configureListing(CancellationPolicy.STRICT, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(10));

        preview(driver.auth(), id)
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.policy").value("STRICT"))
                .andExpect(jsonPath("$.refundPercent").value(0))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.nonRefundableAmount").value(67.08));

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"));

        assertThat(provider.calls).isEmpty();
        assertThat(refundCount()).isZero();
        OwnerEarning earning = earning(id);
        assertThat(earning.getNet()).isEqualByComparingTo("60.00");
        assertThat(earning.getStatus()).isEqualTo(EarningStatus.PENDING_PAYOUT); // cancelled for good: payable now
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody()).contains("No refund applies under the strict policy.");
        assertThat(notificationTypes(DRIVER_EMAIL)).endsWith("BOOKING_CANCELLED").doesNotContain("BOOKING_REFUNDED");
        // The slot is free again even though the money stays.
        Instant start = tomorrowAt(10);
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?", Timestamp.from(start),
                Timestamp.from(start.plusSeconds(7200)), id);
        reserveOk(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(7200));
    }

    @Test
    void theRefundTheDriverWasShownIsTheRefundTheCancellationIssues() throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofMinutes(45)); // FLEXIBLE under an hour: half the base

        String shown = preview(driver.auth(), id).andReturn().getResponse().getContentAsString();
        String done = cancel(driver.auth(), id, null).andReturn().getResponse().getContentAsString();

        assertThat((Double) JsonPath.read(shown, "$.refundAmount")).isEqualTo(30.0);
        assertThat((Double) JsonPath.read(done, "$.refundAmount")).isEqualTo(30.0);
        assertThat((Double) JsonPath.read(shown, "$.nonRefundableAmount")).isEqualTo(37.08);
        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(3000L);
    }

    @Test
    void flexibleAnHourOrMoreBeforeStartRefundsTheWholeBaseAndReversesTheEarning() throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(2));

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(60.0));

        assertThat(earning(id).getNet()).isEqualByComparingTo("0.00");
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.REVERSED);
    }

    @Test
    void aProviderThatRefusesTheRefundStillCancelsAndLeavesTheRefundForTheRetryJob() throws Exception {
        configureListing(CancellationPolicy.MODERATE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(30).plusMinutes(1));
        provider.failures.set(1);

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.refundAmount").value(0.0));

        assertThat(jdbc.queryForObject("select status from refunds", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select amount from refunds", BigDecimal.class)).isEqualByComparingTo("60.00");
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.REVERSED);
    }

    // ---- refunds the provider may already hold ----------------------------------------------------------------

    /** A refund call that reached the provider but whose transaction then rolled back, leaving no refund row. */
    private void refundLostToARollback(long bookingId, String amount) {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Booking booking = locks.lock(bookingId);
            refundService.refund(booking, payments.findByBookingId(bookingId).orElseThrow(), new BigDecimal(amount),
                    BookingActor.DRIVER, "Booking cancelled by driver", null);
            throw new IllegalStateException("rolled back after the provider call");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(refundCount()).isZero();
        assertThat(provider.calls).hasSize(1);
    }

    @Test
    void aRepeatedCancellationAdoptsTheRefundALostTransactionLeftAtTheProviderInsteadOfRefundingAgain()
            throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofMinutes(45)); // half the base: 30.00
        provider.listIssuedRefunds = true;
        refundLostToARollback(id, "30.00");
        String orphan = provider.issued.get(0).refundId();

        cancel(driver.auth(), id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(30.0))
                .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"));

        assertThat(provider.calls).hasSize(1); // exactly one refund at the provider, ever
        Map<String, Object> row = jdbc.queryForMap("select provider_refund_id, status, amount from refunds");
        assertThat(row).containsEntry("provider_refund_id", orphan).containsEntry("status", "PROCESSED");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("30.00");
        assertThat(earning(id).getNet()).isEqualByComparingTo("30.00");
    }

    @Test
    void aProviderRefundOfAnotherAmountBlocksTheCancellationAndChangesNothing() throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofMinutes(45));
        provider.existingRefunds = List.of(new ProviderRefund("rfnd_dashboard", RefundStatus.PROCESSED, 1000L, null,
                Map.of()));

        cancel(driver.auth(), id, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(
                        "We're confirming an earlier refund for this booking. Please try again in a few minutes."));

        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat(provider.calls).isEmpty();
        assertThat(refundCount()).isZero();
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.HELD);
        assertThat(notificationTypes(DRIVER_EMAIL)).doesNotContain("BOOKING_CANCELLED");
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
    }

    @Test
    void refundsAreTaggedWithTheBookingSoAnOrphanCanBeIdentified() throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(2));

        cancel(driver.auth(), id, null).andExpect(status().isOk());

        assertThat(provider.notes).containsExactly(Map.of("bookingId", String.valueOf(id)));
    }

    @Test
    void anUnreachableGatewayWhileCheckingLeavesAFailedRefundForTheRetryJob() throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(2));
        provider.failFetch = true;

        cancel(driver.auth(), id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(provider.calls).isEmpty(); // not asking for a refund it could not rule out having made
        assertThat(jdbc.queryForObject("select status from refunds", String.class)).isEqualTo("FAILED");
    }

    // ---- telling the driver about the refund -----------------------------------------------------------------

    @Test
    void aRefundTheProviderRefusedIsPromisedAsProcessedLaterAndAnnouncedOnceTheRetryGetsItThrough() throws Exception {
        configureListing(CancellationPolicy.MODERATE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(30).plusMinutes(1));
        provider.failures.set(1);

        cancel(driver.auth(), id, null).andExpect(status().isOk());

        assertThat(emails.lastTo(DRIVER_EMAIL).textBody())
                .contains("Your refund of ₹60.00 will be processed to your original payment method.")
                .doesNotContain("on its way");
        assertThat(jdbc.queryForObject("select body from notifications where type = 'BOOKING_CANCELLED' "
                + "and link = ?", String.class, "/driver/bookings/" + id))
                .contains("Your refund of ₹60.00 will be processed to your original payment method.");
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED");
        emails.clear();

        jobs.retryFailedRefunds();

        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED", "BOOKING_REFUNDED");
        assertThat(jdbc.queryForObject("select body from notifications where type = 'BOOKING_REFUNDED'",
                String.class)).contains("₹60.00");
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Refund issued – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody())
                .contains("Your refund of ₹60.00 for the cancelled booking")
                .doesNotContain("refunded in full");

        jobs.retryFailedRefunds(); // nothing left to retry
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED", "BOOKING_REFUNDED");
    }

    @Test
    void aRefundTheProviderLaterReportsFailedAndThenProcessedIsAnnouncedOnceHoweverOftenTheWebhookRepeats()
            throws Exception {
        configureListing(CancellationPolicy.FLEXIBLE, true);
        long id = paid(10);
        startsIn(id, Duration.ofHours(2));
        cancel(driver.auth(), id, null).andExpect(status().isOk());
        String providerRefund = jdbc.queryForObject("select provider_refund_id from refunds", String.class);
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED");

        assertThat(refundService.applyProviderStatus(providerRefund, RefundStatus.FAILED, "bank said no")).isTrue();
        assertThat(refundService.applyProviderStatus(providerRefund, RefundStatus.FAILED, "bank said no")).isTrue();
        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED");
        assertThat(refundService.applyProviderStatus(providerRefund, RefundStatus.PROCESSED, null)).isTrue();
        assertThat(refundService.applyProviderStatus(providerRefund, RefundStatus.PROCESSED, null)).isTrue();

        assertThat(notificationTypes(DRIVER_EMAIL)).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED", "BOOKING_REFUNDED");
    }

    // ---- not cancellable ------------------------------------------------------------------------------------

    @Test
    void startedAndFinishedBookingsCannotBeCancelledByTheDriver() throws Exception {
        long id = paid(10);

        jdbc.update("update bookings set status = 'ACTIVE' where id = ?", id);
        preview(driver.auth(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.reason").value("Bookings can't be cancelled once they've started"))
                .andExpect(jsonPath("$.policy").doesNotExist())
                .andExpect(jsonPath("$.refundPercent").value(0))
                .andExpect(jsonPath("$.refundAmount").value(0.0));
        cancel(driver.auth(), id, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));

        jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", id);
        startsIn(id, Duration.ofMinutes(-10)); // still CONFIRMED but already past its start
        preview(driver.auth(), id).andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.reason").value("Bookings can't be cancelled once they've started"));
        cancel(driver.auth(), id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));

        for (String closed : List.of("COMPLETED", "CANCELLED", "REJECTED", "EXPIRED")) {
            jdbc.update("update bookings set status = ? where id = ?", closed, id);
            String word = closed.equals("REJECTED") ? "declined" : closed.toLowerCase();
            preview(driver.auth(), id).andExpect(jsonPath("$.cancellable").value(false))
                    .andExpect(jsonPath("$.reason").value("This booking is already " + word));
            cancel(driver.auth(), id, null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"))
                    .andExpect(jsonPath("$.detail").value("This booking is already " + word));
        }
        assertThat(provider.calls).isEmpty();
        assertThat(refundCount()).isZero();
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
    }

    @Test
    void cancellingTwiceIsRefusedTheSecondTime() throws Exception {
        long id = paid(10);

        cancel(driver.auth(), id, null).andExpect(status().isOk());
        cancel(driver.auth(), id, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));

        assertThat(provider.calls).hasSize(1);
        assertThat(refundCount()).isEqualTo(1);
    }

    @Test
    void anotherDriversBookingIsNotFoundAndOwnersCannotUseTheDriverEndpoints() throws Exception {
        long id = paid(10);

        preview(other.auth(), id).andExpect(status().isNotFound());
        cancel(other.auth(), id, null).andExpect(status().isNotFound());
        preview(driver.auth(), 999_999L).andExpect(status().isNotFound());
        cancel(driver.auth(), 999_999L, null).andExpect(status().isNotFound());
        preview(ownerAuth, id).andExpect(status().isForbidden());
        cancel(ownerAuth, id, null).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel")).andExpect(status().isUnauthorized());

        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void theReasonIsOptionalTrimmedAndLimitedTo300Characters() throws Exception {
        long first = paid(10);
        long second = paid(14);
        long third = paid(18);

        cancel(driver.auth(), first, "{\"reason\":\"" + "x".repeat(301) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(booking(first)).containsEntry("status", "CONFIRMED");

        cancel(driver.auth(), first, "{\"reason\":\"" + "x".repeat(300) + "\"}").andExpect(status().isOk());
        cancel(driver.auth(), second, "{\"reason\":\"   \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").doesNotExist());
        cancel(driver.auth(), third, "{\"reason\":\"  Plans changed  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("Plans changed"));
    }

    @Test
    void concurrentCancellationsRefundExactlyOnce() throws Exception {
        long id = paid(10);
        startsIn(id, Duration.ofHours(30).plusMinutes(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Integer> attempt = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return cancel(driver.auth(), id, null).andReturn().getResponse().getStatus();
            };
            Future<Integer> a = pool.submit(attempt);
            Future<Integer> b = pool.submit(attempt);
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(refundCount()).isEqualTo(1);
        assertThat(provider.calls).hasSize(1);
        assertThat(subjects(DRIVER_EMAIL)).hasSize(1);
    }

    // ---- owner ----------------------------------------------------------------------------------------------

    @Test
    void ownerCancellationRefundsTheDriverInFullAndReversesTheEarning() throws Exception {
        configureListing(CancellationPolicy.STRICT, true); // the policy never limits the owner's side
        long id = paid(10);
        startsIn(id, Duration.ofHours(5));

        ownerCancel(ownerAuth, id, "{\"reason\":\"Gate under repair\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Map<String, Object> row = booking(id);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "OWNER")
                .containsEntry("cancel_reason", "Gate under repair");
        assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("67.08");
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat(provider.calls).extracting(RecordingPaymentProvider.Call::paise).containsExactly(6708L);
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Your booking was cancelled by the owner – ParkEase");
        assertThat(emails.lastTo(DRIVER_EMAIL).textBody())
                .contains("Gate under repair", "Your full refund of ₹67.08 will be processed to your original payment method.");
        assertThat(notificationTypes(DRIVER_EMAIL)).endsWith("BOOKING_CANCELLED").doesNotContain("BOOKING_REFUNDED");
        assertThat(emails.sentTo(OWNER_EMAIL)).isEmpty();
        assertThat(jdbc.queryForList("select actor from booking_events where booking_id = ? and from_status = 'CONFIRMED' and to_status = 'CANCELLED'",
                String.class, id)).containsExactly("OWNER");
        // The slot is free again.
        Instant start = tomorrowAt(10);
        jdbc.update("update bookings set start_time = ?, end_time = ? where id = ?", Timestamp.from(start),
                Timestamp.from(start.plusSeconds(7200)), id);
        reserveOk(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(7200));
    }

    @Test
    void anOwnerCancellationStandsWhenTheProviderRefusesTheRefundAndTheRefundIsLeftForRetry() throws Exception {
        long id = paid(10);
        startsIn(id, Duration.ofHours(5));
        provider.failures.set(1);

        ownerCancel(ownerAuth, id, "{\"reason\":\"Gate under repair\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(booking(id)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "OWNER");
        assertThat(jdbc.queryForObject("select status from refunds", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select amount from refunds", BigDecimal.class)).isEqualByComparingTo("67.08");
        assertThat(jdbc.queryForObject("select attempts from refunds", Integer.class)).isEqualTo(1); // retryable
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat(earning(id).getStatus()).isEqualTo(EarningStatus.REVERSED);
        assertThat(subjects(DRIVER_EMAIL)).containsExactly("Your booking was cancelled by the owner – ParkEase");
        assertThat(notificationTypes(DRIVER_EMAIL)).endsWith("BOOKING_CANCELLED");
    }

    @Test
    void ownerCancellationNeedsAReasonAndOwnershipAndAConfirmedBookingThatHasNotStarted() throws Exception {
        long id = paid(10);

        ownerCancel(ownerAuth, id, "{\"reason\":\"  \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        ownerCancel(ownerAuth, id, "{}").andExpect(status().isBadRequest());
        ownerCancel(ownerAuth, id, null).andExpect(status().isBadRequest());
        ownerCancel(ownerAuth, id, "{\"reason\":\"" + "x".repeat(301) + "\"}").andExpect(status().isBadRequest());
        ownerCancel(otherOwnerAuth, id, "{\"reason\":\"Mine now\"}").andExpect(status().isNotFound());
        ownerCancel(ownerAuth, 999_999L, "{\"reason\":\"No such\"}").andExpect(status().isNotFound());
        ownerCancel(driver.auth(), id, "{\"reason\":\"Not allowed\"}").andExpect(status().isForbidden());
        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
        assertThat(provider.calls).isEmpty();

        startsIn(id, Duration.ofMinutes(-5)); // CONFIRMED but past its start
        ownerCancel(ownerAuth, id, "{\"reason\":\"Too late\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
        startsIn(id, Duration.ofHours(5));
        jdbc.update("update bookings set status = 'ACTIVE' where id = ?", id);
        ownerCancel(ownerAuth, id, "{\"reason\":\"Too late\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
        jdbc.update("update bookings set status = 'AWAITING_APPROVAL' where id = ?", id);
        ownerCancel(ownerAuth, id, "{\"reason\":\"Decline it instead\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail").value(not(containsString("AWAITING_APPROVAL"))))
                .andExpect(jsonPath("$.detail").value(containsString("awaiting approval")));
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", id);
        ownerCancel(ownerAuth, id, "{\"reason\":\"Again\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));

        assertThat(provider.calls).isEmpty();
        assertThat(refundCount()).isZero();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
