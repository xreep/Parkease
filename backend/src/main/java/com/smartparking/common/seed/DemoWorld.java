package com.smartparking.common.seed;

import com.smartparking.common.seed.DemoModel.Bk;
import com.smartparking.common.seed.DemoModel.ListingBlock;
import com.smartparking.common.seed.DemoModel.Lst;
import com.smartparking.common.seed.DemoModel.Person;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Everything one seeding run knows: the clock, the random source, the people, listings and bookings so far. */
final class DemoWorld {

    final Random rnd;
    final Instant now;
    final String passwordHash;

    long adminId;
    Person demoOwner;
    Person demoDriver;
    /** The owners of the listing seeder, by email. */
    final Map<String, Person> existingOwners = new LinkedHashMap<>();
    /** Owners by the key the listings file uses: "demo" and o01.. */
    final Map<String, Person> ownerByKey = new HashMap<>();
    final List<Person> newOwners = new ArrayList<>();
    final List<Person> newDrivers = new ArrayList<>();
    /** Approved listings that exist or were created now: where bookings can go. */
    final List<Lst> approvedListings = new ArrayList<>();
    /** Every listing the seeder creates, whatever its status. */
    final List<Lst> newListings = new ArrayList<>();
    final List<Bk> bookings = new ArrayList<>();
    final List<ListingBlock> blocks = new ArrayList<>();
    /** The owner document the pending and rejected owners share. */
    String documentKey;
    String documentContentType;

    DemoWorld(Random rnd, Instant now, String passwordHash) {
        this.rnd = rnd;
        this.now = now;
        this.passwordHash = passwordHash;
    }
}
