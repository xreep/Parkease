package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.FileStorage.SignedUrl;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.ValidatedUpload;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A broken file store (e.g. a revoked Cloudinary key) must not stop the demo listings from being seeded. */
@IntegrationTest
class DemoListingSeederStorageFailureTest {

    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository ownerProfiles;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired ListingPhotoRepository photos;
    @Autowired ParkingSlotRepository slots;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired FileStorage storage;
    @Autowired Clock clock;

    @Test
    void listingsAreSeededAndThePendingOwnerStaysUnsubmittedWhenTheDocumentUploadFails() {
        FileStorage failingPrivate = new FileStorage() {
            public StoredFile storePublic(ValidatedUpload upload, String folder) { return storage.storePublic(upload, folder); }
            public StoredFile storePrivate(ValidatedUpload upload, String folder) {
                throw new RuntimeException("Invalid api_key 123");
            }
            public void delete(String key) { storage.delete(key); }
            public SignedUrl privateUrl(String key, Duration ttl) { return storage.privateUrl(key, ttl); }
            public String publicUrl(String key) { return storage.publicUrl(key); }
        };
        new DemoAccountSeeder(users, ownerProfiles, passwordEncoder, clock, "Demo@1234").seed();
        new DemoListingSeeder(users, ownerProfiles, cities, listings, photos, slots, rules, passwordEncoder,
                failingPrivate, clock, "Demo@1234").seed();

        assertThat(listings.findAll()).filteredOn(l -> l.getStatus() == ListingStatus.APPROVED).hasSizeGreaterThan(40);
        var pending = users.findByEmail("owner.pending@parkease.dev").orElseThrow();
        assertThat(ownerProfiles.findById(pending.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(VerificationStatus.UNSUBMITTED);
    }
}
