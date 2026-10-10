package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.booking.CancellationPolicyCalculator;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.pricing.PricingService;
import com.smartparking.pricing.Quote;
import com.smartparking.settings.PlatformSettings;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the whole demo data set once (committed, in a clean test database) and checks it from every angle: sizes,
 * idempotence, determinism, and the money and booking rules the platform enforces on live data.
 */
@CommittedIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoActivitySeederTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PASSWORD = "Demo@1234";
    private static final String LIVE = "('AWAITING_APPROVAL','CONFIRMED','ACTIVE')";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository ownerProfiles;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired ListingPhotoRepository photos;
    @Autowired ParkingSlotRepository slots;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired FileStorage storage;
    @Autowired PricingService pricing;
    @Autowired PlatformSettings settings;
    @Autowired PlatformTransactionManager txManager;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.smartparking.booking.BookingJobs jobs;

    Clock clock;
    Instant now;
    long seedMillis;

    @BeforeAll
    void seedEverything() {
        reset();
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        clock = Clock.fixed(now, ZoneOffset.UTC);
        long started = System.nanoTime();
        assertThat(seedAll()).isTrue();
        seedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    @AfterAll
    void cleanUp() {
        reset();
    }

    private void reset() {
        DatabaseCleaner.clean(jdbc);
        jdbc.update("delete from platform_settings where key = ?", DemoActivitySeeder.MARKER_KEY);
    }

    /** Accounts, listings, then the activity; returns whether the activity seeder wrote anything. */
    private boolean seedAll() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return Boolean.TRUE.equals(tx.execute(status -> {
            new DemoAccountSeeder(users, ownerProfiles, passwordEncoder, clock, PASSWORD).seed();
            new DemoListingSeeder(users, ownerProfiles, cities, listings, photos, slots, rules, passwordEncoder,
                    storage, clock, PASSWORD).seed();
            em.flush();
            return activitySeeder().seed();
        }));
    }

    private DemoActivitySeeder activitySeeder() {
        return new DemoActivitySeeder(jdbc, em, txManager, pricing, settings, passwordEncoder, storage, clock, environment, PASSWORD);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ---- shape ----------------------------------------------------------------------------------------------

    @Test
    void createsTheExpectedPeopleAndVehicles() {
        assertThat(count("select count(*) from users where role = 'OWNER'")).isBetween(28L, 36L);
        assertThat(count("select count(*) from owner_profiles where verification_status = 'PENDING'"))
                .isBetween(3L, 5L);
        assertThat(count("select count(*) from users where role = 'DRIVER'")).isBetween(48L, 56L);
        assertThat(count("select count(*) from users where role = 'ADMIN'")).isEqualTo(1);

        assertThat(count("select count(*) from users u where role = 'DRIVER'"
                + " and (select count(*) from vehicles v where v.user_id = u.id) not between 1 and 2")).isZero();
        assertThat(count("select count(*) from vehicles where is_default")).isEqualTo(
                count("select count(distinct user_id) from vehicles"));
        // The demo logins keep working with the demo password.
        assertThat(count("select count(*) from users where email in "
                + "('admin@parkease.dev','owner@parkease.dev','driver@parkease.dev')")).isEqualTo(3);
    }

    @Test
    void createsBookingsAcrossEveryStatusAndTheWholeTimeline() {
        long total = count("select count(*) from bookings");
        assertThat(total).isBetween(480L, 540L);

        Map<String, Long> byStatus = statusCounts();
        assertThat(byStatus).containsKeys("COMPLETED", "CONFIRMED", "ACTIVE", "AWAITING_APPROVAL", "CANCELLED",
                "REJECTED", "EXPIRED");
        assertThat(byStatus.get("COMPLETED")).isGreaterThan(total / 2);
        assertThat(byStatus.get("ACTIVE")).isGreaterThanOrEqualTo(2);
        assertThat(byStatus.get("AWAITING_APPROVAL")).isGreaterThanOrEqualTo(5);
        assertThat(count("select count(*) from bookings where cancelled_by = 'DRIVER'")).isGreaterThan(10);
        assertThat(count("select count(*) from bookings where cancelled_by = 'OWNER'")).isGreaterThan(0);
        assertThat(count("select count(*) from bookings where cancelled_by = 'ADMIN'")).isGreaterThan(0);
        assertThat(count("select count(*) from bookings where status = 'REJECTED' and cancelled_by = 'OWNER'"))
                .isGreaterThan(0);
        assertThat(count("select count(*) from bookings where status = 'REJECTED' and cancelled_by = 'SYSTEM'"))
                .isGreaterThan(0);

        Instant earliest = instant("select min(start_time) from bookings");
        Instant latest = instant("select max(start_time) from bookings");
        assertThat(earliest).isAfterOrEqualTo(now.minus(Duration.ofDays(91)));
        assertThat(earliest).isBefore(now.minus(Duration.ofDays(80)));
        assertThat(latest).isBeforeOrEqualTo(now.plus(Duration.ofDays(15)));
        assertThat(latest).isAfter(now.plus(Duration.ofDays(10)));
        // Everything is created before it starts, and never in the future.
        assertThat(count("select count(*) from bookings where created_at > start_time or created_at > ?",
                Timestamp.from(now))).isZero();
    }

    @Test
    void bookingStatesMatchTheClock() {
        Timestamp ts = Timestamp.from(now);
        assertThat(count("select count(*) from bookings where status = 'ACTIVE' and not (start_time <= ? and end_time > ?)",
                ts, ts)).isZero();
        assertThat(count("select count(*) from bookings where status = 'COMPLETED' and (end_time > ? or completed_at is null)",
                ts)).isZero();
        assertThat(count("select count(*) from bookings where status = 'CONFIRMED' and start_time <= ?", ts)).isZero();
        assertThat(count("select count(*) from bookings where status = 'AWAITING_APPROVAL'"
                + " and (approval_deadline is null or approval_deadline <= ? or start_time <= ?)", ts, ts)).isZero();
        // Only listings that need the owner's approval have requests waiting.
        assertThat(count("select count(*) from bookings b join parking_listings l on l.id = b.listing_id"
                + " where b.status = 'AWAITING_APPROVAL' and l.auto_approve")).isZero();
        assertThat(count("select count(*) from bookings where status = 'EXPIRED'"
                + " and (hold_expires_at is null or hold_expires_at > ?)", ts)).isZero();
        // The per-minute jobs have nothing to do on a fresh seed.
        assertThat(count("select count(*) from bookings where status = 'PENDING_PAYMENT'")).isZero();
    }

    @Test
    void demoLoginsHaveActivity() {
        Long driver = id("driver@parkease.dev");
        assertThat(count("select count(*) from bookings where driver_id = ?", driver)).isGreaterThanOrEqualTo(8);
        assertThat(count("select count(distinct status) from bookings where driver_id = ?", driver))
                .isGreaterThanOrEqualTo(5);
        assertThat(count("select count(*) from bookings where driver_id = ? and status in " + LIVE
                + " and end_time > ?", driver, Timestamp.from(now))).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from reviews where driver_id = ?", driver)).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from notifications where user_id = ?", driver)).isGreaterThanOrEqualTo(5);

        Long owner = id("owner@parkease.dev");
        assertThat(count("select count(*) from bookings b join parking_listings l on l.id = b.listing_id"
                + " where l.owner_id = ?", owner)).isGreaterThanOrEqualTo(40);
        assertThat(count("select count(*) from bookings b join parking_listings l on l.id = b.listing_id"
                + " where l.owner_id = ? and b.status = 'AWAITING_APPROVAL'", owner)).isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from owner_earnings where owner_id = ? and status = 'PAID'", owner))
                .isGreaterThan(0);
        assertThat(count("select count(*) from owner_earnings where owner_id = ? and status = 'PENDING_PAYOUT'", owner))
                .isGreaterThan(0);
        assertThat(count("select count(*) from notifications where user_id = ?", owner)).isGreaterThanOrEqualTo(5);
    }

    // ---- money ----------------------------------------------------------------------------------------------

    @Test
    void everyBookingIsPricedLikeTheLivePricingService() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.booking_code, b.start_time, b.end_time, b.pricing_mode, b.pricing_breakdown, b.base_amount,
                       b.platform_fee, b.gst_amount, b.total_amount, b.gst_percent,
                       l.price_per_hour, l.price_per_day, l.price_per_month
                from bookings b join parking_listings l on l.id = b.listing_id""");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            Quote q = pricing.quote((BigDecimal) r.get("price_per_hour"), (BigDecimal) r.get("price_per_day"),
                    (BigDecimal) r.get("price_per_month"), at(r.get("start_time")), at(r.get("end_time")));
            String code = (String) r.get("booking_code");
            assertThat(q.pricingMode().name()).as(code).isEqualTo(r.get("pricing_mode"));
            assertThat(q.breakdown()).as(code).isEqualTo(r.get("pricing_breakdown"));
            assertThat((BigDecimal) r.get("base_amount")).as(code).isEqualByComparingTo(q.baseAmount());
            assertThat((BigDecimal) r.get("platform_fee")).as(code).isEqualByComparingTo(q.platformFee());
            assertThat((BigDecimal) r.get("gst_amount")).as(code).isEqualByComparingTo(q.gstAmount());
            assertThat((BigDecimal) r.get("total_amount")).as(code).isEqualByComparingTo(q.totalAmount());
            assertThat((BigDecimal) r.get("gst_percent")).as(code).isEqualByComparingTo(q.gstPercent());
        }
    }

    @Test
    void paymentsRefundsAndInvoicesAgreeWithEachBooking() {
        // Every booking but the unpaid expired ones has a captured-then-maybe-refunded payment and an invoice.
        assertThat(count("select count(*) from payments")).isEqualTo(count("select count(*) from bookings"));
        assertThat(count("select count(*) from payments p join bookings b on b.id = p.booking_id"
                + " where p.amount <> b.total_amount or p.provider <> 'MOCK' or p.currency <> 'INR'")).isZero();
        assertThat(count("select count(*) from payments p join bookings b on b.id = p.booking_id"
                + " where (b.status = 'EXPIRED') <> (p.status = 'FAILED')")).isZero();
        assertThat(count("select count(*) from payments where status <> 'FAILED' and (provider_payment_id is null"
                + " or provider_payment_id not like 'pay\\_mock\\_%' or provider_order_id not like 'order\\_mock\\_%'"
                + " or captured_at is null)")).isZero();
        assertThat(count("select count(*) from invoices")).isEqualTo(
                count("select count(*) from bookings where status <> 'EXPIRED'"));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.booking_code, b.status as booking_status, b.refund_amount, p.amount, p.status as payment_status,
                       coalesce((select sum(r.amount) from refunds r where r.payment_id = p.id
                                 and r.status <> 'FAILED'), 0) as refunded,
                       (select count(*) from refunds r where r.payment_id = p.id and r.status <> 'PROCESSED') as unsettled
                from bookings b join payments p on p.booking_id = b.id""");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("booking_code");
            BigDecimal amount = (BigDecimal) r.get("amount");
            BigDecimal refunded = (BigDecimal) r.get("refunded");
            assertThat(refunded).as(code + " refunds within the payment").isLessThanOrEqualTo(amount);
            assertThat((BigDecimal) r.get("refund_amount")).as(code).isEqualByComparingTo(refunded);
            assertThat(((Number) r.get("unsettled")).longValue()).as(code).isZero();
            String expected = "EXPIRED".equals(r.get("booking_status")) ? "FAILED"
                    : refunded.signum() == 0 ? "CAPTURED"
                    : refunded.compareTo(amount) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
            assertThat(r.get("payment_status")).as(code).isEqualTo(expected);
        }
        assertThat(count("select count(*) from payments where status = 'REFUNDED'")).isGreaterThan(5);
        assertThat(count("select count(*) from payments where status = 'PARTIALLY_REFUNDED'")).isGreaterThan(5);
    }

    @Test
    void invoiceNumbersContinueTheSequenceInOrderOfIssue() {
        List<String> numbers = jdbc.queryForList(
                "select invoice_number from invoices order by issued_at, id", String.class);
        assertThat(numbers).doesNotHaveDuplicates().allMatch(n -> n.matches("INV-20\\d\\d-\\d{6}"));
        List<Long> serials = numbers.stream().map(n -> Long.parseLong(n.substring(n.length() - 6))).toList();
        assertThat(serials).isSorted();
        // The sequence has moved past the last number, so the next real invoice cannot collide.
        long next = count("select nextval('invoice_number_seq')");
        assertThat(next).isGreaterThan(serials.get(serials.size() - 1));
        assertThat(count("select count(*) from invoices i join payments p on p.id = i.payment_id"
                + " where p.booking_id <> i.booking_id")).isZero();
    }

    @Test
    void ownerEarningsFollowTheRefundRules() {
        assertThat(count("select count(*) from owner_earnings")).isEqualTo(
                count("select count(*) from bookings where status <> 'EXPIRED'"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.booking_code, b.status as booking_status, b.base_amount, b.platform_fee, b.listing_id,
                       e.gross, e.commission, e.net, e.status, e.payout_reference, e.paid_at, e.owner_id,
                       l.owner_id as listing_owner,
                       coalesce((select sum(r.amount) from refunds r join payments p on p.id = r.payment_id
                                 where p.booking_id = b.id and r.status <> 'FAILED'), 0) as refunded,
                       (select p.amount from payments p where p.booking_id = b.id) as paid,
                       exists (select 1 from disputes d where d.booking_id = b.id and d.status <> 'RESOLVED') as held
                from owner_earnings e join bookings b on b.id = e.booking_id
                join parking_listings l on l.id = b.listing_id""");
        Set<String> paidRefs = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("booking_code");
            BigDecimal gross = (BigDecimal) r.get("gross");
            BigDecimal refunded = (BigDecimal) r.get("refunded");
            String status = (String) r.get("status");
            String bookingStatus = (String) r.get("booking_status");
            assertThat(gross).as(code).isEqualByComparingTo((BigDecimal) r.get("base_amount"));
            assertThat((BigDecimal) r.get("commission")).as(code).isEqualByComparingTo((BigDecimal) r.get("platform_fee"));
            assertThat(r.get("owner_id")).as(code).isEqualTo(r.get("listing_owner"));

            boolean reversed = refunded.compareTo((BigDecimal) r.get("paid")) >= 0
                    || gross.subtract(refunded.min(gross)).signum() == 0;
            BigDecimal net = (BigDecimal) r.get("net");
            if (reversed) {
                assertThat(status).as(code).isEqualTo("REVERSED");
                assertThat(net).as(code).isEqualByComparingTo(BigDecimal.ZERO);
            } else {
                assertThat(status).as(code).isNotEqualTo("REVERSED");
                assertThat(net).as(code).isEqualByComparingTo(gross.subtract(refunded.min(gross)));
            }
            switch (status) {
                case "HELD" -> assertThat(bookingStatus).as(code).isIn("AWAITING_APPROVAL", "CONFIRMED", "ACTIVE");
                case "PENDING_PAYOUT" -> {
                    assertThat(bookingStatus).as(code).isIn("COMPLETED", "CANCELLED");
                    assertThat(r.get("payout_reference")).as(code).isNull();
                }
                case "PAID" -> {
                    assertThat(bookingStatus).as(code).isIn("COMPLETED", "CANCELLED");
                    assertThat((String) r.get("payout_reference")).as(code).startsWith("UTR");
                    assertThat(r.get("paid_at")).as(code).isNotNull();
                    assertThat(r.get("held")).as(code + " not paid while a dispute is open").isEqualTo(false);
                    paidRefs.add((String) r.get("payout_reference"));
                }
                default -> assertThat(status).isEqualTo("REVERSED");
            }
            if (!"HELD".equals(status) && !"PAID".equals(status) && !"REVERSED".equals(status)) {
                assertThat(bookingStatus).as(code).isNotIn("AWAITING_APPROVAL", "CONFIRMED", "ACTIVE");
            }
        }
        assertThat(rows.stream().map(r -> r.get("status")).distinct().toList())
                .containsExactlyInAnyOrder("HELD", "PENDING_PAYOUT", "PAID", "REVERSED");
        assertThat(paidRefs).hasSizeGreaterThan(10);
        // One reference never spans two owners.
        assertThat(count("select count(*) from (select payout_reference from owner_earnings"
                + " where payout_reference is not null group by payout_reference"
                + " having count(distinct owner_id) > 1) x")).isZero();
    }

    @Test
    void cancellationsRefundWhatThePolicySays() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.booking_code, b.cancelled_by, b.status, b.start_time, b.base_amount, b.total_amount,
                       b.refund_amount, l.cancellation_policy,
                       (select max(e.created_at) from booking_events e
                        where e.booking_id = b.id and e.to_status in ('CANCELLED','REJECTED')) as cancelled_at,
                       (select e.from_status from booking_events e where e.booking_id = b.id
                        and e.to_status = 'CANCELLED' limit 1) as cancelled_from
                from bookings b join parking_listings l on l.id = b.listing_id
                where b.status in ('CANCELLED','REJECTED')""");
        assertThat(rows).hasSizeGreaterThan(40);
        Map<String, Long> percents = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("booking_code");
            BigDecimal refund = (BigDecimal) r.get("refund_amount");
            if ("DRIVER".equals(r.get("cancelled_by")) && !"AWAITING_APPROVAL".equals(r.get("cancelled_from"))) {
                var expected = CancellationPolicyCalculator.preview(
                        CancellationPolicy.valueOf((String) r.get("cancellation_policy")), at(r.get("start_time")),
                        at(r.get("cancelled_at")), (BigDecimal) r.get("base_amount"));
                assertThat(refund).as(code).isEqualByComparingTo(expected.refundAmount());
                percents.merge(expected.percent() + "%", 1L, Long::sum);
            } else {
                // Owner, admin and system take the fee back too, and so does a request given up before the owner
                // answered: the driver gets everything.
                assertThat(refund).as(code).isEqualByComparingTo((BigDecimal) r.get("total_amount"));
            }
        }
        assertThat(percents.keySet()).contains("100%", "50%", "0%");
    }

    @Test
    void noTwoBookingsShareASlotAtTheSameTime() {
        assertThat(count("""
                select count(*) from bookings a join bookings b
                  on a.slot_id = b.slot_id and a.id < b.id
                 and tstzrange(a.start_time, a.end_time, '[)') && tstzrange(b.start_time, b.end_time, '[)')
                where a.status <> 'EXPIRED' and b.status <> 'EXPIRED'""")).isZero();
        // Slot, vehicle and listing belong together.
        assertThat(count("select count(*) from bookings b join parking_slots s on s.id = b.slot_id"
                + " where s.listing_id <> b.listing_id or s.vehicle_type <> b.vehicle_type or not s.active")).isZero();
        assertThat(count("select count(*) from bookings b join vehicles v on v.id = b.vehicle_id"
                + " where v.user_id <> b.driver_id or v.plate_number <> b.plate_number or v.type <> b.vehicle_type"))
                .isZero();
        assertThat(count("select count(*) from bookings b join parking_listings l on l.id = b.listing_id"
                + " where l.status <> 'APPROVED'")).isZero();
    }

    @Test
    void bookingsFallInsideTheOpeningHoursOfTheirListing() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.booking_code || ' ' || l.title || ' ' || b.status as booking_code, b.start_time, b.end_time
                from bookings b join parking_listings l on l.id = b.listing_id where not l.open_24x7""");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            ZonedDateTime start = at(r.get("start_time")).atZone(IST);
            ZonedDateTime end = at(r.get("end_time")).atZone(IST);
            String code = (String) r.get("booking_code");
            assertThat(end.toLocalDate()).as(code).isEqualTo(start.toLocalDate());
            int open = start.getDayOfWeek().getValue() == 7 ? 9 : 8;
            int close = start.getDayOfWeek().getValue() == 7 ? 21 : 22;
            assertThat(start.toLocalTime()).as(code).isAfterOrEqualTo(java.time.LocalTime.of(open, 0));
            assertThat(end.toLocalTime()).as(code).isBeforeOrEqualTo(java.time.LocalTime.of(close, 0));
        }
    }

    // ---- reviews, disputes, notifications --------------------------------------------------------------------

    @Test
    void reviewsAreSkewedPositiveAndListingAggregatesAreRecomputed() {
        long reviews = count("select count(*) from reviews");
        assertThat(reviews).isBetween(180L, 240L);
        assertThat(count("select count(*) from reviews r join bookings b on b.id = r.booking_id"
                + " where b.status <> 'COMPLETED' or b.driver_id <> r.driver_id or b.listing_id <> r.listing_id"))
                .isZero();
        assertThat(count("select count(*) from reviews where created_at < (select completed_at from bookings"
                + " where id = reviews.booking_id) or created_at > ?", Timestamp.from(now))).isZero();

        long fiveStar = count("select count(*) from reviews where rating = 5");
        long fourStar = count("select count(*) from reviews where rating = 4");
        long low = count("select count(*) from reviews where rating <= 3");
        assertThat(fiveStar + fourStar).isGreaterThan(reviews * 7 / 10);
        assertThat(low).isBetween(15L, reviews / 4);
        assertThat(count("select count(*) from reviews where rating = 1")).isGreaterThan(0);

        long replies = count("select count(*) from reviews where owner_reply is not null");
        assertThat(replies).isBetween(reviews * 30 / 100, reviews * 50 / 100);
        assertThat(count("select count(*) from reviews where (owner_reply is null) <> (owner_replied_at is null)"))
                .isZero();
        assertThat(count("select count(*) from reviews where hidden_at is not null")).isEqualTo(1);
        assertThat(count("select count(*) from reviews where hidden_at is not null and hidden_reason is null")).isZero();

        // avg_rating / review_count follow the visible reviews exactly as ReviewService computes them.
        List<Map<String, Object>> aggregates = jdbc.queryForList("""
                select l.id, l.avg_rating, l.review_count,
                       (select count(*) from reviews r where r.listing_id = l.id and r.hidden_at is null) as n,
                       (select coalesce(sum(r.rating), 0) from reviews r
                        where r.listing_id = l.id and r.hidden_at is null) as total
                from parking_listings l""");
        int withReviews = 0;
        for (Map<String, Object> a : aggregates) {
            long n = ((Number) a.get("n")).longValue();
            long total = ((Number) a.get("total")).longValue();
            assertThat(((Number) a.get("review_count")).longValue()).isEqualTo(n);
            BigDecimal expected = n == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
            assertThat((BigDecimal) a.get("avg_rating")).isEqualByComparingTo(expected);
            if (n > 0) {
                withReviews++;
            }
        }
        assertThat(withReviews).isGreaterThan(20);
    }

    @Test
    void disputesCoverEveryStateAndResolution() {
        long disputes = count("select count(*) from disputes");
        assertThat(disputes).isBetween(8L, 12L);
        for (String status : List.of("OPEN", "UNDER_REVIEW", "RESOLVED")) {
            assertThat(count("select count(*) from disputes where status = ?", status)).as(status).isGreaterThanOrEqualTo(2);
        }
        for (String resolution : List.of("REFUND_FULL", "REFUND_PARTIAL", "NO_REFUND", "WARNING")) {
            assertThat(count("select count(*) from disputes where resolution = ?", resolution)).as(resolution)
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(count("select count(*) from disputes where (status = 'RESOLVED') <> (resolved_at is not null"
                + " and admin_notes is not null and resolution is not null)")).isZero();
        assertThat(count("select count(*) from disputes d join bookings b on b.id = d.booking_id"
                + " where b.status <> 'COMPLETED'")).isZero();
        // A refund resolution has its refund row, and the amount agrees with the resolution.
        List<Map<String, Object>> refundResolutions = jdbc.queryForList("""
                select d.resolution, d.resolution_amount, p.amount as paid,
                       (select coalesce(sum(r.amount), 0) from refunds r where r.payment_id = p.id
                        and r.notice = 'DISPUTE') as refunded
                from disputes d join payments p on p.booking_id = d.booking_id
                where d.resolution in ('REFUND_FULL','REFUND_PARTIAL')""");
        assertThat(refundResolutions).hasSize(
                (int) count("select count(*) from disputes where resolution in ('REFUND_FULL','REFUND_PARTIAL')"));
        for (Map<String, Object> r : refundResolutions) {
            assertThat((BigDecimal) r.get("refunded")).isEqualByComparingTo((BigDecimal) r.get("resolution_amount"));
            if ("REFUND_FULL".equals(r.get("resolution"))) {
                assertThat((BigDecimal) r.get("refunded")).isEqualByComparingTo((BigDecimal) r.get("paid"));
            }
        }
    }

    @Test
    void notificationsAndAuditRowsExist() {
        assertThat(count("select count(*) from notifications")).isGreaterThan(150);
        assertThat(count("select count(*) from notifications where read_at is null")).isGreaterThan(20);
        assertThat(count("select count(*) from notifications where read_at is not null")).isGreaterThan(20);
        assertThat(count("select count(*) from notifications where created_at > ?", Timestamp.from(now))).isZero();
        assertThat(count("select count(distinct type) from notifications")).isGreaterThanOrEqualTo(10);
        assertThat(count("select count(*) from admin_actions")).isGreaterThanOrEqualTo(10);
        assertThat(count("select count(distinct action) from admin_actions")).isGreaterThanOrEqualTo(5);
        assertThat(count("select count(*) from admin_actions where created_at > ?", Timestamp.from(now))).isZero();
        assertThat(count("select count(*) from booking_events")).isGreaterThan(1500);
    }

    @Test
    void listingsOfEveryStatusExistAndEveryStateIsStillCovered() {
        for (String status : List.of("APPROVED", "PENDING_REVIEW", "REJECTED", "PAUSED", "SUSPENDED")) {
            assertThat(count("select count(*) from parking_listings where status = ?", status)).as(status)
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(count("select count(distinct c.state_id) from parking_listings l join cities c on c.id = l.city_id"
                + " where l.status = 'APPROVED'")).isEqualTo(36);
        assertThat(count("select count(*) from parking_listings where status = 'APPROVED'")).isGreaterThan(80);
        assertThat(count("select count(*) from parking_listings where not auto_approve and status = 'APPROVED'"))
                .isGreaterThan(5);
        assertThat(count("select count(distinct cancellation_policy) from parking_listings")).isEqualTo(3);
        // Every approved listing is bookable: it has slots of both kinds.
        assertThat(count("select count(*) from parking_listings l where status = 'APPROVED' and (select count(distinct vehicle_type)"
                + " from parking_slots s where s.listing_id = l.id) < 2")).isZero();
    }

    // ---- idempotence, determinism, performance, the dashboard ------------------------------------------------

    @Test
    void seedingTwiceChangesNothing() {
        Map<String, Long> before = tableCounts();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThat(Boolean.TRUE.equals(tx.execute(status -> activitySeeder().seed()))).isFalse();
        assertThat(seedAllAgain()).isFalse();
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(count("select count(*) from platform_settings where key = ?", DemoActivitySeeder.MARKER_KEY))
                .isEqualTo(1);
    }

    private boolean seedAllAgain() {
        return seedAll();
    }

    @Test
    void theSeedingIsFast() {
        assertThat(seedMillis).as("seeding took %d ms", seedMillis).isLessThan(30_000);
    }

    @Test
    void theSameSeedProducesTheSameBookings() {
        List<String> first = jdbc.queryForList("select booking_code from bookings order by booking_code", String.class);
        List<String> firstWindows = windows();
        reset();
        assertThat(seedAll()).isTrue();
        assertThat(jdbc.queryForList("select booking_code from bookings order by booking_code", String.class))
                .isEqualTo(first);
        assertThat(windows()).isEqualTo(firstWindows);
    }

    /** Booking code, listing title, slot label and window (as offsets from now) of every booking, in a stable order. */
    private List<String> windows() {
        List<String> out = new ArrayList<>();
        jdbc.query("""
                select b.booking_code, l.title, s.label, b.status, b.start_time, b.end_time, b.total_amount
                from bookings b join parking_listings l on l.id = b.listing_id
                join parking_slots s on s.id = b.slot_id order by b.booking_code""", rs -> {
            out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4) + "|"
                    + Duration.between(now, rs.getTimestamp(5).toInstant()).toMinutes() + "|"
                    + Duration.between(now, rs.getTimestamp(6).toInstant()).toMinutes() + "|" + rs.getBigDecimal(7));
        });
        return out;
    }

    @Test
    void theAdminDashboardShowsRealNumbers() throws Exception {
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@parkease.dev\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.accessToken");

        String body = mvc.perform(get("/api/v1/admin/stats").param("from", istDate(now.minus(Duration.ofDays(100))))
                        .param("to", istDate(now.plus(Duration.ofDays(16)))).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.drivers").value(org.hamcrest.Matchers.greaterThan(45)))
                .andExpect(jsonPath("$.users.owners").value(org.hamcrest.Matchers.greaterThan(25)))
                .andExpect(jsonPath("$.listings.approved").value(org.hamcrest.Matchers.greaterThan(80)))
                .andExpect(jsonPath("$.listings.pendingReview").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.bookings.created").value(org.hamcrest.Matchers.greaterThan(450)))
                .andExpect(jsonPath("$.bookings.confirmed").value(org.hamcrest.Matchers.greaterThan(300)))
                .andExpect(jsonPath("$.bookings.cancelled").value(org.hamcrest.Matchers.greaterThan(10)))
                .andExpect(jsonPath("$.topCities.length()").value(5))
                .andExpect(jsonPath("$.series.length()").value(org.hamcrest.Matchers.greaterThan(100)))
                .andReturn().getResponse().getContentAsString();

        // The dashboard's GMV is what customers paid net of refunds, to the rupee.
        BigDecimal gmv = new BigDecimal(JsonPath.read(body, "$.money.gmv").toString());
        BigDecimal revenue = new BigDecimal(JsonPath.read(body, "$.money.platformRevenue").toString());
        BigDecimal refunds = new BigDecimal(JsonPath.read(body, "$.money.refunds").toString());
        BigDecimal paid = jdbc.queryForObject("select coalesce(sum(amount), 0) from payments where status <> 'FAILED'",
                BigDecimal.class);
        BigDecimal refunded = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from refunds where status <> 'FAILED'", BigDecimal.class);
        assertThat(gmv.signum()).isPositive();
        assertThat(revenue.signum()).isPositive();
        assertThat(refunds).isEqualByComparingTo(refunded);
        assertThat(gmv).isEqualByComparingTo(paid.subtract(refunded));
    }

    // ---- timeline details --------------------------------------------------------------------------------------

    @Test
    void everyStatusCarriesTheTimestampsItNeeds() {
        // confirmed_at: set once the booking was paid and (for requests) approved; completed_at: only completed ones.
        assertThat(count("select count(*) from bookings where status in ('CONFIRMED','ACTIVE','COMPLETED')"
                + " and confirmed_at is null")).isZero();
        assertThat(count("select count(*) from bookings where status in ('AWAITING_APPROVAL','REJECTED','EXPIRED')"
                + " and confirmed_at is not null")).isZero();
        assertThat(count("select count(*) from bookings where (status = 'COMPLETED') <> (completed_at is not null)"))
                .isZero();
        assertThat(count("select count(*) from bookings where status = 'COMPLETED' and completed_at < end_time"))
                .isZero();
        assertThat(count("select count(*) from bookings where status in ('CANCELLED','REJECTED')"
                + " and (cancelled_by is null)")).isZero();
        assertThat(count("select count(*) from bookings where status not in ('CANCELLED','REJECTED')"
                + " and cancelled_by is not null")).isZero();
        // A request has a deadline only while it waits (approving clears it), a hold only while it is unpaid.
        assertThat(count("select count(*) from bookings where status in ('CONFIRMED','ACTIVE','COMPLETED')"
                + " and approval_deadline is not null")).isZero();
        assertThat(count("select count(*) from bookings where status = 'AWAITING_APPROVAL'"
                + " and approval_deadline is null")).isZero();
        assertThat(count("select count(*) from bookings where hold_expires_at is not null and status <> 'EXPIRED'"))
                .isZero();
    }

    @Test
    void everyBookingsHistoryIsAnUnbrokenChainEndingAtItsStatus() {
        Map<Long, String> statusOf = new HashMap<>();
        jdbc.query("select id, status from bookings", rs -> {
            statusOf.put(rs.getLong(1), rs.getString(2));
        });
        Map<Long, String> last = new HashMap<>();
        Map<Long, Timestamp> lastAt = new HashMap<>();
        jdbc.query("select booking_id, from_status, to_status, created_at from booking_events order by booking_id, id",
                rs -> {
                    long id = rs.getLong(1);
                    String from = rs.getString(2);
                    String previous = last.get(id);
                    if (previous == null) {
                        assertThat(from).as("first event of booking %d", id).isNull();
                    } else {
                        assertThat(from).as("chain of booking %d", id).isEqualTo(previous);
                        assertThat(rs.getTimestamp(4)).as("order of booking %d", id).isAfterOrEqualTo(lastAt.get(id));
                    }
                    last.put(id, rs.getString(3));
                    lastAt.put(id, rs.getTimestamp(4));
                });
        assertThat(last.keySet()).isEqualTo(statusOf.keySet());
        statusOf.forEach((id, status) -> assertThat(last.get(id)).as("last event of booking %d", id).isEqualTo(status));
    }

    @Test
    void reportsAreRaisedWithinTheWindowAfterTheBookingEnded() {
        assertThat(count("select count(*) from disputes d join bookings b on b.id = d.booking_id"
                + " where d.created_at < b.completed_at or d.created_at > b.completed_at + interval '7 days'"))
                .isZero();
        assertThat(count("select count(*) from disputes d join bookings b on b.id = d.booking_id"
                + " where d.resolved_at is not null and d.resolved_at < d.created_at")).isZero();
    }

    @Test
    void ownersAreNeverPaidForABookingWhileItsReportIsUnresolvedOrBeforeItWasResolved() {
        assertThat(count("select count(*) from owner_earnings e join disputes d on d.booking_id = e.booking_id"
                + " where e.status = 'PAID' and (d.status <> 'RESOLVED' or e.paid_at < d.resolved_at)")).isZero();
        assertThat(count("select count(*) from owner_earnings e join disputes d on d.booking_id = e.booking_id"
                + " where d.status <> 'RESOLVED' and e.status = 'PENDING_PAYOUT'")).isGreaterThanOrEqualTo(3);
        // Payouts follow the end of the booking by at least a day.
        assertThat(count("select count(*) from owner_earnings e join bookings b on b.id = e.booking_id"
                + " where e.status = 'PAID' and b.status = 'COMPLETED' and e.paid_at < b.completed_at + interval '1 day'"))
                .isZero();
    }

    @Test
    void requestsWaitNoLongerThanTheApprovalWindow() {
        long windowSeconds = settings.approvalHours() * 3600L;
        assertThat(count("select count(*) from bookings where status = 'AWAITING_APPROVAL' and created_at < ?",
                Timestamp.from(now.minusSeconds(windowSeconds)))).isZero();
        assertThat(count("select count(*) from bookings where status = 'AWAITING_APPROVAL'"
                + " and approval_deadline > start_time")).isZero();
    }

    @Test
    void remindersAndNudgesThatTheJobsWouldHaveSentAreMarkedSent() {
        Timestamp ts = Timestamp.from(now);
        assertThat(count("select count(*) from bookings where status = 'CONFIRMED' and reminder_sent_at is null"
                + " and start_time > ? and start_time <= ?", ts, Timestamp.from(now.plus(Duration.ofMinutes(60)))))
                .isZero();
        assertThat(count("select count(*) from bookings where status = 'AWAITING_APPROVAL'"
                + " and approval_nudge_sent_at is null and approval_deadline > ? and approval_deadline <= ?", ts,
                Timestamp.from(now.plus(Duration.ofMinutes(30))))).isZero();
        assertThat(count("select count(*) from bookings where reminder_sent_at is not null"
                + " and (reminder_sent_at >= start_time or reminder_sent_at > ?)", ts)).isZero();
        assertThat(count("select count(*) from bookings where approval_nudge_sent_at is not null"
                + " and (status <> 'AWAITING_APPROVAL' or approval_nudge_sent_at > ?)", ts)).isZero();
    }

    @Test
    void theHourlyJobsFindNothingToDoOnFreshData() {
        Map<String, Long> before = statusCounts();
        long notifications = count("select count(*) from notifications");
        long events = count("select count(*) from booking_events");

        jobs.expireHolds();
        jobs.autoRejectOverdue();
        jobs.advanceLifecycle();
        jobs.sendReminders();

        assertThat(statusCounts()).isEqualTo(before);
        assertThat(count("select count(*) from notifications")).isEqualTo(notifications);
        assertThat(count("select count(*) from booking_events")).isEqualTo(events);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private Map<String, Long> statusCounts() {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("select status, count(*) from bookings group by status",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    private Map<String, Long> tableCounts() {
        Map<String, Long> out = new TreeMap<>();
        for (String table : List.of("users", "owner_profiles", "vehicles", "parking_listings", "parking_slots",
                "listing_photos", "availability_rules", "availability_blocks", "bookings", "booking_events",
                "payments", "refunds", "owner_earnings", "invoices", "reviews", "disputes", "notifications",
                "admin_actions", "platform_settings")) {
            out.put(table, count("select count(*) from " + table));
        }
        return out;
    }

    private Long id(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private Instant instant(String sql) {
        Timestamp ts = jdbc.queryForObject(sql, Timestamp.class);
        return ts.toInstant();
    }

    private static Instant at(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    private static String istDate(Instant instant) {
        return instant.atZone(IST).toLocalDate().toString();
    }
}
