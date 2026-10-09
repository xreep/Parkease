package com.smartparking.booking;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.mockPay;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static com.smartparking.support.BookingApiSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.invoice.InvoiceService;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.payment.PaymentService;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment confirmation locks the payment row before the booking row (and before the stale-hold sweep), the same order
 * as the expire job and the owner decisions, so a late payment and the background jobs cannot deadlock.
 */
@CommittedIntegrationTest
class ConfirmLockOrderTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired BookingJobs jobs;
    @Autowired PaymentService paymentService;
    @MockitoSpyBean InvoiceService invoices;

    private Long listingId;
    private Driver driver;

    private int deadlocksBefore;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        deadlocksBefore = paymentService.deadlockRetries();
        String ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "lo-owner@example.com", "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Lock Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, "lo-driver@example.com");
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    /** An unpaid hold that has already lapsed (but was not swept), with a ready-to-send payment. */
    private record LapsedHold(long id, String pay) {
    }

    private LapsedHold lapsedHold(int startHour) throws Exception {
        Instant start = tomorrowAt(startHour);
        long id = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start, start.plusSeconds(3600)));
        String pay = mockPay(mvc, driver.auth(), id);
        jdbc.update("update bookings set hold_expires_at = now() - interval '1 minute' where id = ?", id);
        return new LapsedHold(id, pay);
    }

    private ResultActions verifyLate(LapsedHold hold) throws Exception {
        return verify(mvc, driver.auth(), hold.id(), JsonPath.read(hold.pay(), "$.orderId"),
                JsonPath.read(hold.pay(), "$.paymentId"), JsonPath.read(hold.pay(), "$.signature"));
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, id);
    }

    @Test
    void lateVerifyWaitsOnThePaymentRowWithoutHavingTouchedTheBookingRowYet() throws Exception {
        LapsedHold hold = lapsedHold(10);
        CountDownLatch paymentLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Plays the expire job: payment row first, then (once the verify is waiting) the booking row. If the
            // verify had already locked the booking (e.g. through the stale-hold sweep) this would deadlock.
            Future<?> job = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("select id from payments where booking_id = ? for update", hold.id());
                paymentLocked.countDown();
                awaitBlockedStatement();
                jdbc.execute("set local lock_timeout = '5s'");
                jdbc.queryForList("select id from bookings where id = ? for update", hold.id());
            }));
            assertThat(paymentLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> verifying = pool.submit(() -> verifyLate(hold).andReturn());

            job.get(30, TimeUnit.SECONDS);
            MvcResult result = verifying.get(30, TimeUnit.SECONDS);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.status"))
                    .isEqualTo("CONFIRMED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(hold.id())).isEqualTo("CONFIRMED");
        // Not merely rescued by the deadlock retry: there was no deadlock to begin with.
        assertThat(paymentService.deadlockRetries()).isEqualTo(deadlocksBefore);
    }

    /** Waits until a statement is blocked on a row lock, i.e. the verify has reached the payment lock. */
    private void awaitBlockedStatement() {
        try {
            for (int i = 0; i < 100; i++) {
                Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity "
                        + "where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
                if (blocked != null && blocked > 0) {
                    return;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("The verify request never blocked on the payment row");
    }

    @Test
    void expireJobAndLateVerifyRacingOnTheSameBookingBothCompleteCleanly() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                LapsedHold hold = lapsedHold(6 + 2 * round);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> job = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    jobs.expireHolds();
                    return null;
                });
                Future<MvcResult> verifying = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return verifyLate(hold).andReturn();
                });

                job.get(30, TimeUnit.SECONDS); // no deadlock or other exception escapes the job
                MvcResult result = verifying.get(30, TimeUnit.SECONDS);

                // Whichever side won: the late payment ends up confirmed, never lost, never a server error.
                assertThat(result.getResponse().getStatus()).as("round %d", round).isEqualTo(200);
                assertThat(status(hold.id())).as("round %d", round).isEqualTo("CONFIRMED");
                assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class,
                        hold.id())).isEqualTo("CAPTURED");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(paymentService.deadlockRetries()).isEqualTo(deadlocksBefore);
    }

    private static DeadlockLoserDataAccessException deadlock() {
        return new DeadlockLoserDataAccessException("deadlock detected", new SQLException("deadlock detected", "40P01"));
    }

    @Test
    void ifPostgresAbortsTheConfirmationAsADeadlockVictimItIsRepeatedOnce() throws Exception {
        LapsedHold hold = lapsedHold(10);
        // Raised after the payment and booking were already modified: the whole attempt must roll back and redo.
        doThrow(deadlock()).doCallRealMethod().when(invoices).issue(any(), any());

        verifyLate(hold).andReturn();

        assertThat(status(hold.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from owner_earnings", Integer.class)).isEqualTo(1);
    }

    @Test
    void aSecondDeadlockIsNotHiddenAndLeavesTheBookingUntouched() throws Exception {
        LapsedHold hold = lapsedHold(10);
        doThrow(deadlock()).doThrow(deadlock()).doCallRealMethod().when(invoices).issue(any(), any());

        int httpStatus = verifyLate(hold).andReturn().getResponse().getStatus();

        assertThat(httpStatus).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("select status from payments where booking_id = ?", String.class, hold.id()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from invoices", Integer.class)).isZero();
        // doCallRealMethod stub only covers later calls; a plain re-verify now succeeds.
        verifyLate(hold).andReturn();
        assertThat(status(hold.id())).isEqualTo("CONFIRMED");
    }
}
