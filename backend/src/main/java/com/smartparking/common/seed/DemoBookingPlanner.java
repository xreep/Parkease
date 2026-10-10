package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.CancellationPolicyCalculator;
import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.Hours;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Outcome;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Slot;
import com.smartparking.common.seed.DemoModel.Veh;
import com.smartparking.common.seed.DemoModel.Window;
import com.smartparking.pricing.PricingService;
import com.smartparking.settings.PlatformSettings;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Plans the bookings of the demo: who parks where and when, how each one ended, and every timestamp and amount that
 * follows from it. Everything is derived from one seeded {@link Random} and the current time, in a fixed order, so a
 * run is reproducible. Amounts come from {@link PricingService}, refunds from the real cancellation policy rules, and
 * slots are allocated so that no slot is ever double booked. Nothing here touches the database.
 */
final class DemoBookingPlanner {

    /** About how many bookings the demo has. */
    static final int TARGET = 505;
    static final int DAYS_BACK = 90;
    static final int DAYS_AHEAD = 14;

    private static final ZoneId IST = AvailabilityEvaluator.ZONE;
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int MAX_ATTEMPTS = 8000;
    /** Relative popularity of each start hour (IST). */
    private static final double[] HOUR_WEIGHT = {0.3, 0.2, 0.2, 0.2, 0.3, 0.5, 1.0, 2.0, 3.0, 5.0, 5.0, 4.0, 4.0, 3.0,
            3.0, 3.0, 3.0, 4.0, 5.0, 4.0, 2.0, 1.0, 0.5, 0.4};
    private static final int[] DURATIONS = {60, 90, 120, 150, 180, 240, 300, 360, 480, 600};
    private static final double[] DURATION_WEIGHT_CAR = {2.0, 1.0, 3.0, 1.5, 3.0, 2.5, 1.5, 1.5, 1.0, 0.5};
    private static final double[] DURATION_WEIGHT_BIKE = {3.0, 1.5, 3.5, 1.5, 2.5, 1.5, 0.8, 0.6, 0.3, 0.1};

    private final Random rnd;
    private final Instant now;
    private final PricingService pricing;
    private final int holdMinutes;
    private final int approvalHours;
    private final List<Lst> listings;
    private final List<Person> drivers;
    private final LocalDate today;
    private final double[] dayCumulative;
    private final double[] listingCumulative;
    private final double[] driverCumulative;

    private final List<Bk> planned = new ArrayList<>();
    private final Set<String> codes = new HashSet<>();

    DemoBookingPlanner(Random rnd, Instant now, PricingService pricing, PlatformSettings settings,
                       List<Lst> bookableListings, List<Person> bookableDrivers) {
        this.rnd = rnd;
        this.now = now;
        this.pricing = pricing;
        this.holdMinutes = settings.holdMinutes();
        this.approvalHours = settings.approvalHours();
        this.listings = bookableListings;
        this.drivers = bookableDrivers;
        this.today = now.atZone(IST).toLocalDate();
        this.dayCumulative = cumulativeDays();
        this.listingCumulative = cumulative(listings.stream().mapToDouble(l -> l.weight).toArray());
        this.driverCumulative = cumulative(drivers.stream().mapToDouble(p -> p.weight).toArray());
    }

    List<Bk> planned() {
        return planned;
    }

    /** Fills the rest of the bookings with random ones, after any showcase bookings were added. */
    void planRandom() {
        int attempts = 0;
        while (planned.size() < TARGET && attempts++ < MAX_ATTEMPTS) {
            Person driver = drivers.get(pick(driverCumulative));
            Veh vehicle = driver.vehicles.get(rnd.nextInt(driver.vehicles.size()));
            Lst listing = listings.get(pick(listingCumulative));
            if (!listing.hasSlotFor(vehicle.type)) {
                continue;
            }
            Instant[] window = sampleWindow(listing, vehicle.type);
            if (window == null) {
                continue;
            }
            Outcome outcome = pickOutcome(listing, window[0], window[1]);
            add(listing, driver, vehicle, window[0], window[1], outcome, null);
        }
    }

    // ---- showcase -------------------------------------------------------------------------------------------

    /** Quarter-hour aligned window on the IST day {@code dayOffset} from today, or null when the listing is closed then. */
    Instant[] windowAt(Lst l, int dayOffset, int hour, int minute, int minutes) {
        ZonedDateTime s = today.plusDays(dayOffset).atTime(hour, minute).atZone(IST);
        Instant start = s.toInstant();
        Instant end = start.plus(Duration.ofMinutes(minutes));
        return fits(l, start, end) ? new Instant[] {start, end} : null;
    }

    Instant floorQuarter(Instant t) {
        long s = t.getEpochSecond();
        return Instant.ofEpochSecond(s - Math.floorMod(s, 900));
    }

    /**
     * Adds one chosen booking. {@code cancelHoursBefore} fixes when a driver cancellation happens (hours before the
     * start); null lets the planner draw it. Returns the booking, or null when no slot was free.
     */
    Bk addForced(Lst l, Person driver, Veh vehicle, Instant start, Instant end, Outcome outcome,
                 Double cancelHoursBefore) {
        return add(l, driver, vehicle, start, end, outcome, cancelHoursBefore);
    }

    // ---- planning one booking -------------------------------------------------------------------------------

    private Bk add(Lst l, Person driver, Veh vehicle, Instant start, Instant end, Outcome outcome,
                   Double cancelHoursBefore) {
        Bk b = new Bk();
        b.listing = l;
        b.driver = driver;
        b.vehicle = vehicle;
        b.start = start;
        b.end = end;
        b.outcome = outcome;
        b.quote = pricing.quote(l.hour, l.day, l.month, start, end);
        if (!timeline(b, cancelHoursBefore)) {
            return null;
        }
        Slot slot = outcome == Outcome.EXPIRED ? anySlot(l, vehicle.type) : allocate(l, vehicle.type, start, end);
        if (slot == null) {
            return null;
        }
        b.slot = slot;
        if (outcome != Outcome.EXPIRED) {
            slot.busy.add(new Window(start, end));
        }
        if (!driver.existing && driver.createdAt.isAfter(b.createdAt.minus(Duration.ofDays(1)))) {
            driver.createdAt = b.createdAt.minus(Duration.ofDays(1)).minusSeconds(rnd.nextInt(14 * 86_400));
        }
        b.code = newCode();
        planned.add(b);
        return b;
    }

    private Slot allocate(Lst l, VehicleType type, Instant start, Instant end) {
        for (Window w : l.blocks) {
            if (w.overlaps(start, end)) {
                return null;
            }
        }
        List<Slot> candidates = l.slots.stream().filter(s -> s.type == type).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        int offset = rnd.nextInt(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Slot s = candidates.get((offset + i) % candidates.size());
            if (s.isFree(start, end)) {
                return s;
            }
        }
        return null;
    }

    private Slot anySlot(Lst l, VehicleType type) {
        List<Slot> candidates = l.slots.stream().filter(s -> s.type == type).toList();
        return candidates.isEmpty() ? null : candidates.get(rnd.nextInt(candidates.size()));
    }

    private String newCode() {
        while (true) {
            StringBuilder sb = new StringBuilder("PK-");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET.charAt(rnd.nextInt(CODE_ALPHABET.length())));
            }
            if (codes.add(sb.toString())) {
                return sb.toString();
            }
        }
    }

    // ---- outcomes -------------------------------------------------------------------------------------------

    private Outcome pickOutcome(Lst l, Instant start, Instant end) {
        double r = rnd.nextDouble();
        boolean needsApproval = !l.autoApprove;
        if (!end.isAfter(now)) { // already over
            if (needsApproval) {
                if (r < 0.07) {
                    return Outcome.REJECTED_OWNER;
                }
                if (r < 0.12) {
                    return Outcome.REJECTED_SYSTEM;
                }
                if (r < 0.22) {
                    return pickCancellation(true);
                }
                return r < 0.26 ? Outcome.EXPIRED : Outcome.COMPLETED;
            }
            if (r < 0.12) {
                return pickCancellation(false);
            }
            return r < 0.17 ? Outcome.EXPIRED : Outcome.COMPLETED;
        }
        if (!start.isAfter(now)) {
            return Outcome.ACTIVE;
        }
        if (needsApproval && Duration.between(now, start).toHours() >= 3 && r < 0.5) {
            return Outcome.AWAITING;
        }
        if (r < 0.45) {
            return Outcome.CONFIRMED;
        }
        double r2 = rnd.nextDouble();
        if (r2 < 0.08) {
            return pickCancellation(needsApproval);
        }
        if (needsApproval && r2 < 0.12) {
            return Outcome.REJECTED_OWNER;
        }
        return Outcome.CONFIRMED;
    }

    private Outcome pickCancellation(boolean needsApproval) {
        double r = rnd.nextDouble();
        if (r < 0.62) {
            return needsApproval && rnd.nextDouble() < 0.22 ? Outcome.CANCELLED_DRIVER_AWAITING : Outcome.CANCELLED_DRIVER;
        }
        return r < 0.85 ? Outcome.CANCELLED_OWNER : Outcome.CANCELLED_ADMIN;
    }

    // ---- timeline -------------------------------------------------------------------------------------------

    /** Fills every timestamp and refund of the booking; false when the outcome cannot happen for this window. */
    private boolean timeline(Bk b, Double cancelHoursBefore) {
        Outcome o = b.outcome;
        boolean needsApproval = !b.listing.autoApprove;
        Instant start = b.start;
        BigDecimal total = b.quote.totalAmount();
        Duration approvalWindow = Duration.ofHours(approvalHours);
        // A request needs a few hours to be answered before the parking starts.
        Instant latestCreated = now.minus(Duration.ofMinutes(needsApproval ? 130 : 3));

        switch (o) {
            case AWAITING -> {
                if (!needsApproval || Duration.between(now, start).toHours() < 3) {
                    return false;
                }
                b.createdAt = now.minus(Duration.ofSeconds(180 + rnd.nextInt(97 * 60)));
                b.paidAt = b.createdAt.plusSeconds(30 + rnd.nextInt(150));
                b.approvalDeadline = min(b.paidAt.plus(approvalWindow), start);
            }
            case COMPLETED, ACTIVE, CONFIRMED, REJECTED_OWNER, REJECTED_SYSTEM -> {
                if (o.isRejected() && !needsApproval) {
                    return false;
                }
                b.createdAt = createdBefore(start, needsApproval, latestCreated);
                b.paidAt = b.createdAt.plusSeconds(30 + rnd.nextInt(210));
                if (needsApproval) {
                    b.approvalDeadline = min(b.paidAt.plus(approvalWindow), start);
                    if (o == Outcome.REJECTED_OWNER) {
                        b.decidedAt = b.paidAt.plusSeconds(8 * 60 + rnd.nextInt(92 * 60));
                    } else if (o == Outcome.REJECTED_SYSTEM) {
                        if (b.approvalDeadline.plusSeconds(60).isAfter(now)) {
                            return false;
                        }
                        b.decidedAt = b.approvalDeadline.plusSeconds(5 + rnd.nextInt(50));
                    } else {
                        b.approvedAt = b.paidAt.plusSeconds(6 * 60 + rnd.nextInt(94 * 60));
                        b.decidedAt = b.approvedAt;
                    }
                }
                if (o.isRejected()) {
                    b.cancelledFrom = BookingStatus.AWAITING_APPROVAL;
                    b.cancelReason = o == Outcome.REJECTED_SYSTEM
                            ? "The owner didn't respond within " + approvalHours + (approvalHours == 1 ? " hour" : " hours")
                            : pickOne(DemoCatalog.OWNER_REJECT_REASONS);
                    b.refund = total;
                } else {
                    b.confirmedAt = needsApproval ? b.approvedAt : b.paidAt;
                    if (o == Outcome.COMPLETED) {
                        b.completedAt = b.end.plusSeconds(10 + rnd.nextInt(50));
                    }
                }
            }
            case EXPIRED -> {
                if (b.end.isAfter(now)) {
                    return false;
                }
                b.createdAt = createdBefore(start, needsApproval, latestCreated);
                b.holdExpiresAt = b.createdAt.plus(Duration.ofMinutes(holdMinutes));
                b.decidedAt = b.holdExpiresAt.plusSeconds(20 + rnd.nextInt(50));
            }
            case CANCELLED_DRIVER_AWAITING -> {
                if (!needsApproval) {
                    return false;
                }
                b.createdAt = createdBefore(start, true, latestCreated);
                b.paidAt = b.createdAt.plusSeconds(30 + rnd.nextInt(210));
                b.approvalDeadline = min(b.paidAt.plus(approvalWindow), start);
                b.decidedAt = b.paidAt.plusSeconds(5 * 60 + rnd.nextInt(90 * 60));
                b.cancelledFrom = BookingStatus.AWAITING_APPROVAL;
                b.cancelReason = pickOne(DemoCatalog.DRIVER_CANCEL_REASONS);
                b.refund = total;
            }
            case CANCELLED_DRIVER, CANCELLED_OWNER, CANCELLED_ADMIN -> {
                double hoursBefore = cancelHoursBefore != null ? cancelHoursBefore : drawHoursBefore();
                Instant cancelAt = start.minusSeconds((long) (hoursBefore * 3600));
                Instant latestCancel = now.minusSeconds(120);
                if (cancelAt.isAfter(latestCancel)) {
                    if (cancelHoursBefore != null) {
                        return false;
                    }
                    cancelAt = latestCancel.minusSeconds(rnd.nextInt(6 * 3600));
                    if (!cancelAt.isBefore(start)) {
                        return false;
                    }
                }
                long minLead = needsApproval ? 130 * 60 : 20 * 60;
                b.createdAt = cancelAt.minusSeconds(minLead + rnd.nextInt(2 * 86_400 - (int) minLead));
                b.paidAt = b.createdAt.plusSeconds(30 + rnd.nextInt(210));
                if (needsApproval) {
                    b.approvalDeadline = min(b.paidAt.plus(approvalWindow), start);
                    b.approvedAt = b.paidAt.plusSeconds(6 * 60 + rnd.nextInt(94 * 60));
                }
                b.confirmedAt = needsApproval ? b.approvedAt : b.paidAt;
                b.decidedAt = cancelAt;
                b.cancelledFrom = BookingStatus.CONFIRMED;
                switch (o) {
                    case CANCELLED_DRIVER -> {
                        b.cancelReason = rnd.nextInt(4) == 0 ? null : pickOne(DemoCatalog.DRIVER_CANCEL_REASONS);
                        b.refund = CancellationPolicyCalculator.preview(b.listing.policy, start, cancelAt,
                                b.quote.baseAmount()).refundAmount();
                    }
                    case CANCELLED_OWNER -> {
                        b.cancelReason = pickOne(DemoCatalog.OWNER_CANCEL_REASONS);
                        b.refund = total;
                    }
                    default -> {
                        b.cancelReason = pickOne(DemoCatalog.ADMIN_CANCEL_REASONS);
                        b.refund = total;
                    }
                }
            }
        }
        return true;
    }

    /** A creation time {@code lead} before the start, but early enough to have been answered by now. */
    private Instant createdBefore(Instant start, boolean needsApproval, Instant latestCreated) {
        Instant created = start.minusSeconds(lead(needsApproval));
        if (created.isAfter(latestCreated)) {
            created = latestCreated.minusSeconds(rnd.nextInt(86_400));
        }
        return created;
    }

    /** Seconds between booking and start: mostly same day, sometimes days ahead; requests need hours of notice. */
    private long lead(boolean needsApproval) {
        double r = rnd.nextDouble();
        long seconds;
        if (r < 0.40) {
            seconds = 20 * 60 + rnd.nextInt(160 * 60);
        } else if (r < 0.75) {
            seconds = 3 * 3600 + rnd.nextInt(21 * 3600);
        } else {
            seconds = 86_400 + rnd.nextInt(5 * 86_400);
        }
        return needsApproval ? Math.max(seconds, 3 * 3600 + rnd.nextInt(2 * 3600)) : seconds;
    }

    /** Hours before the start at which a cancellation happens, spread over every refund tier of every policy. */
    private double drawHoursBefore() {
        double r = rnd.nextDouble();
        if (r < 0.30) {
            return 48 + rnd.nextDouble() * 122;
        }
        if (r < 0.50) {
            return 24 + rnd.nextDouble() * 24;
        }
        if (r < 0.72) {
            return 2 + rnd.nextDouble() * 22;
        }
        if (r < 0.86) {
            return 1 + rnd.nextDouble();
        }
        return 0.1 + rnd.nextDouble() * 0.9;
    }

    // ---- windows --------------------------------------------------------------------------------------------

    /** A start and end for a random booking, inside the listing's opening hours; null when nothing fits. */
    private Instant[] sampleWindow(Lst l, VehicleType type) {
        LocalDate date = today.plusDays(pick(dayCumulative) - DAYS_BACK);
        if (l.open24x7) {
            double r = rnd.nextDouble();
            if (type == VehicleType.FOUR_WHEELER && r < 0.04) {
                return window(date, 9 + rnd.nextInt(3), 0, 30 * 1440);
            }
            if (type == VehicleType.FOUR_WHEELER && r < 0.16) {
                int minutes = (1 + rnd.nextInt(6)) * 1440 + rnd.nextInt(3) * 180;
                return window(date, hourOfDay(0, 23), quarter(), minutes);
            }
            return window(date, hourOfDay(0, 23), quarter(), duration(type));
        }
        Hours h = l.hours[date.getDayOfWeek().getValue()];
        if (h == null) {
            return null;
        }
        int open = h.open().getHour() * 60 + h.open().getMinute();
        int close = h.close().getHour() * 60 + h.close().getMinute();
        int minutes = Math.min(duration(type), close - open);
        if (minutes < 60) {
            return null;
        }
        int latestStart = close - minutes;
        for (int i = 0; i < 10; i++) {
            int hour = hourOfDay(open / 60, Math.min(latestStart / 60, 23));
            int minute = hour * 60 + quarter();
            if (minute >= open && minute <= latestStart) {
                return window(date, minute / 60, minute % 60, minutes);
            }
        }
        int fallback = latestStart - Math.floorMod(latestStart, 15);
        return window(date, fallback / 60, fallback % 60, minutes);
    }

    private Instant[] window(LocalDate date, int hour, int minute, int minutes) {
        Instant start = date.atTime(hour, minute).atZone(IST).toInstant();
        return new Instant[] {start, start.plus(Duration.ofMinutes(minutes))};
    }

    private int hourOfDay(int from, int to) {
        double total = 0;
        for (int h = from; h <= to; h++) {
            total += HOUR_WEIGHT[h];
        }
        double r = rnd.nextDouble() * total;
        for (int h = from; h <= to; h++) {
            r -= HOUR_WEIGHT[h];
            if (r <= 0) {
                return h;
            }
        }
        return to;
    }

    private int quarter() {
        double r = rnd.nextDouble();
        return r < 0.6 ? 0 : r < 0.85 ? 30 : r < 0.925 ? 15 : 45;
    }

    private int duration(VehicleType type) {
        double[] w = type == VehicleType.TWO_WHEELER ? DURATION_WEIGHT_BIKE : DURATION_WEIGHT_CAR;
        double total = 0;
        for (double x : w) {
            total += x;
        }
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) {
                return DURATIONS[i];
            }
        }
        return DURATIONS[0];
    }

    /** True when the window lies inside the opening hours of the listing, in one IST day (24x7 listings: always). */
    boolean fits(Lst l, Instant start, Instant end) {
        if (l.open24x7) {
            return true;
        }
        ZonedDateTime s = start.atZone(IST);
        ZonedDateTime e = end.atZone(IST);
        if (!s.toLocalDate().equals(e.toLocalDate())) {
            return false;
        }
        Hours h = l.hours[s.getDayOfWeek().getValue()];
        return h != null && !s.toLocalTime().isBefore(h.open()) && !e.toLocalTime().isAfter(h.close());
    }

    // ---- sampling helpers -----------------------------------------------------------------------------------

    private double[] cumulativeDays() {
        double[] c = new double[DAYS_BACK + DAYS_AHEAD + 1];
        double sum = 0;
        for (int i = 0; i < c.length; i++) {
            int offset = i - DAYS_BACK;
            double w = offset <= 0 ? 1 + 0.9 * (offset + DAYS_BACK) / DAYS_BACK : 1.1;
            switch (today.plusDays(offset).getDayOfWeek()) {
                case SATURDAY -> w *= 1.25;
                case SUNDAY -> w *= 1.1;
                default -> { }
            }
            sum += w;
            c[i] = sum;
        }
        return c;
    }

    private static double[] cumulative(double[] weights) {
        double[] c = new double[weights.length];
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i];
            c[i] = sum;
        }
        return c;
    }

    private int pick(double[] cumulative) {
        double r = rnd.nextDouble() * cumulative[cumulative.length - 1];
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < r) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private String pickOne(List<String> options) {
        return options.get(rnd.nextInt(options.size()));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
