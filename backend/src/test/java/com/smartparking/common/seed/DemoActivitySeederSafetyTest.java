package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.pricing.PricingService;
import com.smartparking.settings.PlatformSettings;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** What the seeder must never do: touch other people's rows, leave half a seed behind, or take a hosted demo down. */
@CommittedIntegrationTest
class DemoActivitySeederSafetyTest {

    private static final String PASSWORD = "Demo@1234";

    @Autowired JdbcTemplate jdbc;
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

    private final Clock clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);

    @BeforeEach
    void clean() {
        reset();
    }

    @AfterEach
    void cleanAfter() {
        jdbc.execute("drop trigger if exists demo_fail_trg on admin_actions");
        jdbc.execute("drop function if exists demo_fail()");
        reset();
    }

    private void reset() {
        DatabaseCleaner.clean(jdbc);
        jdbc.update("delete from platform_settings where key = ?", DemoActivitySeeder.MARKER_KEY);
    }

    private DemoActivitySeeder seeder(Environment environment) {
        return new DemoActivitySeeder(jdbc, em, txManager, pricing, settings, passwordEncoder, storage, clock,
                environment, PASSWORD);
    }

    /** The accounts and listings the activity builds on, committed. */
    private void seedBase() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            new DemoAccountSeeder(users, ownerProfiles, passwordEncoder, clock, PASSWORD).seed();
            new DemoListingSeeder(users, ownerProfiles, cities, listings, photos, slots, rules, passwordEncoder,
                    storage, clock, PASSWORD).seed();
        });
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long insertUser(String email, String role) {
        return jdbc.queryForObject("insert into users (name, email, password_hash, role, status, email_verified)"
                + " values (?, ?, 'x', ?, 'ACTIVE', true) returning id", Long.class, "Foreign " + role, email, role);
    }

    /** Someone else's owner, driver, listing, slot and booking, as a real user would have made them. */
    private Map<String, List<Map<String, Object>>> insertForeignData() {
        long owner = insertUser("foreign-owner@example.com", "OWNER");
        long driver = insertUser("foreign-driver@example.com", "DRIVER");
        long city = jdbc.queryForObject("select c.id from cities c join states s on s.id = c.state_id"
                + " where s.slug = 'maharashtra' and c.slug = 'pune'", Long.class);
        long listing = jdbc.queryForObject("""
                insert into parking_listings (owner_id, city_id, title, address, pincode, lat, lng, listing_type,
                    open_24x7, auto_approve, status, price_per_hour, cancellation_policy)
                values (?, ?, 'Foreign Spot', 'Somewhere in Pune', '411001', 18.52, 73.85, 'COMMERCIAL', true, true,
                    'APPROVED', 30, 'MODERATE') returning id""", Long.class, owner, city);
        long slot = jdbc.queryForObject("insert into parking_slots (listing_id, label, vehicle_type, size)"
                + " values (?, 'F-01', 'FOUR_WHEELER', 'MEDIUM') returning id", Long.class, listing);
        Instant start = clock.instant().plus(Duration.ofDays(10));
        jdbc.update("""
                insert into bookings (booking_code, driver_id, listing_id, slot_id, vehicle_type, plate_number,
                    start_time, end_time, pricing_mode, pricing_breakdown, base_amount, platform_fee, gst_amount,
                    gst_percent, total_amount, status)
                values ('PK-FOREIG', ?, ?, ?, 'FOUR_WHEELER', 'MH12AB1234', ?, ?, 'HOURLY', '2 hours', 60, 6, 1.08,
                    18, 67.08, 'CONFIRMED')""", driver, listing, slot, Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofHours(2))));
        return foreignSnapshot();
    }

    private Map<String, List<Map<String, Object>>> foreignSnapshot() {
        return Map.of(
                "users", jdbc.queryForList("select * from users where email like 'foreign-%' order by id"),
                "listings", jdbc.queryForList("select * from parking_listings where title = 'Foreign Spot'"),
                "slots", jdbc.queryForList("select * from parking_slots where label = 'F-01'"),
                "bookings", jdbc.queryForList("select * from bookings where booking_code = 'PK-FOREIG'"));
    }

    @Test
    void rowsOfOtherUsersAreLeftExactlyAsTheyWere() {
        Map<String, List<Map<String, Object>>> before = insertForeignData();
        seedBase();

        assertThat(seeder(new MockEnvironment()).seed()).isTrue();

        assertThat(foreignSnapshot()).isEqualTo(before);
        // Nothing was attached to the foreign owner's listing, driver or owner either.
        assertThat(count("select count(*) from bookings where listing_id in"
                + " (select id from parking_listings where title = 'Foreign Spot')")).isEqualTo(1);
        assertThat(count("select count(*) from bookings where driver_id in"
                + " (select id from users where email = 'foreign-driver@example.com')")).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id in"
                + " (select id from users where email like 'foreign-%')")).isZero();
        assertThat(count("select count(*) from owner_earnings where owner_id in"
                + " (select id from users where email like 'foreign-%')")).isZero();
        assertThat(count("select count(*) from vehicles where user_id in"
                + " (select id from users where email like 'foreign-%')")).isZero();
    }

    @Test
    void aFailureHalfwayLeavesNothingBehindAndALaterRunSucceeds() {
        seedBase();
        long users = count("select count(*) from users");
        long listingsBefore = count("select count(*) from parking_listings");
        // Fails when the last rows (the admin audit trail) are written, after almost everything else was.
        jdbc.execute("create function demo_fail() returns trigger language plpgsql as $$ begin"
                + " raise exception 'injected failure'; end $$");
        jdbc.execute("create trigger demo_fail_trg before insert on admin_actions for each row execute function demo_fail()");

        assertThatThrownBy(() -> seeder(new MockEnvironment()).seed()).hasMessageContaining("injected failure");

        assertThat(count("select count(*) from users")).isEqualTo(users);
        assertThat(count("select count(*) from parking_listings")).isEqualTo(listingsBefore);
        for (String table : List.of("bookings", "payments", "booking_events", "refunds", "invoices", "owner_earnings",
                "reviews", "disputes", "notifications", "vehicles", "admin_actions")) {
            assertThat(count("select count(*) from " + table)).as(table).isZero();
        }
        assertThat(count("select count(*) from platform_settings where key = '" + DemoActivitySeeder.MARKER_KEY + "'"))
                .isZero();

        jdbc.execute("drop trigger demo_fail_trg on admin_actions");
        jdbc.execute("drop function demo_fail()");
        assertThat(seeder(new MockEnvironment()).seed()).isTrue();
        assertThat(count("select count(*) from bookings")).isGreaterThan(400);
    }

    @Test
    void underTheDemoProfileAFailedSeedIsLoggedAndTheAppKeepsStarting() {
        seedBase();
        jdbc.execute("create function demo_fail() returns trigger language plpgsql as $$ begin"
                + " raise exception 'injected failure'; end $$");
        jdbc.execute("create trigger demo_fail_trg before insert on admin_actions for each row execute function demo_fail()");
        MockEnvironment hosted = new MockEnvironment();
        hosted.setActiveProfiles("prod", "demo");

        seeder(hosted).run(new DefaultApplicationArguments());

        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(count("select count(*) from platform_settings where key = '" + DemoActivitySeeder.MARKER_KEY + "'"))
                .isZero();
    }

    @Test
    void underTheDevProfileAFailedSeedStopsTheStartup() {
        seedBase();
        jdbc.execute("create function demo_fail() returns trigger language plpgsql as $$ begin"
                + " raise exception 'injected failure'; end $$");
        jdbc.execute("create trigger demo_fail_trg before insert on admin_actions for each row execute function demo_fail()");
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("dev");
        MockEnvironment both = new MockEnvironment();
        both.setActiveProfiles("dev", "demo");

        assertThatThrownBy(() -> seeder(local).run(new DefaultApplicationArguments()))
                .hasMessageContaining("injected failure");
        assertThatThrownBy(() -> seeder(both).run(new DefaultApplicationArguments()))
                .hasMessageContaining("injected failure");
    }
}
