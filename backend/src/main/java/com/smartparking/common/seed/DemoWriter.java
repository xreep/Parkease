package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoLedger.Rows;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.ListingBlock;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Slot;
import com.smartparking.common.seed.DemoModel.Veh;
import com.smartparking.listing.ParkingListing;
import jakarta.persistence.EntityManager;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes the planned world to the database in batches, parents before children. */
final class DemoWriter {

    private static final String RULES = "Park only in your assigned slot. Follow staff instructions.";
    static final String REJECTION_REASON = "The document was unreadable. Please upload a clear photo of your ID.";

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
            + " approval_nudge_sent_at, cancelled_by, cancel_reason, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
    private final DemoWorld world;
    private final Rows rows;

    DemoWriter(JdbcTemplate jdbc, EntityManager em, DemoWorld world, Rows rows) {
        this.jdbc = jdbc;
        this.em = em;
        this.world = world;
        this.rows = rows;
    }

    void writeAll() {
        writePeople();
        writeListings();
        writeBlocks();
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

    // ---- people -----------------------------------------------------------------------------------------------

    private void writePeople() {
        Instant now = world.now;
        for (Person p : world.newDrivers) { // vehicles are dated just after their owner registered
            for (Veh v : p.vehicles) {
                v.createdAt = p.createdAt.plus(Duration.ofMinutes(5 + world.rnd.nextInt(600)));
            }
        }
        world.demoDriver.vehicles.stream().filter(v -> v.createdAt == null).forEach(v -> v.createdAt = now);

        List<Object[]> users = new ArrayList<>();
        List<Object[]> profiles = new ArrayList<>();
        for (int i = 0; i < world.newOwners.size(); i++) {
            Person p = world.newOwners.get(i);
            users.add(userRow(p));
            profiles.add(ownerProfileRow(p, i));
        }
        world.newDrivers.forEach(p -> users.add(userRow(p)));
        batch(INSERT_USER, users);
        batch(INSERT_OWNER_PROFILE, profiles);

        List<Object[]> vehicles = new ArrayList<>();
        List<Person> drivers = new ArrayList<>(world.newDrivers);
        drivers.add(world.demoDriver);
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
        return new Object[] {p.id, p.name, p.email, p.phone, world.passwordHash, p.role.name(),
                p.suspended ? "SUSPENDED" : "ACTIVE", p.emailVerified, ts(p.createdAt), ts(p.createdAt)};
    }

    private Object[] ownerProfileRow(Person p, int index) {
        int verifiedCount = DemoCatalog.VERIFIED_NEW_OWNERS;
        if (index < verifiedCount) {
            boolean bank = index % 3 == 0;
            String upi = bank ? null : p.firstName().toLowerCase() + "@okaxis";
            String account = bank ? "5010023" + (1000000 + world.rnd.nextInt(9000000)) : null;
            return new Object[] {p.id, "VERIFIED", "AADHAAR", null, null, null, upi, account,
                    bank ? "HDFC0001234" : null, p.name, null, ts(p.createdAt.plus(Duration.ofDays(1))),
                    ts(p.createdAt), ts(p.createdAt.plus(Duration.ofDays(1)))};
        }
        Instant submitted = p.createdAt.plus(Duration.ofHours(2 + world.rnd.nextInt(8)));
        if (index < verifiedCount + DemoCatalog.PENDING_NEW_OWNERS) {
            return new Object[] {p.id, "PENDING", index % 2 == 0 ? "PAN" : "DRIVING_LICENCE", world.documentKey,
                    world.documentContentType, ts(submitted), null, null, null, null, null, null, ts(p.createdAt),
                    ts(submitted)};
        }
        return new Object[] {p.id, "REJECTED", "PAN", world.documentKey, world.documentContentType, ts(submitted),
                null, null, null, null, REJECTION_REASON, null, ts(p.createdAt),
                ts(p.createdAt.plus(Duration.ofDays(2)))};
    }

    // ---- listings ---------------------------------------------------------------------------------------------

    private void writeListings() {
        List<Object[]> listings = new ArrayList<>();
        List<Object[]> amenities = new ArrayList<>();
        List<Object[]> photos = new ArrayList<>();
        List<Object[]> slots = new ArrayList<>();
        List<AvailabilityRule> hours = new ArrayList<>();
        for (Lst l : world.newListings) {
            Instant updated = l.approvedAt != null ? l.approvedAt : l.submittedAt != null ? l.submittedAt : l.createdAt;
            listings.add(new Object[] {l.id, l.owner.id, l.cityId, l.title, l.description, l.address, l.pincode,
                    l.lat, l.lng, l.type.name(), l.open24x7, RULES, l.autoApprove, l.status.name(),
                    l.rejectionReason, l.hour, l.day, l.month, l.policy.name(),
                    l.submittedAt == null ? null : ts(l.submittedAt),
                    l.approvedAt == null ? null : ts(l.approvedAt), ts(l.createdAt), ts(updated)});
            l.amenities.forEach(a -> amenities.add(new Object[] {l.id, a.trim()}));
            photos.add(new Object[] {l.id, "/seed/parking-" + l.photo + ".svg", null, 0, ts(l.createdAt),
                    ts(l.createdAt)});
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
        // Weekly hours are saved through JPA, like they are read: Hibernate keeps times of day in the JDBC time zone.
        hours.forEach(em::persist);
        em.flush();
    }

    private void writeBlocks() {
        List<Object[]> blocks = new ArrayList<>();
        for (ListingBlock b : world.blocks) {
            Instant created = world.now.minus(Duration.ofDays(1 + world.rnd.nextInt(10)));
            blocks.add(new Object[] {b.listing().id, null, ts(b.window().start()), ts(b.window().end()), b.reason(),
                    ts(created), ts(created)});
        }
        batch(INSERT_BLOCK, blocks);
    }

    // ---- bookings ---------------------------------------------------------------------------------------------

    private void writeBookings() {
        List<Object[]> out = new ArrayList<>();
        for (Bk b : world.bookings) {
            out.add(new Object[] {b.id, b.code, b.driver.id, b.listing.id, b.slot.id, b.vehicle.id,
                    b.vehicle.type.name(), b.vehicle.plate, ts(b.start), ts(b.end), b.quote.pricingMode().name(),
                    b.quote.breakdown(), b.quote.baseAmount(), b.quote.platformFee(), b.quote.gstAmount(),
                    b.quote.gstPercent(), b.quote.totalAmount(), b.refundTotal().setScale(2, RoundingMode.HALF_UP),
                    b.status().name(), opt(b.holdExpiresAt), opt(b.approvalDeadline), opt(b.confirmedAt),
                    opt(b.completedAt), opt(b.reminderSentAt), opt(b.approvalNudgeSentAt),
                    b.outcome.canceller() == null ? null : b.outcome.canceller().name(), b.cancelReason,
                    ts(b.createdAt), ts(b.updatedAt)});
        }
        batch(INSERT_BOOKING, out);
    }

    /** Listings that got reviews show the average and count of their visible ones, as ReviewService keeps them. */
    private void refreshListingAggregates() {
        Long[] reviewIds = rows.reviews.stream().map(r -> (Long) r[0]).toArray(Long[]::new);
        if (reviewIds.length == 0) {
            return;
        }
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    update parking_listings l set avg_rating = s.average, review_count = s.total
                    from (select listing_id, round(avg(rating)::numeric, 1) as average, count(*) as total
                          from reviews where hidden_at is null group by listing_id) s
                    where s.listing_id = l.id
                      and l.id in (select listing_id from reviews where id = any (?))""");
            ps.setArray(1, con.createArrayOf("bigint", reviewIds));
            return ps;
        });
        // A listing whose only reviews are hidden has no visible ones: the update above skips it.
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    update parking_listings set avg_rating = 0, review_count = 0
                    where id in (select listing_id from reviews where id = any (?))
                      and not exists (select 1 from reviews r where r.listing_id = parking_listings.id
                                      and r.hidden_at is null)""");
            ps.setArray(1, con.createArrayOf("bigint", reviewIds));
            return ps;
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void batch(String sql, List<Object[]> batchRows) {
        if (!batchRows.isEmpty()) {
            jdbc.batchUpdate(sql, batchRows);
        }
    }

    private static OffsetDateTime opt(Instant instant) {
        return instant == null ? null : ts(instant);
    }

    private static OffsetDateTime ts(Instant instant) {
        return DemoLedger.ts(instant);
    }
}
