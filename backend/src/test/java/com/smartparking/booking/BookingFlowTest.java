package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.orderId;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserve;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.BookingApiSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@CommittedIntegrationTest
class BookingFlowTest {

    private static final String OWNER_EMAIL = "bf-owner@example.com";
    private static final String DRIVER_EMAIL = "bf-driver@example.com";
    private static final String OTHER_DRIVER_EMAIL = "bf-driver2@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired ParkingSlotRepository slots;
    @Autowired BookingRepository bookings;
    @Autowired OwnerEarningRepository earnings;
    @Autowired RecordingEmailSender emails;

    private String ownerAuth;
    private Long listingId;
    private Long slotId;
    private Driver driver;
    private Driver other;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Booking Spot", 18.5204, 73.8567, 30);
        slotId = slots.findByListingIdOrderByLabelAsc(listingId).get(0).getId();
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        other = driverWithVehicle(mvc, OTHER_DRIVER_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    private static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }

    private static void assertAbout(Instant actual, Duration fromNow) {
        assertThat(Duration.between(Instant.now().plus(fromNow), actual).abs()).isLessThan(Duration.ofSeconds(30));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void expireHold(long bookingId) {
        tx.executeWithoutResult(s -> {
            Booking b = bookings.findById(bookingId).orElseThrow();
            b.setHoldExpiresAt(Instant.now().minusSeconds(60));
            bookings.saveAndFlush(b);
            bookings.expireStaleHolds(List.of(slotId), Instant.now());
        });
    }

    @Test
    void reserveThenPayConfirmsAutoApproveBookingWithInvoiceEarningAndEmails() throws Exception {
        Instant start = tomorrowAt(10);
        String checkout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200));

        assertThat((String) JsonPath.read(checkout, "$.booking.status")).isEqualTo("PENDING_PAYMENT");
        assertAbout(instant(checkout, "$.booking.holdExpiresAt"), Duration.ofMinutes(10));
        assertThat((String) JsonPath.read(checkout, "$.booking.bookingCode")).matches("PK-[A-HJKMNP-Z2-9]{6}");
        assertThat((String) JsonPath.read(checkout, "$.booking.slotLabel")).isEqualTo("A-01");
        assertThat((Double) JsonPath.read(checkout, "$.booking.totalAmount")).isEqualTo(67.08);
        assertThat((String) JsonPath.read(checkout, "$.payment.provider")).isEqualTo("MOCK");
        assertThat((Integer) JsonPath.read(checkout, "$.payment.amount")).isEqualTo(6708);
        assertThat((String) JsonPath.read(checkout, "$.payment.currency")).isEqualTo("INR");
        assertThat((String) JsonPath.read(checkout, "$.payment.name")).isEqualTo("ParkEase");
        assertThat((String) JsonPath.read(checkout, "$.payment.description")).isEqualTo("Booking Spot");
        assertThat((String) JsonPath.read(checkout, "$.payment.prefill.email")).isEqualTo(DRIVER_EMAIL);
        assertThat((Object) JsonPath.read(checkout, "$.payment.keyId")).isNull();
        long id = bookingId(checkout);
        assertThat(count("select count(*) from payments where booking_id = ? and status = 'CREATED'", id)).isEqualTo(1);
        emails.clear();

        String pay = mockPay(mvc, driver.auth(), id);
        String paid = verifyWith(pay, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(paid, "$.status")).isEqualTo("CONFIRMED");
        assertThat((String) JsonPath.read(paid, "$.invoiceNumber")).matches("INV-\\d{4}-\\d{6}");
        assertThat((String) JsonPath.read(paid, "$.paymentStatus")).isEqualTo("CAPTURED");
        assertThat((Object) JsonPath.read(paid, "$.holdExpiresAt")).isNull();
        assertThat((String) JsonPath.read(paid, "$.ownerFirstName")).isEqualTo("Ravi");
        assertThat((List<?>) JsonPath.read(paid, "$.events")).hasSize(2);
        assertThat((String) JsonPath.read(paid, "$.events[1].toStatus")).isEqualTo("CONFIRMED");
        OwnerEarning earning = earnings.findByBookingId(id).orElseThrow();
        assertThat(earning.getStatus()).isEqualTo(EarningStatus.HELD);
        assertThat(earning.getGross()).isEqualByComparingTo("60.00");
        assertThat(earning.getCommission()).isEqualByComparingTo("6.00");
        assertThat(earning.getNet()).isEqualByComparingTo("60.00");
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking confirmed – ParkEase");
        assertThat(emails.sentTo(OWNER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("New booking – ParkEase");
        String driverText = emails.lastTo(DRIVER_EMAIL).textBody();
        assertThat(driverText).contains((String) JsonPath.read(paid, "$.bookingCode"), "Booking Spot", "A-01", "67.08",
                "/driver/bookings/" + id);

        // Verifying again is idempotent: no second invoice, earning or email.
        String again = verifyWith(pay, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.status")).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(count("select count(*) from owner_earnings")).isEqualTo(1);
        assertThat(count("select count(*) from booking_events where booking_id = ?", id)).isEqualTo(2);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
        assertThat(emails.sentTo(OWNER_EMAIL)).hasSize(1);
    }

    private ResultActions verifyWith(String pay, long bookingId) throws Exception {
        return verify(mvc, driver.auth(), bookingId, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"));
    }

    @Test
    void secondMockPayAfterCaptureAlsoReturnsOkWithoutSideEffects() throws Exception {
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        payOk(mvc, driver.auth(), id);
        emails.clear();

        String replay = payOk(mvc, driver.auth(), id);

        assertThat((String) JsonPath.read(replay, "$.status")).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
    }

    @Test
    void manualApprovalListingMovesToAwaitingApprovalAndAsksOwnerToApprove() throws Exception {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setAutoApprove(false);
        listings.saveAndFlush(listing);
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        emails.clear();

        String paid = payOk(mvc, driver.auth(), id);

        assertThat((String) JsonPath.read(paid, "$.status")).isEqualTo("AWAITING_APPROVAL");
        assertAbout(instant(paid, "$.approvalDeadline"), Duration.ofHours(2));
        assertThat((Object) JsonPath.read(paid, "$.confirmedAt")).isNull();
        assertThat((Boolean) JsonPath.read(paid, "$.autoApprove")).isFalse();
        assertThat(emails.sentTo(DRIVER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Booking request sent – ParkEase");
        assertThat(emails.sentTo(OWNER_EMAIL)).extracting(EmailMessage::subject)
                .containsExactly("Approve a booking request – ParkEase");
        assertThat(emails.lastTo(OWNER_EMAIL).textBody()).contains("/owner/bookings");
        assertThat(earnings.findByBookingId(id)).isPresent();
    }

    @Test
    void badSignatureOrWrongOrderIsRejected() throws Exception {
        Instant start = tomorrowAt(10);
        long first = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        Instant later = tomorrowAt(14);
        String secondCheckout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), later, later.plusSeconds(3600));
        long second = bookingId(secondCheckout);
        String firstOrder = orderId(checkoutJson(first));
        String firstPay = mockPay(mvc, driver.auth(), first);
        String secondPay = mockPay(mvc, driver.auth(), second);

        verify(mvc, driver.auth(), first, firstOrder, JsonPath.read(firstPay, "$.paymentId"), "deadbeef")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        // A valid payment for the second booking cannot confirm the first one.
        verify(mvc, driver.auth(), first, JsonPath.read(secondPay, "$.orderId"),
                JsonPath.read(secondPay, "$.paymentId"), JsonPath.read(secondPay, "$.signature"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        // Someone else's booking is a 404.
        verify(mvc, other.auth(), first, firstOrder, JsonPath.read(firstPay, "$.paymentId"),
                JsonPath.read(firstPay, "$.signature"))
                .andExpect(status().isNotFound());

        assertThat(count("select count(*) from bookings where status = 'PENDING_PAYMENT'")).isEqualTo(2);
        assertThat(count("select count(*) from invoices")).isZero();
    }

    private String checkoutJson(long bookingId) throws Exception {
        return mvc.perform(get("/api/v1/bookings/" + bookingId + "/checkout")
                        .header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void overlappingReservationIsRejectedUntilTheFirstHoldLapses() throws Exception {
        Instant start = tomorrowAt(10);
        long first = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));

        reserve(mvc, other.auth(), listingId, other.vehicleId(), start.plusSeconds(3600), start.plusSeconds(10800))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("FULLY_BOOKED"));

        expireHold(first);
        reserve(mvc, other.auth(), listingId, other.vehicleId(), start.plusSeconds(3600), start.plusSeconds(10800))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.booking.status").value("PENDING_PAYMENT"));
        assertThat(jdbc.queryForObject("select status from bookings where id = ?", String.class, first))
                .isEqualTo("EXPIRED");
    }

    @Test
    void reservationOnAnExpiredButUnsweptHoldTakesTheSlot() throws Exception {
        Instant start = tomorrowAt(10);
        long first = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        // Lapse the hold without running the expiry sweep: the allocator must free the slot itself.
        Booking b = bookings.findById(first).orElseThrow();
        b.setHoldExpiresAt(Instant.now().minusSeconds(60));
        bookings.saveAndFlush(b);

        reserve(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select status from bookings where id = ?", String.class, first))
                .isEqualTo("EXPIRED");
    }

    @Test
    void fourthUnpaidHoldIsRejected() throws Exception {
        for (int i = 0; i < 3; i++) {
            Instant start = tomorrowAt(8 + 3 * i);
            reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600));
        }
        Instant start = tomorrowAt(20);
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOO_MANY_HOLDS"));
        // Another driver is not affected.
        reserve(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidRequestsAreRejectedWithTheDocumentedCodes() throws Exception {
        Instant start = tomorrowAt(10);
        reserve(mvc, ownerAuth, listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        reserve(mvc, driver.auth(), listingId, other.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isNotFound());
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(1800))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        reserve(mvc, driver.auth(), 999_999L, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isNotFound());
        Long draft = ListingTestSupport.createListing(mvc, ownerAuth, ListingTestSupport.puneCityId(cities));
        reserve(mvc, driver.auth(), draft, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_UNAVAILABLE"));
        mvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, driver.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("select count(*) from bookings")).isZero();
    }

    @Test
    void closedListingReportsTheReason() throws Exception {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setOpen24x7(false);
        listings.saveAndFlush(listing);
        Instant start = tomorrowAt(10);
        reserve(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("CLOSED"));
    }

    @Test
    void latePaymentRevivesTheBookingWhenTheSlotIsStillFree() throws Exception {
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
        String pay = mockPay(mvc, driver.auth(), id);
        expireHold(id);
        assertThat(jdbc.queryForObject("select status from bookings where id = ?", String.class, id))
                .isEqualTo("EXPIRED");
        emails.clear();

        String paid = verify(mvc, driver.auth(), id, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.events[*].note", hasItem("Payment received after hold expired")))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(paid, "$.invoiceNumber")).startsWith("INV-");
        assertThat((String) JsonPath.read(paid, "$.events[1].fromStatus")).isEqualTo("EXPIRED");
        assertThat((Object) JsonPath.read(paid, "$.holdExpiresAt")).isNull();
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
        assertThat(count("select count(*) from refunds")).isZero();
    }

    @Test
    void latePaymentIsCancelledAndFullyRefundedWhenTheSlotWasTaken() throws Exception {
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(7200)));
        String pay = mockPay(mvc, driver.auth(), id);
        expireHold(id);
        // Someone else grabs the slot while the first driver's payment is in flight.
        reserveOk(mvc, other.auth(), listingId, other.vehicleId(), start, start.plusSeconds(7200));
        emails.clear();

        verify(mvc, driver.auth(), id, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("SYSTEM"))
                .andExpect(jsonPath("$.cancelReason").value("Slot was taken before the payment arrived"))
                .andExpect(jsonPath("$.refundAmount").value(67.08))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.invoiceNumber").value(nullValue()));

        Map<String, Object> refund = jdbc.queryForMap(
                "select r.status, r.amount from refunds r join payments p on p.id = r.payment_id where p.booking_id = ?", id);
        assertThat(refund.get("status")).isEqualTo("PROCESSED");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("67.08");
        assertThat(count("select count(*) from invoices")).isZero();
        assertThat(count("select count(*) from owner_earnings")).isZero();
        // The other driver's hold is untouched.
        assertThat(count("select count(*) from bookings where status = 'PENDING_PAYMENT'")).isEqualTo(1);
    }

    @Test
    void checkoutIsReadableWhileTheHoldIsLiveThenGoneAfterExpiry() throws Exception {
        Instant start = tomorrowAt(10);
        String checkout = reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600));
        long id = bookingId(checkout);
        mvc.perform(get("/api/v1/bookings/" + id + "/checkout").header(HttpHeaders.AUTHORIZATION, other.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + id + "/checkout").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRIVERS_ONLY"));
        mvc.perform(get("/api/v1/bookings/" + id + "/checkout").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.orderId").value(orderId(checkout)));

        Booking b = bookings.findById(id).orElseThrow();
        b.setHoldExpiresAt(Instant.now().minusSeconds(1));
        bookings.saveAndFlush(b);
        mvc.perform(get("/api/v1/bookings/" + id + "/checkout").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        b.setHoldExpiresAt(Instant.now().plusSeconds(600));
        bookings.saveAndFlush(b);

        payOk(mvc, driver.auth(), id);
        mvc.perform(get("/api/v1/bookings/" + id + "/checkout").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void mockPayRequiresTheDriversOwnBooking() throws Exception {
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        mvc.perform(post("/api/v1/payments/mock/pay")
                        .header(HttpHeaders.AUTHORIZATION, other.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + id + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentVerificationsConfirmOnlyOnce() throws Exception {
        Instant start = tomorrowAt(10);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        String pay = mockPay(mvc, driver.auth(), id);
        emails.clear();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 4).<Callable<Integer>>mapToObj(i -> () ->
                    verify(mvc, driver.auth(), id, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                            JsonPath.read(pay, "$.signature")).andReturn().getResponse().getStatus()).toList();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from invoices")).isEqualTo(1);
        assertThat(count("select count(*) from owner_earnings")).isEqualTo(1);
        assertThat(emails.sentTo(DRIVER_EMAIL)).hasSize(1);
        assertThat(emails.sentTo(OWNER_EMAIL)).hasSize(1);
    }
}
