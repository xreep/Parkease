package com.smartparking.common.seed;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingPhoto;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.DocumentType;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.ValidatedUpload;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo data for the dev and demo profiles: verified owners per region, 55 approved listings covering every state and union territory,
 * plus one pending owner and one pending listing so the admin review queues are not empty. Safe to run repeatedly.
 */
@Slf4j
@Component
@Profile({"dev", "demo"})
@Order(2)
public class DemoListingSeeder implements ApplicationRunner {

    private static final String LISTINGS_FILE = "seed/demo-listings.psv";
    private static final String PENDING_OWNER_EMAIL = "owner.pending@parkease.dev";
    private static final String WEST = "west";
    private static final String RULES = "Park only in your assigned slot. Follow staff instructions.";
    private static final byte[] DOCUMENT_PDF = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj "
            + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]>>endobj\n"
            + "trailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.US_ASCII);

    private record DemoOwner(String key, String email, String name, String phone) {
    }

    private static final List<DemoOwner> NEW_OWNERS = List.of(
            new DemoOwner("north", "owner.north@parkease.dev", "Amit Khanna", "9000000011"),
            new DemoOwner("south", "owner.south@parkease.dev", "Karthik Iyer", "9000000012"),
            new DemoOwner("east", "owner.east@parkease.dev", "Sourav Das", "9000000013"),
            new DemoOwner("northeast", "owner.northeast@parkease.dev", "Lalremruata Pachuau", "9000000014"),
            new DemoOwner("central", "owner.central@parkease.dev", "Neha Joshi", "9000000015"));

    private final UserRepository users;
    private final OwnerProfileRepository ownerProfiles;
    private final CityRepository cities;
    private final ParkingListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final PasswordEncoder passwordEncoder;
    private final FileStorage storage;
    private final Clock clock;
    private final String demoPassword;

    public DemoListingSeeder(UserRepository users, OwnerProfileRepository ownerProfiles, CityRepository cities,
                             ParkingListingRepository listings, ListingPhotoRepository photos,
                             ParkingSlotRepository slots, AvailabilityRuleRepository rules,
                             PasswordEncoder passwordEncoder, FileStorage storage, Clock clock,
                             @Value("${app.seed.demo-password}") String demoPassword) {
        this.users = users;
        this.ownerProfiles = ownerProfiles;
        this.cities = cities;
        this.listings = listings;
        this.photos = photos;
        this.slots = slots;
        this.rules = rules;
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

    @Transactional
    public void seed() {
        Map<String, User> owners = new LinkedHashMap<>();
        owners.put(WEST, users.findByEmail(DemoAccountSeeder.OWNER_EMAIL).orElseThrow(() ->
                new IllegalStateException("Demo owner " + DemoAccountSeeder.OWNER_EMAIL + " is missing; "
                        + "DemoAccountSeeder must run first")));
        for (DemoOwner o : NEW_OWNERS) {
            owners.put(o.key(), ensureVerifiedOwner(o));
        }
        ensurePendingOwner();

        int created = 0;
        for (String[] row : readRows()) {
            if (seedApprovedListing(row, owners)) {
                created++;
            }
        }
        if (seedPendingListing(owners.get(WEST))) {
            created++;
        }
        log.info("Demo listings ready ({} created this run)", created);
    }

    // ---- owners ----

    private User ensureVerifiedOwner(DemoOwner o) {
        User user = ensureOwnerUser(o.email(), o.name(), o.phone());
        OwnerProfile profile = profileFor(user);
        if (profile.getVerificationStatus() != VerificationStatus.VERIFIED) {
            profile.setVerificationStatus(VerificationStatus.VERIFIED);
            profile.setDocumentType(DocumentType.AADHAAR);
            profile.setPayoutUpi(o.name().split("\\s+")[0].toLowerCase() + "@okaxis");
            profile.setPayoutAccountName(o.name());
            profile.setVerifiedAt(clock.instant());
        }
        return user;
    }

    private void ensurePendingOwner() {
        User user = ensureOwnerUser(PENDING_OWNER_EMAIL, "Vikram Singh", "9000000016");
        OwnerProfile profile = profileFor(user);
        if (profile.getVerificationStatus() == VerificationStatus.UNSUBMITTED && profile.getDocumentKey() == null) {
            StoredFile stored;
            try {
                stored = storage.storePrivate(new ValidatedUpload(DOCUMENT_PDF, "application/pdf", "pdf"),
                        "owner-documents");
            } catch (RuntimeException e) {
                // A file-store problem (e.g. a wrong or revoked Cloudinary key) must not stop the whole demo from
                // starting: this owner just stays unsubmitted and the next start tries again.
                log.warn("Could not store the sample document of {} ({}); it stays unsubmitted for now",
                        PENDING_OWNER_EMAIL, e.getClass().getSimpleName());
                return;
            }
            profile.setVerificationStatus(VerificationStatus.PENDING);
            profile.setDocumentType(DocumentType.DRIVING_LICENCE);
            profile.setDocumentKey(stored.key());
            profile.setDocumentContentType(stored.contentType());
            profile.setDocumentSubmittedAt(clock.instant());
        }
    }

    private User ensureOwnerUser(String email, String name, String phone) {
        return users.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPhone(phone);
            user.setRole(Role.OWNER);
            user.setEmailVerified(true);
            user.setPasswordHash(passwordEncoder.encode(demoPassword));
            return users.save(user);
        });
    }

    private OwnerProfile profileFor(User user) {
        return ownerProfiles.findById(user.getId()).orElseGet(() -> ownerProfiles.save(OwnerProfile.forUser(user)));
    }

    // ---- listings ----

    /** Columns: owner|stateSlug|citySlug|title|address|pincode|lat|lng|type|hour|day|month|2W|4W|24x7|amenities|photo */
    private boolean seedApprovedListing(String[] f, Map<String, User> owners) {
        User owner = owners.get(f[0]);
        if (owner == null) {
            throw new IllegalStateException("Unknown owner " + f[0] + " in demo-listings.psv");
        }
        String title = f[3];
        if (listings.existsByOwnerIdAndTitle(owner.getId(), title)) {
            return false;
        }
        City city = cities.findBySlugs(f[1], f[2]).orElseThrow(() ->
                new IllegalStateException("Unknown city " + f[1] + "/" + f[2] + " in demo-listings.psv"));
        ListingType type = ListingType.valueOf(f[8]);
        boolean open24x7 = Boolean.parseBoolean(f[14]);
        Set<Amenity> amenities = EnumSet.noneOf(Amenity.class);
        if (!f[15].isBlank()) {
            Arrays.stream(f[15].split(";")).map(String::trim).map(Amenity::valueOf).forEach(amenities::add);
        }
        Instant now = clock.instant();

        ParkingListing l = newListing(owner, city, title, f[4], f[5], Double.parseDouble(f[6]),
                Double.parseDouble(f[7]), type, open24x7, new BigDecimal(f[9]), new BigDecimal(f[10]),
                new BigDecimal(f[11]), amenities);
        l.setDescription("Secure " + type.name().toLowerCase() + " parking at " + title
                + ". Reserve in advance on ParkEase.");
        l.setStatus(ListingStatus.APPROVED);
        l.setSubmittedAt(now);
        l.setApprovedAt(now);
        l = listings.save(l);

        addSlots(l, Integer.parseInt(f[12]), Integer.parseInt(f[13]));
        addHours(l);
        int photo = Integer.parseInt(f[16]);
        addPhoto(l, photo, 0);
        addPhoto(l, photo % 6 + 1, 1);
        return true;
    }

    private boolean seedPendingListing(User owner) {
        String title = "Viman Nagar Residency Parking";
        if (listings.existsByOwnerIdAndTitle(owner.getId(), title)) {
            return false;
        }
        City pune = cities.findBySlugs("maharashtra", "pune").orElseThrow(() ->
                new IllegalStateException("Unknown city maharashtra/pune in demo data"));
        ParkingListing l = newListing(owner, pune, title, "Off Airport Road, Viman Nagar, Pune", "411014",
                18.5679, 73.9143, ListingType.RESIDENTIAL, false, new BigDecimal("25"), new BigDecimal("150"),
                new BigDecimal("2500"), EnumSet.of(Amenity.COVERED, Amenity.CCTV));
        l.setDescription("Secure residential parking at " + title + ". Reserve in advance on ParkEase.");
        l.setStatus(ListingStatus.PENDING_REVIEW);
        l.setSubmittedAt(clock.instant());
        l = listings.save(l);
        addSlots(l, 4, 4);
        addHours(l);
        addPhoto(l, 3, 0);
        return true;
    }

    private ParkingListing newListing(User owner, City city, String title, String address, String pincode,
                                      double lat, double lng, ListingType type, boolean open24x7,
                                      BigDecimal hour, BigDecimal day, BigDecimal month, Set<Amenity> amenities) {
        ParkingListing l = new ParkingListing();
        l.setOwner(owner);
        l.setCity(city);
        l.setTitle(title);
        l.setAddress(address);
        l.setPincode(pincode);
        l.setLat(lat);
        l.setLng(lng);
        l.setListingType(type);
        l.setOpen24x7(open24x7);
        l.setRules(RULES);
        l.setAutoApprove(true);
        l.setCancellationPolicy(CancellationPolicy.MODERATE);
        l.setPricePerHour(hour.setScale(2));
        l.setPricePerDay(day.setScale(2));
        l.setPricePerMonth(month.setScale(2));
        l.getAmenities().addAll(amenities);
        return l;
    }

    private void addSlots(ParkingListing l, int twoWheelers, int fourWheelers) {
        List<ParkingSlot> all = new ArrayList<>();
        for (int i = 1; i <= fourWheelers; i++) {
            all.add(slot(l, "A-%02d".formatted(i), VehicleType.FOUR_WHEELER, SlotSize.MEDIUM));
        }
        for (int i = 1; i <= twoWheelers; i++) {
            all.add(slot(l, "B-%02d".formatted(i), VehicleType.TWO_WHEELER, SlotSize.SMALL));
        }
        slots.saveAll(all);
    }

    private static ParkingSlot slot(ParkingListing l, String label, VehicleType vehicleType, SlotSize size) {
        ParkingSlot s = new ParkingSlot();
        s.setListing(l);
        s.setLabel(label);
        s.setVehicleType(vehicleType);
        s.setSize(size);
        s.setActive(true);
        return s;
    }

    /** Mon-Sat 08:00-22:00 and Sun 09:00-21:00 unless the listing is open 24x7 (no rules then). */
    private void addHours(ParkingListing l) {
        if (l.isOpen24x7()) {
            return;
        }
        List<AvailabilityRule> all = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            AvailabilityRule r = new AvailabilityRule();
            r.setListing(l);
            r.setDayOfWeek(day);
            r.setOpenTime(day == 7 ? LocalTime.of(9, 0) : LocalTime.of(8, 0));
            r.setCloseTime(day == 7 ? LocalTime.of(21, 0) : LocalTime.of(22, 0));
            all.add(r);
        }
        rules.saveAll(all);
    }

    private void addPhoto(ParkingListing l, int imageNumber, int sortOrder) {
        ListingPhoto p = new ListingPhoto();
        p.setListing(l);
        p.setUrl("/seed/parking-" + imageNumber + ".svg");
        p.setStorageKey(null);
        p.setSortOrder(sortOrder);
        photos.save(p);
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
                if (fields.length != 17) {
                    throw new IllegalStateException("Bad row in demo-listings.psv: " + line);
                }
                rows.add(fields);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + LISTINGS_FILE, e);
        }
        return rows;
    }
}
