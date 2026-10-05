package com.smartparking.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.ListingPhoto;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class DemoListingSeederTest {

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
    @Autowired EntityManager em;

    DemoListingSeeder listingSeeder;

    @BeforeEach
    void seedTwice() {
        DemoAccountSeeder accountSeeder =
                new DemoAccountSeeder(users, ownerProfiles, passwordEncoder, clock, "Demo@1234");
        listingSeeder = new DemoListingSeeder(users, ownerProfiles, cities, listings, photos, slots, rules,
                passwordEncoder, storage, clock, "Demo@1234");
        accountSeeder.seed();
        listingSeeder.seed();
        listingSeeder.seed();
        em.flush();
        em.clear();
    }

    private ParkingListing byTitle(String title) {
        return listings.findAll().stream().filter(l -> l.getTitle().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void seedsApprovedListingsInEveryStateAndOnePendingReview() {
        assertThat(listings.countByStatus(ListingStatus.APPROVED)).isEqualTo(55);
        assertThat(listings.countByStatus(ListingStatus.PENDING_REVIEW)).isEqualTo(1);
        Set<String> states = listings.findAll().stream()
                .filter(l -> l.getStatus() == ListingStatus.APPROVED)
                .map(l -> l.getCity().getState().getCode())
                .collect(Collectors.toSet());
        assertThat(states).hasSize(36);
        ParkingListing pending = byTitle("Viman Nagar Residency Parking");
        assertThat(pending.getStatus()).isEqualTo(ListingStatus.PENDING_REVIEW);
        assertThat(pending.getSubmittedAt()).isNotNull();
        assertThat(pending.getOwner().getEmail()).isEqualTo(DemoAccountSeeder.OWNER_EMAIL);
    }

    @Test
    void seedsVerifiedAndPendingOwners() {
        Long pendingId = users.findByEmail("owner.pending@parkease.dev").orElseThrow().getId();
        OwnerProfile pending = ownerProfiles.findById(pendingId).orElseThrow();
        assertThat(pending.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(pending.getDocumentKey()).isNotNull();
        assertThat(pending.getDocumentSubmittedAt()).isNotNull();

        Long northId = users.findByEmail("owner.north@parkease.dev").orElseThrow().getId();
        OwnerProfile north = ownerProfiles.findById(northId).orElseThrow();
        assertThat(north.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(north.getPayoutUpi()).isEqualTo("amit@okaxis");
    }

    @Test
    void seedsFullDetailForTwentyFourSevenListing() {
        ParkingListing l = byTitle("MG Road Metro Parking");
        assertThat(l.isOpen24x7()).isTrue();
        assertThat(l.getPricePerHour()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(l.getAmenities()).hasSize(4);
        assertThat(l.getApprovedAt()).isNotNull();

        List<ParkingSlot> all = slots.findByListingIdOrderByLabelAsc(l.getId());
        List<String> twoWheeler = all.stream().filter(s -> s.getVehicleType() == VehicleType.TWO_WHEELER)
                .map(ParkingSlot::getLabel).toList();
        List<String> fourWheeler = all.stream().filter(s -> s.getVehicleType() == VehicleType.FOUR_WHEELER)
                .map(ParkingSlot::getLabel).toList();
        assertThat(twoWheeler).hasSize(8).first().isEqualTo("B-01");
        assertThat(twoWheeler).last().isEqualTo("B-08");
        assertThat(fourWheeler).hasSize(12).first().isEqualTo("A-01");
        assertThat(fourWheeler).last().isEqualTo("A-12");
        assertThat(all).allMatch(ParkingSlot::isActive);
        assertThat(photos.findByListingIdOrderBySortOrderAsc(l.getId())).hasSize(2)
                .extracting(ListingPhoto::getUrl).containsExactly("/seed/parking-1.svg", "/seed/parking-2.svg");
        assertThat(rules.findByListingIdOrderByDayOfWeekAsc(l.getId())).isEmpty();
    }

    @Test
    void seedsWeeklyHoursForNormalListing() {
        ParkingListing l = byTitle("Sitabuldi Main Road Parking");
        assertThat(l.isOpen24x7()).isFalse();
        List<AvailabilityRule> hours = rules.findByListingIdOrderByDayOfWeekAsc(l.getId());
        assertThat(hours).hasSize(7);
        assertThat(hours.get(0).getOpenTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(hours.get(0).getCloseTime()).isEqualTo(LocalTime.of(22, 0));
        AvailabilityRule sunday = hours.get(6);
        assertThat(sunday.getDayOfWeek()).isEqualTo(7);
        assertThat(sunday.getOpenTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(sunday.getCloseTime()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void seedingAgainChangesNothing() {
        long listingsBefore = listings.count();
        long slotsBefore = slots.count();
        long photosBefore = photos.count();
        long usersBefore = users.count();
        String documentKey = ownerProfiles.findById(
                users.findByEmail("owner.pending@parkease.dev").orElseThrow().getId()).orElseThrow().getDocumentKey();

        listingSeeder.seed();
        em.flush();
        em.clear();

        assertThat(listings.count()).isEqualTo(listingsBefore);
        assertThat(slots.count()).isEqualTo(slotsBefore);
        assertThat(photos.count()).isEqualTo(photosBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(ownerProfiles.findById(users.findByEmail("owner.pending@parkease.dev").orElseThrow().getId())
                .orElseThrow().getDocumentKey()).isEqualTo(documentKey);
        assertThat(listings.countByStatus(ListingStatus.APPROVED)).isEqualTo(55);
        assertThat(listings.countByStatus(ListingStatus.PENDING_REVIEW)).isEqualTo(1);
    }
}
