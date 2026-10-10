package com.smartparking.common.seed;

import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Veh;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.ValidatedUpload;
import com.smartparking.user.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** The people of the demo: the existing logins, ~30 owners (a few unverified) and ~50 drivers with their vehicles. */
final class DemoAccounts {

    static final int DRIVERS = 50;

    private static final String PENDING_OWNER_EMAIL = "owner.pending@parkease.dev";
    private static final byte[] DOCUMENT_PDF = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj "
            + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]>>endobj\n"
            + "trailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.US_ASCII);

    private final JdbcTemplate jdbc;
    private final DemoIds ids;
    private final FileStorage storage;
    private final DemoWorld world;
    private final Random rnd;
    private final Set<String> emails = new HashSet<>();
    private final Set<String> plates = new HashSet<>();
    private final List<Long> vehicleIds = new ArrayList<>();

    DemoAccounts(JdbcTemplate jdbc, DemoIds ids, FileStorage storage, DemoWorld world) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.storage = storage;
        this.world = world;
        this.rnd = world.rnd;
    }

    // ---- the logins that exist already -----------------------------------------------------------------------

    /** Finds the demo admin, owner, driver and the listing seeder's owners; the demo driver gets both kinds of vehicle. */
    void loadExisting() {
        world.adminId = require(DemoAccountSeeder.ADMIN_EMAIL).id;
        world.demoOwner = require(DemoAccountSeeder.OWNER_EMAIL);
        world.demoDriver = require(DemoAccountSeeder.DRIVER_EMAIL);
        world.demoOwner.role = Role.OWNER;
        world.demoDriver.role = Role.DRIVER;
        for (String email : DemoCatalog.EXISTING_OWNER_EMAILS) {
            Person p = email.equals(DemoAccountSeeder.OWNER_EMAIL) ? world.demoOwner : find(email);
            if (p != null) {
                p.role = Role.OWNER;
                world.existingOwners.put(email, p);
            }
        }
        world.ownerByKey.put("demo", world.demoOwner);
        emails.addAll(jdbc.queryForList("select email from users", String.class));
        plates.addAll(jdbc.queryForList("select plate_number from vehicles", String.class));
        loadDemoDriverVehicles();
        loadDocument();
    }

    private Person require(String email) {
        Person p = find(email);
        if (p == null) {
            throw new IllegalStateException("Demo account " + email + " is missing; "
                    + "DemoAccountSeeder and DemoListingSeeder must run first");
        }
        return p;
    }

    private Person find(String email) {
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

    private void loadDemoDriverVehicles() {
        Person driver = world.demoDriver;
        jdbc.query("select id, type, plate_number, make_model, is_default, created_at from vehicles"
                + " where user_id = ? order by id", rs -> {
            Veh v = new Veh();
            v.id = rs.getLong(1);
            v.userId = driver.id;
            v.type = VehicleType.valueOf(rs.getString(2));
            v.plate = rs.getString(3);
            v.makeModel = rs.getString(4);
            v.isDefault = rs.getBoolean(5);
            v.createdAt = rs.getTimestamp(6).toInstant();
            v.existing = true;
            driver.vehicles.add(v);
        }, driver.id);
        // The showcase needs a car and a two-wheeler; a driver who has only one kind gets the other.
        for (VehicleType type : VehicleType.values()) {
            if (driver.vehicles.stream().noneMatch(v -> v.type == type)) {
                boolean car = type == VehicleType.FOUR_WHEELER;
                String plate = car ? "MH02DX4521" : "MH02KL7788";
                while (!plates.add(plate)) {
                    plate = DemoCatalog.plate(rnd);
                }
                Veh v = vehicle(driver, type, plate, car ? "Hyundai Creta" : "Honda Activa 6G",
                        driver.vehicles.isEmpty());
                v.id = ids.next("vehicles", 1).get(0);
                driver.vehicles.add(v);
            }
        }
    }

    /** The pending owner of the listing seeder already has a document on file; the new unverified owners share it. */
    private void loadDocument() {
        List<String[]> found = jdbc.query("select p.document_key, p.document_content_type from owner_profiles p"
                + " join users u on u.id = p.user_id where u.email = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, PENDING_OWNER_EMAIL);
        if (!found.isEmpty() && found.get(0)[0] != null) {
            world.documentKey = found.get(0)[0];
            world.documentContentType = found.get(0)[1];
        } else {
            StoredFile stored = storage.storePrivate(new ValidatedUpload(DOCUMENT_PDF, "application/pdf", "pdf"),
                    "owner-documents");
            world.documentKey = stored.key();
            world.documentContentType = stored.contentType();
        }
    }

    // ---- new people -------------------------------------------------------------------------------------------

    void createOwners() {
        int count = DemoCatalog.NEW_OWNER_NAMES.size();
        List<Long> userIds = ids.next("users", count);
        for (int i = 0; i < count; i++) {
            Person p = new Person();
            p.id = userIds.get(i);
            p.name = DemoCatalog.NEW_OWNER_NAMES.get(i);
            p.email = uniqueEmail(p.name);
            p.phone = DemoCatalog.phone(rnd);
            p.role = Role.OWNER;
            boolean verified = i < DemoCatalog.VERIFIED_NEW_OWNERS;
            boolean pending = !verified && i < DemoCatalog.VERIFIED_NEW_OWNERS + DemoCatalog.PENDING_NEW_OWNERS;
            p.createdAt = world.now.minus(Duration.ofDays(verified ? 125 + rnd.nextInt(26)
                    : pending ? 1 + rnd.nextInt(6) : 18 + rnd.nextInt(8)));
            world.newOwners.add(p);
            world.ownerByKey.put("o%02d".formatted(i + 1), p);
        }
    }

    void createDrivers() {
        List<Long> userIds = ids.next("users", DRIVERS);
        vehicleIds.addAll(ids.next("vehicles", 2 * DRIVERS)); // at most two each; unused keys are simply skipped
        Set<String> names = new HashSet<>();
        for (int i = 0; i < DRIVERS; i++) {
            Person p = new Person();
            p.id = userIds.get(i);
            String name;
            do {
                name = DemoCatalog.FIRST_NAMES.get(rnd.nextInt(DemoCatalog.FIRST_NAMES.size())) + " "
                        + DemoCatalog.LAST_NAMES.get(rnd.nextInt(DemoCatalog.LAST_NAMES.size()));
            } while (!names.add(name));
            p.name = name;
            p.email = uniqueEmail(name);
            p.phone = DemoCatalog.phone(rnd);
            p.role = Role.DRIVER;
            boolean unverified = i >= DRIVERS - 4 && i < DRIVERS - 1;
            p.suspended = i == DRIVERS - 1;
            p.emailVerified = !unverified;
            p.createdAt = world.now.minus(Duration.ofDays(unverified ? 1 + rnd.nextInt(8) : 4 + rnd.nextInt(115)));
            p.weight = unverified || p.suspended ? 0 : 0.3 + rnd.nextDouble() * rnd.nextDouble() * 3;
            addVehicles(p);
            world.newDrivers.add(p);
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
            List<String> makes = type == VehicleType.FOUR_WHEELER ? DemoCatalog.FOUR_WHEELERS : DemoCatalog.TWO_WHEELERS;
            Veh v = vehicle(p, type, plate, makes.get(rnd.nextInt(makes.size())), k == 0);
            v.id = vehicleIds.remove(0);
            p.vehicles.add(v);
        }
    }

    private static Veh vehicle(Person owner, VehicleType type, String plate, String make, boolean isDefault) {
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
}
