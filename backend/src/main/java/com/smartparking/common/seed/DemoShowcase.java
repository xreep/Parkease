package com.smartparking.common.seed;

import com.smartparking.common.model.VehicleType;
import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Outcome;
import com.smartparking.common.seed.DemoModel.Person;
import com.smartparking.common.seed.DemoModel.Veh;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Bookings that make the demo logins look lived in: one of every state for the driver, requests for the owner. */
@Slf4j
final class DemoShowcase {

    private final DemoWorld world;
    private final DemoBookingPlanner planner;

    DemoShowcase(DemoWorld world, DemoBookingPlanner planner) {
        this.world = world;
        this.planner = planner;
    }

    void plan(List<Lst> bookable, List<Person> drivers) {
        Person demo = world.demoDriver;
        Lst andheri = byTitle(bookable, "Andheri Metro Station Parking");
        Lst bkc = byTitle(bookable, "BKC Office Parking");
        Lst cyber = byTitle(bookable, "Cyber City Office Parking");
        Lst powai = byTitle(bookable, "Powai Hiranandani Mall Parking");
        Lst bandra = byTitle(bookable, "Bandra West Society Parking");
        Lst koregaon = byTitle(bookable, "Koregaon Park Garage");
        Veh car = vehicleOf(demo, VehicleType.FOUR_WHEELER);
        Veh bike = vehicleOf(demo, VehicleType.TWO_WHEELER);
        List<Person> carDrivers = drivers.stream().filter(d -> vehicleOf(d, VehicleType.FOUR_WHEELER) != null).toList();
        if (car == null || bike == null || andheri == null || bkc == null || cyber == null || powai == null
                || bandra == null || koregaon == null || carDrivers.size() < 6) {
            log.warn("Some demo listings, vehicles or drivers are missing; skipping the showcase bookings");
            return;
        }
        // The demo driver's own bookings: one of everything.
        Instant startedAt = planner.floorQuarter(world.now.minus(Duration.ofMinutes(70)));
        planner.addForced(andheri, demo, car, startedAt, startedAt.plus(Duration.ofHours(4)), Outcome.ACTIVE, null);
        show(bkc, demo, car, 1, 10, 0, 300, Outcome.CONFIRMED, null);
        show(cyber, demo, car, 5, 10, 0, 420, Outcome.CONFIRMED, null);
        show(bandra, demo, car, 2, 11, 0, 180, Outcome.AWAITING, null);
        mark(show(andheri, demo, car, -2, 9, 0, 180, Outcome.COMPLETED, null), 0, false, true);
        mark(show(powai, demo, car, -9, 10, 0, 180, Outcome.COMPLETED, null), 5, false, false);
        mark(show(koregaon, demo, car, -16, 10, 0, 240, Outcome.COMPLETED, null), 4, true, false);
        mark(show(andheri, demo, bike, -33, 8, 30, 150, Outcome.COMPLETED, null), 3, false, false);
        mark(show(bkc, demo, car, -58, 9, 0, 360, Outcome.COMPLETED, null), -1, false, false);
        mark(show(cyber, demo, car, -1, 10, 0, 420, Outcome.COMPLETED, null), -1, false, false);
        show(bkc, demo, car, -12, 14, 0, 240, Outcome.CANCELLED_DRIVER, 10.0);
        show(bandra, demo, car, -20, 10, 0, 120, Outcome.REJECTED_OWNER, null);
        show(andheri, demo, bike, -40, 8, 0, 180, Outcome.EXPIRED, null);

        // The demo owner's requests waiting for an answer, a live booking and a poor review, from other drivers.
        show(bandra, carDrivers.get(0), 1, 10, 0, 180, Outcome.AWAITING);
        show(koregaon, carDrivers.get(1), 3, 9, 30, 240, Outcome.AWAITING);
        show(bandra, carDrivers.get(2), 4, 14, 0, 240, Outcome.AWAITING);
        show(powai, carDrivers.get(3), 2, 10, 0, 300, Outcome.CONFIRMED);
        Instant powaiStart = planner.floorQuarter(world.now.minus(Duration.ofMinutes(40)));
        planner.addForced(powai, carDrivers.get(4), vehicleOf(carDrivers.get(4), VehicleType.FOUR_WHEELER),
                powaiStart, powaiStart.plus(Duration.ofHours(3)), Outcome.ACTIVE, null);
        mark(show(powai, carDrivers.get(5), -3, 10, 0, 180, Outcome.COMPLETED), 2, true, false);
    }

    private Bk show(Lst l, Person driver, int day, int hour, int minute, int minutes, Outcome outcome) {
        return show(l, driver, vehicleOf(driver, VehicleType.FOUR_WHEELER), day, hour, minute, minutes, outcome, null);
    }

    private Bk show(Lst l, Person driver, Veh vehicle, int day, int hour, int minute, int minutes, Outcome outcome,
                    Double cancelHours) {
        Instant[] window = planner.windowAt(l, day, hour, minute, minutes);
        if (window == null) {
            return null;
        }
        return planner.addForced(l, driver, vehicle, window[0], window[1], outcome, cancelHours);
    }

    private static void mark(Bk b, int rating, boolean reply, boolean dispute) {
        if (b != null) {
            b.wantedRating = rating;
            b.forceReply = reply;
            b.forceDispute = dispute;
        }
    }

    private static Veh vehicleOf(Person p, VehicleType type) {
        return p.vehicles.stream().filter(v -> v.type == type).findFirst().orElse(null);
    }

    private static Lst byTitle(List<Lst> listings, String title) {
        return listings.stream().filter(l -> l.title.equals(title)).findFirst().orElse(null);
    }
}
