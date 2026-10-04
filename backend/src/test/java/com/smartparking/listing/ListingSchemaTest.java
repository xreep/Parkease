package com.smartparking.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.model.VehicleType;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.owner.DocumentType;
import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.TestUsers;
import com.smartparking.user.Role;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@IntegrationTest
class ListingSchemaTest {

    @Autowired ParkingListingRepository listings;
    @Autowired ListingPhotoRepository photos;
    @Autowired ParkingSlotRepository slots;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired AvailabilityBlockRepository blocks;
    @Autowired OwnerProfileRepository ownerProfiles;
    @Autowired UserRepository users;
    @Autowired CityRepository cities;
    @Autowired EntityManager em;

    private ParkingListing newListing(User owner, City city) {
        ParkingListing l = new ParkingListing();
        l.setOwner(owner);
        l.setCity(city);
        l.setTitle("Test");
        l.setAddress("Addr");
        l.setPincode("411001");
        l.setLat(18.52);
        l.setLng(73.85);
        l.setListingType(ListingType.OFFICE);
        l.setAmenities(new java.util.HashSet<>(Set.of(Amenity.CCTV, Amenity.COVERED)));
        l.setPricePerHour(new BigDecimal("30.00"));
        return listings.save(l);
    }

    private ParkingSlot newSlot(ParkingListing listing, String label) {
        ParkingSlot s = new ParkingSlot();
        s.setListing(listing);
        s.setLabel(label);
        s.setVehicleType(VehicleType.FOUR_WHEELER);
        s.setSize(SlotSize.MEDIUM);
        return s;
    }

    @Test
    void persistsListingWithAmenitiesPhotosSlotsRulesAndBlocks() {
        User owner = users.save(TestUsers.newUser("schema@example.com", Role.OWNER));
        City pune = cities.findBySlugs("maharashtra", "pune").orElseThrow();
        ParkingListing listing = newListing(owner, pune);

        ListingPhoto photo = new ListingPhoto();
        photo.setListing(listing);
        photo.setUrl("/x.png");
        photo.setSortOrder(0);
        photos.save(photo);

        slots.save(newSlot(listing, "A-01"));

        AvailabilityRule rule = new AvailabilityRule();
        rule.setListing(listing);
        rule.setDayOfWeek(1);
        rule.setOpenTime(LocalTime.of(9, 0));
        rule.setCloseTime(LocalTime.of(18, 0));
        rules.save(rule);

        Instant now = Instant.now();
        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listing);
        block.setStartTime(now.plus(1, ChronoUnit.HOURS));
        block.setEndTime(now.plus(3, ChronoUnit.HOURS));
        blocks.save(block);

        em.flush();
        em.clear();

        ParkingListing reloaded = listings.findById(listing.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(reloaded.getAmenities()).containsExactlyInAnyOrder(Amenity.CCTV, Amenity.COVERED);
        assertThat(photos.countByListingId(listing.getId())).isEqualTo(1);
        assertThat(slots.countByListingIdAndActiveTrue(listing.getId())).isEqualTo(1);
        assertThat(rules.findByListingIdOrderByDayOfWeekAsc(listing.getId())).hasSize(1);
        assertThat(blocks.findByListingIdAndEndTimeAfterOrderByStartTimeAsc(listing.getId(), now)).hasSize(1);
        assertThat(reloaded.getCancellationPolicy()).isEqualTo(CancellationPolicy.MODERATE);
        assertThat(reloaded.isAutoApprove()).isTrue();
    }

    @Test
    void slotLabelsAreUniquePerListingCaseInsensitive() {
        User owner = users.save(TestUsers.newUser("schema2@example.com", Role.OWNER));
        City pune = cities.findBySlugs("maharashtra", "pune").orElseThrow();
        ParkingListing listing = newListing(owner, pune);
        slots.saveAndFlush(newSlot(listing, "a-01"));

        ParkingSlot second = newSlot(listing, "A-01");
        assertThatThrownBy(() -> {
            slots.saveAndFlush(second);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownerProfileStoresDocumentFields() {
        User owner = users.save(TestUsers.newUser("schema3@example.com", Role.OWNER));
        OwnerProfile profile = OwnerProfile.forUser(owner);
        Instant submitted = Instant.now().truncatedTo(ChronoUnit.MICROS);
        profile.setDocumentType(DocumentType.AADHAAR);
        profile.setDocumentKey("local/private/x");
        profile.setDocumentContentType("application/pdf");
        profile.setDocumentSubmittedAt(submitted);
        ownerProfiles.saveAndFlush(profile);
        em.clear();

        OwnerProfile reloaded = ownerProfiles.findById(owner.getId()).orElseThrow();
        assertThat(reloaded.getDocumentType()).isEqualTo(DocumentType.AADHAAR);
        assertThat(reloaded.getDocumentKey()).isEqualTo("local/private/x");
        assertThat(reloaded.getDocumentContentType()).isEqualTo("application/pdf");
        assertThat(reloaded.getDocumentSubmittedAt()).isEqualTo(submitted);
    }
}
