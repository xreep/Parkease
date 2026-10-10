package com.smartparking.common.seed;

import com.smartparking.common.seed.DemoLedger.Rows;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.pricing.PricingService;
import com.smartparking.settings.PlatformSettings;
import com.smartparking.storage.FileStorage;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills a demo database with a believable history: ~30 owners across the major cities (a few waiting for
 * verification), ~50 drivers with their vehicles, ~40 more listings, ~500 bookings from 90 days back to 14 days ahead
 * in every status, with the payments, refunds, invoices, owner earnings and payouts, reviews, disputes,
 * notifications and admin audit rows that go with them. The existing demo logins keep working and get activity too.
 *
 * <p>The data is deterministic (one fixed random seed; dates are relative to the clock), idempotent (a marker row in
 * {@code platform_settings} makes later runs do nothing) and written in a single transaction, so a failure leaves
 * nothing behind. It never touches rows it did not create, apart from the rating columns of the listings it adds
 * reviews to. Amounts come from {@link PricingService}, refunds from the cancellation policy rules, and slots are
 * allocated so that nothing overlaps; payments are made with the mock provider (see the demo registration in
 * {@code PaymentConfig}).
 *
 * <p>Collaborators: {@link DemoAccounts} (people), {@link DemoListings}, {@link DemoShowcase} and
 * {@link DemoBookingPlanner} (bookings), {@link DemoLedger} (money and everything that follows from it) and
 * {@link DemoWriter} (database rows).
 *
 * <p>Failures: under {@code dev} the application refuses to start (like the other seeders); on a hosted demo
 * ({@code demo} without {@code dev}) the error is logged and the application starts without the activity, so a
 * seeding problem cannot cause a crash loop. The next start tries again.
 */
@Slf4j
@Component
@Profile({"dev", "demo"})
@Order(3)
public class DemoActivitySeeder implements ApplicationRunner {

    /** Present in {@code platform_settings} once the activity has been seeded. */
    public static final String MARKER_KEY = "demo_activity_seeded_at";

    static final long SEED = 20261010L;

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final PricingService pricing;
    private final PlatformSettings settings;
    private final PasswordEncoder passwordEncoder;
    private final FileStorage storage;
    private final Clock clock;
    private final Environment environment;
    private final String demoPassword;

    public DemoActivitySeeder(JdbcTemplate jdbc, EntityManager em, PlatformTransactionManager txManager,
                              PricingService pricing, PlatformSettings settings, PasswordEncoder passwordEncoder,
                              FileStorage storage, Clock clock, Environment environment,
                              @Value("${app.seed.demo-password}") String demoPassword) {
        this.jdbc = jdbc;
        this.em = em;
        this.tx = new TransactionTemplate(txManager);
        this.pricing = pricing;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.storage = storage;
        this.clock = clock;
        this.environment = environment;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException e) {
            if (!hostedDemo()) {
                throw e;
            }
            log.error("Seeding the demo activity failed; the application starts without it and will try again at the "
                    + "next start", e);
        }
    }

    /** A hosted demo (demo profile on its own, typically beside prod) must start whatever happens to the seed. */
    private boolean hostedDemo() {
        return environment.acceptsProfiles(Profiles.of("demo")) && !environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** Seeds the activity in one transaction; false (and no change) when it was seeded before. */
    public boolean seed() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            em.flush(); // rows the other seeders saved through JPA must be visible to the JDBC statements below
            // Two instances starting together must not both seed: the second waits here and then finds the marker.
            jdbc.queryForList("select pg_advisory_xact_lock(?)", SEED);
            Integer marked = jdbc.queryForObject("select count(*) from platform_settings where key = ?",
                    Integer.class, MARKER_KEY);
            if (marked != null && marked > 0) {
                log.info("Demo activity already seeded; skipping");
                return false;
            }
            seedNow();
            return true;
        }));
    }

    private void seedNow() {
        long started = System.nanoTime();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Random rnd = new Random(SEED);
        DemoWorld world = new DemoWorld(rnd, now, passwordEncoder.encode(demoPassword));
        DemoIds ids = new DemoIds(jdbc);

        DemoAccounts accounts = new DemoAccounts(jdbc, ids, storage, world);
        accounts.loadExisting();
        accounts.createOwners();
        accounts.createDrivers();
        DemoListings listings = new DemoListings(jdbc, em, ids, world);
        listings.loadExisting();
        listings.createFromFile();

        planBookings(world, ids, listings);
        DemoLedger ledger = new DemoLedger(rnd, now, world.adminId, Set.of(world.demoDriver.id, world.demoOwner.id),
                world.bookings);
        Rows rows = ledger.build(n -> ids.next("reviews", n), n -> ids.next("disputes", n), ids::invoiceNumbers);
        auditAccountsAndListings(world, ledger);
        new DemoWriter(jdbc, em, world, rows).writeAll();

        jdbc.update("insert into platform_settings (key, value, updated_at) values (?, ?, ?)", MARKER_KEY,
                now.toString(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        log.info("Demo activity seeded in {} ms: {} bookings ({}), {} reviews, {} disputes, {} notifications",
                Duration.ofNanos(System.nanoTime() - started).toMillis(), world.bookings.size(), summary(world),
                rows.reviews.size(), rows.disputes.size(), rows.notifications.size());
    }

    private void planBookings(DemoWorld world, DemoIds ids, DemoListings listings) {
        List<Lst> bookable = world.approvedListings.stream()
                .filter(l -> !l.slots.isEmpty() && (l.open24x7 || hasAnyHours(l)))
                .toList();
        List<Person> drivers = world.newDrivers.stream().filter(p -> p.weight > 0).toList();
        if (bookable.isEmpty() || drivers.isEmpty()) {
            log.warn("No bookable demo listings or drivers; seeding no bookings");
            return;
        }
        DemoBookingPlanner planner = new DemoBookingPlanner(world.rnd, world.now, pricing, settings, bookable, drivers);
        new DemoShowcase(world, planner).plan(bookable, drivers);
        planner.planRandom();
        world.bookings.addAll(planner.planned());
        // Chronological order makes ids, codes and the audit trail read naturally.
        world.bookings.sort(Comparator.comparing((Bk b) -> b.createdAt));
        List<Long> bookingIds = ids.next("bookings", world.bookings.size());
        List<Long> paymentIds = ids.next("payments", world.bookings.size());
        for (int i = 0; i < world.bookings.size(); i++) {
            world.bookings.get(i).id = bookingIds.get(i);
            world.bookings.get(i).paymentId = paymentIds.get(i);
        }
        listings.planBlocks(bookable);
    }

    private static boolean hasAnyHours(Lst l) {
        for (var h : l.hours) {
            if (h != null) {
                return true;
            }
        }
        return false;
    }

    private void auditAccountsAndListings(DemoWorld world, DemoLedger ledger) {
        Instant now = world.now;
        List<Person> owners = world.newOwners;
        for (int i = 0; i < owners.size(); i++) {
            Person p = owners.get(i);
            if (i < DemoCatalog.VERIFIED_NEW_OWNERS) {
                ledger.audit(p.createdAt.plus(Duration.ofDays(1)), "OWNER_VERIFIED", "OWNER", p.id, null);
            } else if (i == owners.size() - 1) {
                ledger.audit(p.createdAt.plus(Duration.ofDays(2)), "OWNER_REJECTED", "OWNER", p.id,
                        "Reason: " + DemoWriter.REJECTION_REASON);
            }
        }
        for (Lst l : world.newListings) {
            switch (l.status) {
                case APPROVED, PAUSED -> ledger.audit(l.approvedAt, "LISTING_APPROVED", "LISTING", l.id, null);
                case REJECTED -> ledger.audit(l.submittedAt.plus(Duration.ofHours(20)), "LISTING_REJECTED", "LISTING",
                        l.id, "Reason: " + l.rejectionReason);
                case SUSPENDED -> {
                    ledger.audit(l.approvedAt, "LISTING_APPROVED", "LISTING", l.id, null);
                    ledger.audit(now.minus(Duration.ofDays(9)), "LISTING_SUSPENDED", "LISTING", l.id,
                            "Reason: " + l.rejectionReason);
                }
                default -> { }
            }
        }
        Person suspended = world.newDrivers.get(world.newDrivers.size() - 1);
        ledger.audit(now.minus(Duration.ofDays(5)), "USER_SUSPENDED", "USER", suspended.id,
                "Reason: Repeated chargebacks reported by owners");
    }

    private static String summary(DemoWorld world) {
        TreeMap<String, Long> counts = new TreeMap<>();
        for (Bk b : world.bookings) {
            counts.merge(b.status().name(), 1L, Long::sum);
        }
        return counts.toString();
    }
}
