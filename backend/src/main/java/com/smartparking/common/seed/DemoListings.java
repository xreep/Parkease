package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoModel.Hours;
import com.smartparking.common.seed.DemoModel.ListingBlock;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Outcome;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Slot;
import com.smartparking.common.seed.DemoModel.Window;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The parking places of the demo: the approved listings of the listing seeder (read, never changed) plus ~40 more in
 * the major cities (from {@code seed/demo-activity-listings.psv}) in every listing status, and a few future closures.
 */
final class DemoListings {

    private static final String LISTINGS_FILE = "seed/demo-activity-listings.psv";

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final DemoIds ids;
    private final DemoWorld world;
    private final Random rnd;

    DemoListings(JdbcTemplate jdbc, EntityManager em, DemoIds ids, DemoWorld world) {
        this.jdbc = jdbc;
        this.em = em;
        this.ids = ids;
        this.world = world;
        this.rnd = world.rnd;
    }

    // ---- listings that exist already -------------------------------------------------------------------------

    /** Approved listings of the listing seeder's owners, with their slots, hours and what already occupies them. */
    void loadExisting() {
        if (world.existingOwners.isEmpty()) {
            return;
        }
        Map<Long, Person> ownerById = new HashMap<>();
        world.existingOwners.values().forEach(p -> ownerById.put(p.id, p));
        Object[] ownerIds = ownerById.keySet().toArray();
        Map<Long, Lst> listingById = new LinkedHashMap<>();
        jdbc.query("select l.id, l.owner_id, l.city_id, c.tier, l.title, l.listing_type, l.open_24x7,"
                + " l.auto_approve, l.cancellation_policy, l.price_per_hour, l.price_per_day, l.price_per_month"
                + " from parking_listings l join cities c on c.id = l.city_id"
                + " where l.status = 'APPROVED' and l.owner_id in (" + DemoIds.placeholders(ownerIds.length)
                + ") order by l.id", rs -> {
            Lst l = new Lst();
            l.id = rs.getLong(1);
            l.owner = ownerById.get(rs.getLong(2));
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
        }, ownerIds);
        if (listingById.isEmpty()) {
            return;
        }
        Object[] listingIds = listingById.keySet().toArray();
        String in = DemoIds.placeholders(listingIds.length);
        Map<Long, Slot> slotById = new HashMap<>();
        jdbc.query("select id, listing_id, label, vehicle_type from parking_slots where active"
                + " and listing_id in (" + in + ") order by listing_id, label", rs -> {
            Slot s = new Slot();
            s.id = rs.getLong(1);
            s.label = rs.getString(3);
            s.type = VehicleType.valueOf(rs.getString(4));
            listingById.get(rs.getLong(2)).slots.add(s);
            slotById.put(s.id, s);
        }, listingIds);
        // Opening hours go through JPA, exactly like the availability checks read them: Hibernate stores times of day
        // in the JDBC time zone, so the raw column values only make sense when read back the same way.
        em.createQuery("select r from AvailabilityRule r where r.listing.id in :ids", AvailabilityRule.class)
                .setParameter("ids", listingById.keySet()).getResultList()
                .forEach(r -> listingById.get(r.getListing().getId()).hours[r.getDayOfWeek()] =
                        new Hours(r.getOpenTime(), r.getCloseTime()));
        jdbc.query("select listing_id, slot_id, start_time, end_time from availability_blocks"
                + " where listing_id in (" + in + ")", rs -> {
            Window w = new Window(rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant());
            long slotId = rs.getLong(2);
            if (rs.wasNull()) {
                listingById.get(rs.getLong(1)).blocks.add(w);
            } else if (slotById.containsKey(slotId)) {
                slotById.get(slotId).busy.add(w);
            }
        }, listingIds);
        jdbc.query("select slot_id, start_time, end_time from bookings where status in ('PENDING_PAYMENT',"
                + " 'AWAITING_APPROVAL','CONFIRMED','ACTIVE') and listing_id in (" + in + ")", rs -> {
            Slot s = slotById.get(rs.getLong(1));
            if (s != null) {
                s.busy.add(new Window(rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()));
            }
        }, listingIds);
        world.approvedListings.addAll(listingById.values());
    }

    // ---- listings from the file --------------------------------------------------------------------------------

    /** Columns: owner|state|city|title|address|pincode|lat|lng|type|hour|day|month|2W|4W|24x7|amenities|photo|policy|auto|status|note */
    void createFromFile() {
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
        List<Long> listingIds = ids.next("parking_listings", lines.size());
        List<Long> slotIds = ids.next("parking_slots", slotCount);
        int slotIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i);
            Person owner = world.ownerByKey.get(f[0]);
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
            world.newListings.add(l);
            if (l.status == ListingStatus.APPROVED) {
                world.approvedListings.add(l);
            }
        }
        world.approvedListings.forEach(this::weigh);
    }

    private void dateListing(Lst l) {
        Instant now = world.now;
        boolean demo = l.owner == world.demoOwner;
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
        double ownerFactor = l.owner == world.demoOwner ? 2.2 : 1.0;
        double tierFactor = l.tier == 1 ? 5.0 : l.tier == 2 ? 2.5 : 1.0;
        l.weight = tierFactor * ownerFactor * Math.sqrt(Math.max(1, l.slots.size())) * (0.6 + 0.8 * rnd.nextDouble());
    }

    private static Slot newSlot(long id, String label, VehicleType type) {
        Slot s = new Slot();
        s.id = id;
        s.label = label;
        s.type = type;
        return s;
    }

    private static List<String[]> readRows() {
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

    // ---- closures ---------------------------------------------------------------------------------------------

    /** A few future closures (maintenance, private events) on listings that have no booking then. */
    void planBlocks(List<Lst> bookable) {
        String[] reasons = {"Scheduled maintenance", "Private event", "Resurfacing work", "Reserved for staff"};
        Instant todayStart = world.now.atZone(AvailabilityEvaluator.ZONE).toLocalDate()
                .atStartOfDay(AvailabilityEvaluator.ZONE).toInstant();
        for (int attempt = 0; attempt < 40 && world.blocks.size() < 8; attempt++) {
            Lst l = bookable.get(rnd.nextInt(bookable.size()));
            if (world.blocks.stream().anyMatch(b -> b.listing() == l)) {
                continue;
            }
            Instant start = todayStart.plus(Duration.ofDays(3 + rnd.nextInt(9)).plusHours(8));
            Instant end = start.plus(Duration.ofHours(6 + rnd.nextInt(12)));
            boolean clash = world.bookings.stream().anyMatch(b -> b.listing == l && b.outcome != Outcome.EXPIRED
                    && b.start.isBefore(end) && b.end.isAfter(start));
            if (!clash) {
                world.blocks.add(new ListingBlock(l, new Window(start, end), reasons[rnd.nextInt(reasons.length)]));
            }
        }
    }
}
