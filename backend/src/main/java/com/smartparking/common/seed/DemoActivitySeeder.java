package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoLedger.Rows;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.Hours;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Outcome;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Slot;
import com.smartparking.common.seed.DemoModel.Veh;
import com.smartparking.common.seed.DemoModel.Window;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.pricing.PricingService;
import com.smartparking.settings.PlatformSettings;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.ValidatedUpload;
import com.smartparking.user.Role;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills a demo database with a believable history: ~30 owners across the major cities (a few waiting for
 * verification), ~50 drivers with their vehicles, ~40 more listings, ~500 bookings from 90 days back to 14 days ahead
 * in every status, with the payments, refunds, invoices, owner earnings and payouts, reviews, disputes,
 * notifications and admin audit rows that go with them. The existing demo logins keep working and get activity too.
 *
 * <p>The data is deterministic (one fixed random seed; dates are relative to the clock), idempotent (a marker row in
 * {@code platform_settings} makes later runs do nothing, and the whole seed is one transaction), and never touches
 * rows it did not create, apart from the rating columns of the listings it adds reviews to. Amounts come from
 * {@link PricingService}, refunds from the cancellation policy rules, and slots are allocated so that nothing
 * overlaps. Rows are written with JDBC batches (primary keys are taken from the sequences up front), so the whole
 * seed takes a few seconds even over a network.
 */
@Slf4j
@Component
@Profile({"dev", "demo"})
@Order(3)
public class DemoActivitySeeder implements ApplicationRunner {

    /** Present in {@code platform_settings} once the activity has been seeded. */
    public static final String MARKER_KEY = "demo_activity_seeded_at";

    static final long SEED = 20261010L;

    private static final String LISTINGS_FILE = "seed/demo-activity-listings.psv";
    private static final String PENDING_OWNER_EMAIL = "owner.pending@parkease.dev";
    private static final String REJECTION_REASON =
            "The document was unreadable. Please upload a clear photo of your ID.";
    private static final String RULES = "Park only in your assigned slot. Follow staff instructions.";
    private static final byte[] DOCUMENT_PDF = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj "
            + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]>>endobj\n"
            + "trailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.US_ASCII);

    // ---- SQL ------------------------------------------------------------------------------------------------

    private static final String INSERT_USER = "insert into users (id, name, email, phone, password_hash, role, status,"
            + " email_verified, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_OWNER_PROFILE = "insert into owner_profiles (user_id, verification_status,"
            + " document_type, document_key, document_content_type, document_submitted_at, payout_upi,"
            + " payout_bank_account, payout_ifsc, payout_account_name, rejection_reason, verified_at, created_at,"
            + " updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_VEHICLE = "insert into vehicles (id, user_id, type, plate_number, make_model,"
            + " is_default, created_at, updated_at) values (?,?,?,?,?,?,?,?)";
    private static final String INSERT_LISTING = "insert into parking_listings (id, owner_id, city_id, title,"
            + " description, address, pincode, lat, lng, listing_type, open_24x7, rules, auto_approve, status,"
            + " rejection_reason, price_per_hour, price_per_day, price_per_month, cancellation_policy, avg_rating,"
            + " review_count, submitted_at, approved_at, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,?,?,?)";
    private static final String INSERT_AMENITY = "insert into listing_amenities (listing_id, amenity) values (?,?)";
    private static final String INSERT_PHOTO = "insert into listing_photos (listing_id, url, storage_key, sort_order,"
            + " created_at, updated_at) values (?,?,?,?,?,?)";
    private static final String INSERT_SLOT = "insert into parking_slots (id, listing_id, label, vehicle_type, size,"
            + " active, created_at, updated_at) values (?,?,?,?,?,?,?,?)";
    private static final String INSERT_BLOCK = "insert into availability_blocks (listing_id, slot_id, start_time,"
            + " end_time, reason, created_at, updated_at) values (?,?,?,?,?,?,?)";
    private static final String INSERT_BOOKING = "insert into bookings (id, booking_code, driver_id, listing_id,"
            + " slot_id, vehicle_id, vehicle_type, plate_number, start_time, end_time, pricing_mode,"
            + " pricing_breakdown, base_amount, platform_fee, gst_amount, gst_percent, total_amount, refund_amount,"
            + " status, hold_expires_at, approval_deadline, confirmed_at, completed_at, reminder_sent_at,"
            + " cancelled_by, cancel_reason, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_PAYMENT = "insert into payments (id, booking_id, provider, provider_order_id,"
            + " provider_payment_id, amount, currency, method, status, failure_reason, captured_at, created_at,"
            + " updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_EVENT = "insert into booking_events (booking_id, from_status, to_status, actor,"
            + " note, created_at, updated_at) values (?,?,?,?,?,?,?)";
    private static final String INSERT_REFUND = "insert into refunds (payment_id, provider_refund_id, amount, status,"
            + " reason, created_at, updated_at, attempts, notice) values (?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_INVOICE = "insert into invoices (invoice_number, booking_id, payment_id,"
            + " issued_at, created_at, updated_at) values (?,?,?,?,?,?)";
    private static final String INSERT_EARNING = "insert into owner_earnings (booking_id, owner_id, gross, commission,"
            + " net, status, payout_reference, paid_at, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_REVIEW = "insert into reviews (id, booking_id, listing_id, driver_id, rating,"
            + " comment, owner_reply, owner_replied_at, created_at, updated_at, hidden_at, hidden_reason)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_DISPUTE = "insert into disputes (id, booking_id, raised_by, category,"
            + " description, status, owner_response, owner_responded_at, admin_notes, resolution, resolution_amount,"
            + " resolved_at, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_NOTIFICATION = "insert into notifications (user_id, type, title, body, link,"
            + " read_at, created_at, updated_at) values (?,?,?,?,?,?,?,?)";
    private static final String INSERT_ADMIN_ACTION = "insert into admin_actions (admin_id, action, target_type,"
            + " target_id, details, created_at, updated_at) values (?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final PricingService pricing;
    private final PlatformSettings settings;
    private final PasswordEncoder passwordEncoder;
    private final FileStorage storage;
    private final Clock clock;
    private final String demoPassword;

    public DemoActivitySeeder(JdbcTemplate jdbc, EntityManager em, PricingService pricing, PlatformSettings settings,
                              PasswordEncoder passwordEncoder, FileStorage storage, Clock clock,
                              @Value("${app.seed.demo-password}") String demoPassword) {
        this.jdbc = jdbc;
        this.em = em;
        this.pricing = pricing;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.storage = storage;
        this.clock = clock;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Seeds the activity; false (and no change) when it was seeded before. */
    @Transactional
    public boolean seed() {
        em.flush(); // rows the other seeders saved through JPA must be visible to the JDBC statements below
        // Two instances starting together must not both seed: the second waits here and then finds the marker.
        jdbc.queryForList("select pg_advisory_xact_lock(?)", SEED);
        Integer marked = jdbc.queryForObject("select count(*) from platform_settings where key = ?", Integer.class,
                MARKER_KEY);
        if (marked != null && marked > 0) {
            log.info("Demo activity already seeded; skipping");
            return false;
        }
        long started = System.nanoTime();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Random rnd = new Random(SEED);
        Plan plan = new Plan(rnd, now);

        plan.loadAccounts();
        plan.createOwners();
        plan.createDrivers();
        plan.loadListings();
        plan.createListings();
        plan.planBookings();
        plan.buildLedger();
        plan.write();

        jdbc.update("insert into platform_settings (key, value, updated_at) values (?, ?, ?)", MARKER_KEY,
                now.toString(), OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
        log.info("Demo activity seeded in {} ms: {} bookings ({}), {} reviews, {} disputes, {} notifications",
                Duration.ofNanos(System.nanoTime() - started).toMillis(), plan.bookings.size(),
                plan.statusSummary(), plan.rows.reviews.size(), plan.rows.disputes.size(),
                plan.rows.notifications.size());
        return true;
    }

    // ---- the work -------------------------------------------------------------------------------------------

    /** One run's working state: people and listings in memory, planned bookings, then the rows to write. */
    private final class Plan {
        final Random rnd;
        final Instant now;
        final String passwordHash;

        long adminId;
        Person demoOwner;
        Person demoDriver;
        final Map<String, Person> existingOwners = new LinkedHashMap<>();
        final List<Person> newOwners = new ArrayList<>();
        final List<Person> newDrivers = new ArrayList<>();
        final Map<String, Person> ownerByKey = new HashMap<>();
        final List<Lst> allListings = new ArrayList<>();
        final List<Lst> newListings = new ArrayList<>();
        final List<Bk> bookings = new ArrayList<>();
        final List<Window> newBlocks = new ArrayList<>();
        final List<Lst> blockedListings = new ArrayList<>();
        final Set<String> emails = new HashSet<>();
        final Set<String> plates = new HashSet<>();
        final List<Long> vehicleIds = new ArrayList<>();
        Rows rows;
        DemoLedger ledger;
        String documentKey;
        String documentContentType;

        Plan(Random rnd, Instant now) {
            this.rnd = rnd;
            this.now = now;
            this.passwordHash = passwordEncoder.encode(demoPassword);
        }

        // ---- accounts ---------------------------------------------------------------------------------------

        void loadAccounts() {
            adminId = requirePerson(DemoAccountSeeder.ADMIN_EMAIL).id;
            demoOwner = requirePerson(DemoAccountSeeder.OWNER_EMAIL);
            demoDriver = requirePerson(DemoAccountSeeder.DRIVER_EMAIL);
            demoDriver.role = Role.DRIVER;
            demoOwner.role = Role.OWNER;
            for (String email : DemoCatalog.EXISTING_OWNER_EMAILS) {
                Person p = email.equals(DemoAccountSeeder.OWNER_EMAIL) ? demoOwner : findPerson(email);
                if (p != null) {
                    p.role = Role.OWNER;
                    existingOwners.put(email, p);
                }
            }
            ownerByKey.put("demo", demoOwner);
            emails.addAll(jdbc.queryForList("select email from users", String.class));
            plates.addAll(jdbc.queryForList("select plate_number from vehicles", String.class));
            loadVehiclesOfDemoDriver();
            documentFromPendingOwner();
        }

        private Person requirePerson(String email) {
            Person p = findPerson(email);
            if (p == null) {
                throw new IllegalStateException("Demo account " + email + " is missing; "
                        + "DemoAccountSeeder and DemoListingSeeder must run first");
            }
            return p;
        }

        private Person findPerson(String email) {
            List<Person> found = jdbc.query("select id, name, email, phone, created_at from users where email = ?",
                    (rs, i) -> {
                        Person p = new Person();
                        p.id = rs.getLong(1);
                        p.name = rs.getString(2);
                        p.email = rs.getString(3);
                        p.phone = rs.getString(4);
                        p.createdAt = rs.getTimestamp(5).toInstant();
                        p.existing = true;
                        return p;
                    }, email);
            return found.isEmpty() ? null : found.get(0);
        }

        private void loadVehiclesOfDemoDriver() {
            jdbc.query("select id, type, plate_number, make_model, is_default, created_at from vehicles"
                    + " where user_id = ? order by id", rs -> {
                Veh v = new Veh();
                v.id = rs.getLong(1);
                v.userId = demoDriver.id;
                v.type = VehicleType.valueOf(rs.getString(2));
                v.plate = rs.getString(3);
                v.makeModel = rs.getString(4);
                v.isDefault = rs.getBoolean(5);
                v.createdAt = rs.getTimestamp(6).toInstant();
                v.existing = true;
                demoDriver.vehicles.add(v);
            }, demoDriver.id);
            // The showcase needs a car and a two-wheeler; a driver who has only one kind gets the other.
            for (VehicleType type : VehicleType.values()) {
                if (demoDriver.vehicles.stream().noneMatch(v -> v.type == type)) {
                    boolean car = type == VehicleType.FOUR_WHEELER;
                    String plate = car ? "MH02DX4521" : "MH02KL7788";
                    while (!plates.add(plate)) {
                        plate = DemoCatalog.plate(rnd);
                    }
                    Veh v = vehicle(demoDriver, type, plate, car ? "Hyundai Creta" : "Honda Activa 6G",
                            demoDriver.vehicles.isEmpty());
                    v.id = nextIds("vehicles", 1).get(0);
                    demoDriver.vehicles.add(v);
                }
            }
        }

        /** The pending owner of the listing seeder already has a document on file; the new pending owners share it. */
        private void documentFromPendingOwner() {
            List<String[]> found = jdbc.query("select p.document_key, p.document_content_type from owner_profiles p"
                    + " join users u on u.id = p.user_id where u.email = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, PENDING_OWNER_EMAIL);
            if (!found.isEmpty() && found.get(0)[0] != null) {
                documentKey = found.get(0)[0];
                documentContentType = found.get(0)[1];
            } else {
                StoredFile stored = storage.storePrivate(new ValidatedUpload(DOCUMENT_PDF, "application/pdf", "pdf"),
                        "owner-documents");
                documentKey = stored.key();
                documentContentType = stored.contentType();
            }
        }

        // ---- owners and drivers -----------------------------------------------------------------------------

        void createOwners() {
            List<Long> ids = nextIds("users", DemoCatalog.NEW_OWNER_NAMES.size());
            for (int i = 0; i < DemoCatalog.NEW_OWNER_NAMES.size(); i++) {
                Person p = new Person();
                p.id = ids.get(i);
                p.name = DemoCatalog.NEW_OWNER_NAMES.get(i);
                p.email = uniqueEmail(p.name);
                p.phone = DemoCatalog.phone(rnd);
                p.role = Role.OWNER;
                boolean verified = i < DemoCatalog.VERIFIED_NEW_OWNERS;
                boolean pending = i >= DemoCatalog.VERIFIED_NEW_OWNERS
                        && i < DemoCatalog.VERIFIED_NEW_OWNERS + DemoCatalog.PENDING_NEW_OWNERS;
                p.createdAt = now.minus(Duration.ofDays(verified ? 125 + rnd.nextInt(26)
                        : pending ? 1 + rnd.nextInt(6) : 18 + rnd.nextInt(8)));
                newOwners.add(p);
                ownerByKey.put("o%02d".formatted(i + 1), p);
            }
        }

        void createDrivers() {
            int count = 50;
            List<Long> ids = nextIds("users", count);
            vehicleIds.addAll(nextIds("vehicles", 2 * count)); // at most two each; unused keys are simply skipped
            Set<String> names = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Person p = new Person();
                p.id = ids.get(i);
                String name;
                do {
                    name = DemoCatalog.FIRST_NAMES.get(rnd.nextInt(DemoCatalog.FIRST_NAMES.size())) + " "
                            + DemoCatalog.LAST_NAMES.get(rnd.nextInt(DemoCatalog.LAST_NAMES.size()));
                } while (!names.add(name));
                p.name = name;
                p.email = uniqueEmail(name);
                p.phone = DemoCatalog.phone(rnd);
                p.role = Role.DRIVER;
                boolean unverified = i >= count - 4 && i < count - 1;
                p.suspended = i == count - 1;
                p.emailVerified = !unverified;
                p.createdAt = now.minus(Duration.ofDays(unverified ? 1 + rnd.nextInt(8) : 4 + rnd.nextInt(115)));
                p.weight = unverified || p.suspended ? 0 : 0.3 + rnd.nextDouble() * rnd.nextDouble() * 3;
                addVehicles(p);
                newDrivers.add(p);
            }
        }

        private void addVehicles(Person p) {
            int n = rnd.nextDouble() < 0.6 ? 1 : 2;
            boolean firstIsCar = rnd.nextDouble() < 0.62;
            for (int k = 0; k < n; k++) {
                VehicleType type = (k == 0) == firstIsCar ? VehicleType.FOUR_WHEELER : VehicleType.TWO_WHEELER;
                String plate;
                do {
                    plate = DemoCatalog.plate(rnd);
                } while (!plates.add(plate));
                List<String> makes = type == VehicleType.FOUR_WHEELER ? DemoCatalog.FOUR_WHEELERS
                        : DemoCatalog.TWO_WHEELERS;
                Veh v = vehicle(p, type, plate, makes.get(rnd.nextInt(makes.size())), k == 0);
                v.id = vehicleIds.remove(0);
                p.vehicles.add(v);
            }
        }

        private Veh vehicle(Person owner, VehicleType type, String plate, String make, boolean isDefault) {
            Veh v = new Veh();
            v.userId = owner.id;
            v.type = type;
            v.plate = plate;
            v.makeModel = make;
            v.isDefault = isDefault;
            return v;
        }

        private String uniqueEmail(String name) {
            String base = name.toLowerCase().replace("'", "").replace(' ', '.');
            String email = base + "@example.com";
            for (int n = 2; !emails.add(email); n++) {
                email = base + n + "@example.com";
            }
            return email;
        }

        // ---- listings ---------------------------------------------------------------------------------------

        /** Approved listings of the demo owners that exist already, with their slots, hours and blocks. */
        void loadListings() {
            if (existingOwners.isEmpty()) {
                return;
            }
            String ownerIds = String.join(",", existingOwners.values().stream().map(p -> Long.toString(p.id)).toList());
            Map<Long, Person> byId = new HashMap<>();
            existingOwners.values().forEach(p -> byId.put(p.id, p));
            Map<Long, Lst> listingById = new LinkedHashMap<>();
            jdbc.query("select l.id, l.owner_id, l.city_id, c.tier, l.title, l.listing_type, l.open_24x7,"
                    + " l.auto_approve, l.cancellation_policy, l.price_per_hour, l.price_per_day, l.price_per_month"
                    + " from parking_listings l join cities c on c.id = l.city_id"
                    + " where l.status = 'APPROVED' and l.owner_id in (" + ownerIds + ") order by l.id", rs -> {
                Lst l = new Lst();
                l.id = rs.getLong(1);
                l.owner = byId.get(rs.getLong(2));
                l.cityId = rs.getLong(3);
                l.tier = rs.getInt(4);
                l.title = rs.getString(5);
                l.type = ListingType.valueOf(rs.getString(6));
                l.open24x7 = rs.getBoolean(7);
                l.autoApprove = rs.getBoolean(8);
                l.policy = CancellationPolicy.valueOf(rs.getString(9));
                l.hour = rs.getBigDecimal(10);
                l.day = rs.getBigDecimal(11);
                l.month = rs.getBigDecimal(12);
                listingById.put(l.id, l);
            });
            if (listingById.isEmpty()) {
                return;
            }
            String listingIds = String.join(",", listingById.keySet().stream().map(String::valueOf).toList());
            Map<Long, Slot> slotById = new HashMap<>();
            jdbc.query("select id, listing_id, label, vehicle_type from parking_slots where active"
                    + " and listing_id in (" + listingIds + ") order by listing_id, label", rs -> {
                Slot s = new Slot();
                s.id = rs.getLong(1);
                s.label = rs.getString(3);
                s.type = VehicleType.valueOf(rs.getString(4));
                listingById.get(rs.getLong(2)).slots.add(s);
                slotById.put(s.id, s);
            });
            // Opening hours go through JPA, exactly like the availability checks read them: Hibernate stores times of day
            // in the JDBC time zone, so the raw column values only make sense when read back the same way.
            em.createQuery("select r from AvailabilityRule r where r.listing.id in :ids", AvailabilityRule.class)
                    .setParameter("ids", listingById.keySet()).getResultList()
                    .forEach(r -> listingById.get(r.getListing().getId()).hours[r.getDayOfWeek()] =
                            new Hours(r.getOpenTime(), r.getCloseTime()));
            jdbc.query("select listing_id, slot_id, start_time, end_time from availability_blocks"
                    + " where listing_id in (" + listingIds + ")", rs -> {
                Window w = new Window(rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant());
                long slotId = rs.getLong(2);
                if (rs.wasNull()) {
                    listingById.get(rs.getLong(1)).blocks.add(w);
                } else if (slotById.containsKey(slotId)) {
                    slotById.get(slotId).busy.add(w);
                }
            });
            jdbc.query("select slot_id, start_time, end_time from bookings where status in ('PENDING_PAYMENT',"
                    + " 'AWAITING_APPROVAL','CONFIRMED','ACTIVE') and listing_id in (" + listingIds + ")", rs -> {
                Slot s = slotById.get(rs.getLong(1));
                if (s != null) {
                    s.busy.add(new Window(rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()));
                }
            });
            allListings.addAll(listingById.values());
        }

        /** Columns: owner|state|city|title|address|pincode|lat|lng|type|hour|day|month|2W|4W|24x7|amenities|photo|policy|auto|status|note */
        void createListings() {
            List<String[]> lines = readRows();
            Map<String, Long> cityIds = new HashMap<>();
            Map<String, Integer> cityTiers = new HashMap<>();
            jdbc.query("select s.slug, c.slug, c.id, c.tier from cities c join states s on s.id = c.state_id", rs -> {
                cityIds.put(rs.getString(1) + "/" + rs.getString(2), rs.getLong(3));
                cityTiers.put(rs.getString(1) + "/" + rs.getString(2), rs.getInt(4));
            });
            int slotCount = 0;
            for (String[] f : lines) {
                slotCount += Integer.parseInt(f[12]) + Integer.parseInt(f[13]);
            }
            List<Long> listingIds = nextIds("parking_listings", lines.size());
            List<Long> slotIds = nextIds("parking_slots", slotCount);
            int slotIndex = 0;
            for (int i = 0; i < lines.size(); i++) {
                String[] f = lines.get(i);
                Person owner = ownerByKey.get(f[0]);
                String cityKey = f[1] + "/" + f[2];
                if (owner == null || !cityIds.containsKey(cityKey)) {
                    throw new IllegalStateException("Unknown owner or city in " + LISTINGS_FILE + ": " + String.join("|", f));
                }
                Lst l = new Lst();
                l.isNew = true;
                l.id = listingIds.get(i);
                l.owner = owner;
                l.cityId = cityIds.get(cityKey);
                l.tier = cityTiers.get(cityKey);
                l.title = f[3];
                l.address = f[4];
                l.pincode = f[5];
                l.lat = Double.parseDouble(f[6]);
                l.lng = Double.parseDouble(f[7]);
                l.type = ListingType.valueOf(f[8]);
                l.hour = new BigDecimal(f[9]).setScale(2);
                l.day = new BigDecimal(f[10]).setScale(2);
                l.month = new BigDecimal(f[11]).setScale(2);
                l.twoWheelers = Integer.parseInt(f[12]);
                l.fourWheelers = Integer.parseInt(f[13]);
                l.open24x7 = Boolean.parseBoolean(f[14]);
                l.amenities = f[15].isBlank() ? List.of() : List.of(f[15].split(";"));
                l.photo = Integer.parseInt(f[16]);
                l.policy = CancellationPolicy.valueOf(f[17]);
                l.autoApprove = Boolean.parseBoolean(f[18]);
                l.status = ListingStatus.valueOf(f[19]);
                l.rejectionReason = f[20].isBlank() ? null : f[20];
                l.description = "Secure " + l.type.name().toLowerCase() + " parking at " + l.title
                        + ". Reserve in advance on ParkEase.";
                dateListing(l);
                for (int k = 1; k <= l.fourWheelers; k++) {
                    l.slots.add(newSlot(slotIds.get(slotIndex++), "A-%02d".formatted(k), VehicleType.FOUR_WHEELER));
                }
                for (int k = 1; k <= l.twoWheelers; k++) {
                    l.slots.add(newSlot(slotIds.get(slotIndex++), "B-%02d".formatted(k), VehicleType.TWO_WHEELER));
                }
                for (int day = 1; day <= 7; day++) {
                    l.hours[day] = day == 7 ? new Hours(LocalTime.of(9, 0), LocalTime.of(21, 0))
                            : new Hours(LocalTime.of(8, 0), LocalTime.of(22, 0));
                }
                newListings.add(l);
                if (l.status == ListingStatus.APPROVED) {
                    allListings.add(l);
                }
            }
            allListings.forEach(this::weigh);
        }

        private void dateListing(Lst l) {
            boolean demo = l.owner == demoOwner;
            switch (l.status) {
                case APPROVED -> {
                    l.createdAt = demo ? now.minus(Duration.ofDays(110 + rnd.nextInt(10)))
                            : l.owner.createdAt.plus(Duration.ofDays(1 + rnd.nextInt(3)));
                    l.submittedAt = l.createdAt.plus(Duration.ofHours(4 + rnd.nextInt(30)));
                    l.approvedAt = l.submittedAt.plus(Duration.ofHours(8 + rnd.nextInt(30)));
                }
                case PAUSED, SUSPENDED -> {
                    l.createdAt = now.minus(Duration.ofDays(70 + rnd.nextInt(30)));
                    l.submittedAt = l.createdAt.plus(Duration.ofHours(6));
                    l.approvedAt = l.submittedAt.plus(Duration.ofHours(20));
                }
                case REJECTED -> {
                    l.createdAt = now.minus(Duration.ofDays(8 + rnd.nextInt(5)));
                    l.submittedAt = l.createdAt.plus(Duration.ofHours(5));
                }
                default -> { // PENDING_REVIEW, DRAFT
                    l.createdAt = now.minus(Duration.ofDays(1 + rnd.nextInt(3)));
                    l.submittedAt = l.status == ListingStatus.DRAFT ? null : l.createdAt.plus(Duration.ofHours(3));
                }
            }
        }

        /** Busier cities, the demo owner's listings and bigger car parks get more bookings. */
        private void weigh(Lst l) {
            double ownerFactor = l.owner == demoOwner ? 2.2 : 1.0;
            double tierFactor = l.tier == 1 ? 5.0 : l.tier == 2 ? 2.5 : 1.0;
            l.weight = tierFactor * ownerFactor * Math.sqrt(Math.max(1, l.slots.size())) * (0.6 + 0.8 * rnd.nextDouble());
        }

        private Slot newSlot(long id, String label, VehicleType type) {
            Slot s = new Slot();
            s.id = id;
            s.label = label;
            s.type = type;
            return s;
        }

        private List<String[]> readRows() {
            List<String[]> rows = new ArrayList<>();
            try (InputStream in = new ClassPathResource(LISTINGS_FILE).getInputStream()) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split("\\|", -1);
                    for (int i = 0; i < fields.length; i++) {
                        fields[i] = fields[i].trim();
                    }
                    if (fields.length != 21) {
                        throw new IllegalStateException("Bad row in " + LISTINGS_FILE + ": " + line);
                    }
                    rows.add(fields);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + LISTINGS_FILE, e);
            }
            return rows;
        }

        // ---- bookings ---------------------------------------------------------------------------------------

        void planBookings() {
            List<Lst> bookable = allListings.stream()
                    .filter(l -> l.slots.size() > 0 && (l.open24x7 || hasAnyHours(l)))
                    .toList();
            List<Person> drivers = newDrivers.stream().filter(p -> p.weight > 0).toList();
            if (bookable.isEmpty() || drivers.isEmpty()) {
                log.warn("No bookable demo listings or drivers; seeding no bookings");
                return;
            }
            // Ids are needed up front for rows that point at the bookings; assign after planning (count is known then).
            DemoBookingPlanner planner = new DemoBookingPlanner(rnd, now, pricing, settings, bookable, drivers);
            showcase(planner, bookable, drivers);
            planner.planRandom();
            bookings.addAll(planner.planned());
            // Chronological order makes ids, codes and the audit trail read naturally.
            bookings.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
            List<Long> bookingIds = nextIds("bookings", bookings.size());
            List<Long> paymentIds = nextIds("payments", bookings.size());
            for (int i = 0; i < bookings.size(); i++) {
                bookings.get(i).id = bookingIds.get(i);
                bookings.get(i).paymentId = paymentIds.get(i);
            }
            planBlocks(bookable);
        }

        private boolean hasAnyHours(Lst l) {
            for (Hours h : l.hours) {
                if (h != null) {
                    return true;
                }
            }
            return false;
        }

        /** Bookings the demo logins show off: the driver's whole range of states, the owner's pending requests. */
        private void showcase(DemoBookingPlanner planner, List<Lst> bookable, List<Person> drivers) {
            Lst andheri = byTitle(bookable, "Andheri Metro Station Parking");
            Lst bkc = byTitle(bookable, "BKC Office Parking");
            Lst cyber = byTitle(bookable, "Cyber City Office Parking");
            Lst powai = byTitle(bookable, "Powai Hiranandani Mall Parking");
            Lst bandra = byTitle(bookable, "Bandra West Society Parking");
            Lst koregaon = byTitle(bookable, "Koregaon Park Garage");
            Veh car = vehicleOf(demoDriver, VehicleType.FOUR_WHEELER);
            Veh bike = vehicleOf(demoDriver, VehicleType.TWO_WHEELER);
            List<Person> carDrivers = drivers.stream().filter(d -> vehicleOf(d, VehicleType.FOUR_WHEELER) != null).toList();
            if (car == null || bike == null || andheri == null || bkc == null || cyber == null || powai == null
                    || bandra == null || koregaon == null || carDrivers.size() < 6) {
                log.warn("Some demo listings, vehicles or drivers are missing; skipping the showcase bookings");
                return;
            }
            // The demo driver's own bookings: one of everything.
            Instant startedAt = planner.floorQuarter(now.minus(Duration.ofMinutes(70)));
            planner.addForced(andheri, demoDriver, car, startedAt, startedAt.plus(Duration.ofHours(4)), Outcome.ACTIVE, null);
            show(planner, bkc, demoDriver, car, 1, 10, 0, 300, Outcome.CONFIRMED, null);
            show(planner, cyber, demoDriver, car, 5, 10, 0, 420, Outcome.CONFIRMED, null);
            show(planner, bandra, demoDriver, car, 2, 11, 0, 180, Outcome.AWAITING, null);
            mark(show(planner, andheri, demoDriver, car, -2, 9, 0, 180, Outcome.COMPLETED, null), 0, false, true);
            mark(show(planner, powai, demoDriver, car, -9, 10, 0, 180, Outcome.COMPLETED, null), 5, false, false);
            mark(show(planner, koregaon, demoDriver, car, -16, 10, 0, 240, Outcome.COMPLETED, null), 4, true, false);
            mark(show(planner, andheri, demoDriver, bike, -33, 8, 30, 150, Outcome.COMPLETED, null), 3, false, false);
            mark(show(planner, bkc, demoDriver, car, -58, 9, 0, 360, Outcome.COMPLETED, null), -1, false, false);
            mark(show(planner, cyber, demoDriver, car, -1, 10, 0, 420, Outcome.COMPLETED, null), -1, false, false);
            show(planner, bkc, demoDriver, car, -12, 14, 0, 240, Outcome.CANCELLED_DRIVER, 10.0);
            show(planner, bandra, demoDriver, car, -20, 10, 0, 120, Outcome.REJECTED_OWNER, null);
            show(planner, andheri, demoDriver, bike, -40, 8, 0, 180, Outcome.EXPIRED, null);

            // The demo owner's requests waiting for an answer, a live booking and a poor review, from other drivers.
            show(planner, bandra, carDrivers.get(0), 1, 10, 0, 180, Outcome.AWAITING);
            show(planner, koregaon, carDrivers.get(1), 3, 9, 30, 240, Outcome.AWAITING);
            show(planner, bandra, carDrivers.get(2), 4, 14, 0, 240, Outcome.AWAITING);
            show(planner, powai, carDrivers.get(3), 2, 10, 0, 300, Outcome.CONFIRMED);
            Instant powaiStart = planner.floorQuarter(now.minus(Duration.ofMinutes(40)));
            planner.addForced(powai, carDrivers.get(4), vehicleOf(carDrivers.get(4), VehicleType.FOUR_WHEELER),
                    powaiStart, powaiStart.plus(Duration.ofHours(3)), Outcome.ACTIVE, null);
            mark(show(planner, powai, carDrivers.get(5), -3, 10, 0, 180, Outcome.COMPLETED), 2, true, false);
        }

        private Bk show(DemoBookingPlanner planner, Lst l, Person driver, int day, int hour, int minute, int minutes,
                        Outcome outcome) {
            return show(planner, l, driver, vehicleOf(driver, VehicleType.FOUR_WHEELER), day, hour, minute, minutes,
                    outcome, null);
        }

        private Bk show(DemoBookingPlanner planner, Lst l, Person driver, Veh vehicle, int day, int hour, int minute,
                        int minutes, Outcome outcome, Double cancelHours) {
            Instant[] window = planner.windowAt(l, day, hour, minute, minutes);
            if (window == null) {
                return null;
            }
            return planner.addForced(l, driver, vehicle, window[0], window[1], outcome, cancelHours);
        }

        private void mark(Bk b, int rating, boolean reply, boolean dispute) {
            if (b == null) {
                return;
            }
            b.wantedRating = rating;
            b.forceReply = reply;
            b.forceDispute = dispute;
        }

        private Veh vehicleOf(Person p, VehicleType type) {
            return p.vehicles.stream().filter(v -> v.type == type).findFirst().orElse(null);
        }

        private Lst byTitle(List<Lst> listings, String title) {
            return listings.stream().filter(l -> l.title.equals(title)).findFirst().orElse(null);
        }

        /** A few future closures (maintenance, private events) on listings that have no booking then. */
        private void planBlocks(List<Lst> bookable) {
            String[] reasons = {"Scheduled maintenance", "Private event", "Resurfacing work", "Reserved for staff"};
            Instant todayStart = now.atZone(AvailabilityEvaluator.ZONE).toLocalDate().atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
            for (int attempt = 0; attempt < 40 && blockedListings.size() < 8; attempt++) {
                Lst l = bookable.get(rnd.nextInt(bookable.size()));
                if (blockedListings.contains(l)) {
                    continue;
                }
                Instant start = todayStart.plus(Duration.ofDays(3 + rnd.nextInt(9)).plusHours(8));
                Instant end = start.plus(Duration.ofHours(6 + rnd.nextInt(12)));
                boolean clash = bookings.stream().anyMatch(b -> b.listing == l && b.slot != null
                        && b.outcome != Outcome.EXPIRED && b.start.isBefore(end) && b.end.isAfter(start));
                if (clash) {
                    continue;
                }
                blockedListings.add(l);
                newBlocks.add(new Window(start, end));
                l.blocks.add(new Window(start, end));
                blockReasons.add(reasons[rnd.nextInt(reasons.length)]);
            }
        }

        final List<String> blockReasons = new ArrayList<>();

        // ---- ledger -----------------------------------------------------------------------------------------

        void buildLedger() {
            Set<Long> demoIds = Set.of(demoDriver.id, demoOwner.id);
            ledger = new DemoLedger(rnd, now, adminId, demoIds, bookings);
            rows = ledger.build(n -> nextIds("reviews", n), n -> nextIds("disputes", n), this::invoiceNumbers);
            auditAccountsAndListings();
        }

        private List<Long> invoiceNumbers(int n) {
            return jdbc.queryForList("select nextval('invoice_number_seq') from generate_series(1, ?)", Long.class, n)
                    .stream().sorted().toList();
        }

        private void auditAccountsAndListings() {
            for (int i = 0; i < newOwners.size(); i++) {
                Person p = newOwners.get(i);
                if (i < DemoCatalog.VERIFIED_NEW_OWNERS) {
                    ledger.audit(p.createdAt.plus(Duration.ofDays(1)), "OWNER_VERIFIED", "OWNER", p.id, null);
                } else if (i == newOwners.size() - 1) {
                    ledger.audit(p.createdAt.plus(Duration.ofDays(2)), "OWNER_REJECTED", "OWNER", p.id,
                            "Reason: " + REJECTION_REASON);
                }
            }
            for (Lst l : newListings) {
                switch (l.status) {
                    case APPROVED -> ledger.audit(l.approvedAt, "LISTING_APPROVED", "LISTING", l.id, null);
                    case REJECTED -> ledger.audit(l.submittedAt.plus(Duration.ofHours(20)), "LISTING_REJECTED",
                            "LISTING", l.id, "Reason: " + l.rejectionReason);
                    case SUSPENDED -> {
                        ledger.audit(l.approvedAt, "LISTING_APPROVED", "LISTING", l.id, null);
                        ledger.audit(now.minus(Duration.ofDays(9)), "LISTING_SUSPENDED", "LISTING", l.id,
                                "Reason: " + l.rejectionReason);
                    }
                    case PAUSED -> ledger.audit(l.approvedAt, "LISTING_APPROVED", "LISTING", l.id, null);
                    default -> { }
                }
            }
            Person suspended = newDrivers.get(newDrivers.size() - 1);
            ledger.audit(now.minus(Duration.ofDays(5)), "USER_SUSPENDED", "USER", suspended.id,
                    "Reason: Repeated chargebacks reported by owners");
        }

        String statusSummary() {
            Map<String, Long> counts = new java.util.TreeMap<>();
            for (Bk b : bookings) {
                counts.merge(b.status().name(), 1L, Long::sum);
            }
            return counts.toString();
        }

        // ---- writing ----------------------------------------------------------------------------------------

        void write() {
            fixPeopleDates();
            writePeople();
            writeListings();
            writeBookings();
            batch(INSERT_PAYMENT, rows.payments);
            batch(INSERT_EVENT, rows.bookingEvents);
            batch(INSERT_REFUND, rows.refunds);
            batch(INSERT_INVOICE, rows.invoices);
            batch(INSERT_EARNING, rows.earnings);
            batch(INSERT_REVIEW, rows.reviews);
            batch(INSERT_DISPUTE, rows.disputes);
            batch(INSERT_NOTIFICATION, rows.notifications);
            batch(INSERT_ADMIN_ACTION, rows.adminActions);
            refreshListingAggregates();
        }

        /** Vehicles are dated just after their owner's registration; the demo driver's are dated now. */
        private void fixPeopleDates() {
            for (Person p : newDrivers) {
                for (Veh v : p.vehicles) {
                    v.createdAt = p.createdAt.plus(Duration.ofMinutes(5 + rnd.nextInt(600)));
                }
            }
            for (Veh v : demoDriver.vehicles) {
                if (v.createdAt == null) {
                    v.createdAt = now;
                }
            }
        }

        private void writePeople() {
            List<Object[]> users = new ArrayList<>();
            List<Object[]> profiles = new ArrayList<>();
            for (int i = 0; i < newOwners.size(); i++) {
                Person p = newOwners.get(i);
                users.add(userRow(p));
                profiles.add(ownerProfileRow(p, i));
            }
            for (Person p : newDrivers) {
                users.add(userRow(p));
            }
            batch(INSERT_USER, users);
            batch(INSERT_OWNER_PROFILE, profiles);
            List<Object[]> vehicles = new ArrayList<>();
            List<Person> drivers = new ArrayList<>(newDrivers);
            drivers.add(demoDriver);
            for (Person p : drivers) {
                for (Veh v : p.vehicles) {
                    if (!v.existing) {
                        vehicles.add(new Object[] {v.id, p.id, v.type.name(), v.plate, v.makeModel, v.isDefault,
                                ts(v.createdAt), ts(v.createdAt)});
                    }
                }
            }
            batch(INSERT_VEHICLE, vehicles);
        }

        private Object[] userRow(Person p) {
            return new Object[] {p.id, p.name, p.email, p.phone, passwordHash, p.role.name(),
                    p.suspended ? "SUSPENDED" : "ACTIVE", p.emailVerified, ts(p.createdAt), ts(p.createdAt)};
        }

        private Object[] ownerProfileRow(Person p, int index) {
            int verifiedCount = DemoCatalog.VERIFIED_NEW_OWNERS;
            if (index < verifiedCount) {
                boolean bank = index % 3 == 0;
                String upi = bank ? null : p.firstName().toLowerCase() + "@okaxis";
                String account = bank ? "5010023" + (1000000 + rnd.nextInt(9000000)) : null;
                return new Object[] {p.id, "VERIFIED", "AADHAAR", null, null, null, upi, account,
                        bank ? "HDFC0001234" : null, p.name, null, ts(p.createdAt.plus(Duration.ofDays(1))),
                        ts(p.createdAt), ts(p.createdAt.plus(Duration.ofDays(1)))};
            }
            Instant submitted = p.createdAt.plus(Duration.ofHours(2 + rnd.nextInt(8)));
            if (index < verifiedCount + DemoCatalog.PENDING_NEW_OWNERS) {
                return new Object[] {p.id, "PENDING", index % 2 == 0 ? "PAN" : "DRIVING_LICENCE", documentKey,
                        documentContentType, ts(submitted), null, null, null, null, null, null, ts(p.createdAt),
                        ts(submitted)};
            }
            return new Object[] {p.id, "REJECTED", "PAN", documentKey, documentContentType, ts(submitted), null, null,
                    null, null, REJECTION_REASON, null, ts(p.createdAt), ts(p.createdAt.plus(Duration.ofDays(2)))};
        }

        private void writeListings() {
            List<Object[]> listings = new ArrayList<>();
            List<Object[]> amenities = new ArrayList<>();
            List<Object[]> photos = new ArrayList<>();
            List<Object[]> slots = new ArrayList<>();
            List<AvailabilityRule> hours = new ArrayList<>();
            for (Lst l : newListings) {
                Instant updated = l.approvedAt != null ? l.approvedAt : l.submittedAt != null ? l.submittedAt : l.createdAt;
                listings.add(new Object[] {l.id, l.owner.id, l.cityId, l.title, l.description, l.address, l.pincode,
                        l.lat, l.lng, l.type.name(), l.open24x7, RULES, l.autoApprove, l.status.name(),
                        l.rejectionReason, l.hour, l.day, l.month, l.policy.name(),
                        l.submittedAt == null ? null : ts(l.submittedAt),
                        l.approvedAt == null ? null : ts(l.approvedAt), ts(l.createdAt), ts(updated)});
                for (String a : l.amenities) {
                    amenities.add(new Object[] {l.id, a.trim()});
                }
                photos.add(new Object[] {l.id, "/seed/parking-" + l.photo + ".svg", null, 0, ts(l.createdAt), ts(l.createdAt)});
                photos.add(new Object[] {l.id, "/seed/parking-" + (l.photo % 6 + 1) + ".svg", null, 1, ts(l.createdAt),
                        ts(l.createdAt)});
                for (Slot s : l.slots) {
                    slots.add(new Object[] {s.id, l.id, s.label, s.type.name(),
                            s.type == VehicleType.FOUR_WHEELER ? "MEDIUM" : "SMALL", true, ts(l.createdAt),
                            ts(l.createdAt)});
                }
                if (!l.open24x7) {
                    for (int day = 1; day <= 7; day++) {
                        AvailabilityRule rule = new AvailabilityRule();
                        rule.setListing(em.getReference(ParkingListing.class, l.id));
                        rule.setDayOfWeek(day);
                        rule.setOpenTime(l.hours[day].open());
                        rule.setCloseTime(l.hours[day].close());
                        hours.add(rule);
                    }
                }
            }
            batch(INSERT_LISTING, listings);
            batch(INSERT_AMENITY, amenities);
            batch(INSERT_PHOTO, photos);
            batch(INSERT_SLOT, slots);
            persistRules(hours);
            List<Object[]> blocks = new ArrayList<>();
            for (int i = 0; i < blockedListings.size(); i++) {
                Window w = newBlocks.get(i);
                Instant created = now.minus(Duration.ofDays(1 + rnd.nextInt(10)));
                blocks.add(new Object[] {blockedListings.get(i).id, null, ts(w.start()), ts(w.end()),
                        blockReasons.get(i), ts(created), ts(created)});
            }
            batch(INSERT_BLOCK, blocks);
        }

        /** Weekly hours are saved through JPA (see {@link #loadListings}); the listings they point at exist by now. */
        private void persistRules(List<AvailabilityRule> hours) {
            hours.forEach(em::persist);
            em.flush();
        }

        private void writeBookings() {
            List<Object[]> out = new ArrayList<>();
            for (Bk b : bookings) {
                out.add(new Object[] {b.id, b.code, b.driver.id, b.listing.id, b.slot.id, b.vehicle.id,
                        b.vehicle.type.name(), b.vehicle.plate, ts(b.start), ts(b.end), b.quote.pricingMode().name(),
                        b.quote.breakdown(), b.quote.baseAmount(), b.quote.platformFee(), b.quote.gstAmount(),
                        b.quote.gstPercent(), b.quote.totalAmount(), b.refundTotal().setScale(2, RoundingMode.HALF_UP),
                        b.status().name(), b.holdExpiresAt == null ? null : ts(b.holdExpiresAt),
                        b.approvalDeadline == null ? null : ts(b.approvalDeadline),
                        b.confirmedAt == null ? null : ts(b.confirmedAt),
                        b.completedAt == null ? null : ts(b.completedAt),
                        b.reminderSentAt == null ? null : ts(b.reminderSentAt),
                        b.outcome.canceller() == null ? null : b.outcome.canceller().name(), b.cancelReason,
                        ts(b.createdAt), ts(b.updatedAt)});
            }
            batch(INSERT_BOOKING, out);
        }

        private void refreshListingAggregates() {
            List<Long> reviewIds = new ArrayList<>();
            for (Object[] r : rows.reviews) {
                reviewIds.add((Long) r[0]);
            }
            if (reviewIds.isEmpty()) {
                return;
            }
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        update parking_listings l set avg_rating = s.average, review_count = s.total
                        from (select listing_id, round(avg(rating)::numeric, 1) as average, count(*) as total
                              from reviews where hidden_at is null group by listing_id) s
                        where s.listing_id = l.id
                          and l.id in (select listing_id from reviews where id = any (?))""");
                setIds(con, ps, reviewIds);
                return ps;
            });
            // A listing whose only reviews are hidden has no visible ones: reset it (the update above skips it).
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        update parking_listings set avg_rating = 0, review_count = 0
                        where id in (select listing_id from reviews where id = any (?))
                          and not exists (select 1 from reviews r where r.listing_id = parking_listings.id
                                          and r.hidden_at is null)""");
                setIds(con, ps, reviewIds);
                return ps;
            });
        }

        private void setIds(java.sql.Connection con, PreparedStatement ps, List<Long> ids) throws SQLException {
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
        }
    }

    // ---- JDBC helpers ---------------------------------------------------------------------------------------

    private void batch(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }

    /** {@code n} fresh primary keys of the table, in ascending order. */
    private List<Long> nextIds(String table, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(jdbc.queryForList(
                "select nextval(pg_get_serial_sequence(?, 'id')) from generate_series(1, ?)", Long.class, table, n));
        Collections.sort(ids);
        return ids;
    }

    private static OffsetDateTime ts(Instant instant) {
        return DemoLedger.ts(instant);
    }
}
