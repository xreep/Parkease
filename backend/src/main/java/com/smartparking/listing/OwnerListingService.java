package com.smartparking.listing;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.web.GeoUtils;
import com.smartparking.common.web.PageResponse;
import com.smartparking.listing.dto.ListingBasicsRequest;
import com.smartparking.listing.dto.ListingDetailDto;
import com.smartparking.listing.dto.ListingSummaryDto;
import com.smartparking.listing.dto.PricingRequest;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerListingService {

    static final double MAX_DISTANCE_KM = 60.0;
    private static final Set<ListingStatus> DELETABLE =
            EnumSet.of(ListingStatus.DRAFT, ListingStatus.PENDING_REVIEW, ListingStatus.REJECTED, ListingStatus.PAUSED);

    private final ParkingListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final CityRepository cities;
    private final UserRepository users;
    private final FileStorage storage;
    private final ListingMapper mapper;

    /** The owner's listing, or 404 (also for other owners' listings, so existence is not leaked). */
    @Transactional(readOnly = true)
    public ParkingListing requireOwned(Long ownerId, Long listingId) {
        return listings.findByIdAndOwnerId(listingId, ownerId)
                .orElseThrow(() -> ApiException.notFound("Listing not found"));
    }

    /** Like {@link #requireOwned} but rejects SUSPENDED listings. All owner write operations use this. */
    @Transactional(readOnly = true)
    public ParkingListing requireEditable(Long ownerId, Long listingId) {
        ParkingListing listing = requireOwned(ownerId, listingId);
        if (listing.getStatus() == ListingStatus.SUSPENDED) {
            throw ApiException.conflict("LISTING_SUSPENDED", "This listing is suspended and cannot be edited");
        }
        return listing;
    }

    @Transactional
    public ListingDetailDto create(Long ownerId, ListingBasicsRequest r) {
        City city = requireCity(r.cityId());
        checkWithinCity(city, r.lat(), r.lng());
        ParkingListing listing = new ParkingListing();
        listing.setOwner(users.getReferenceById(ownerId));
        applyBasics(listing, r, city);
        return detail(listings.save(listing));
    }

    @Transactional(readOnly = true)
    public ListingDetailDto get(Long ownerId, Long listingId) {
        return detail(requireOwned(ownerId, listingId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ListingSummaryDto> list(Long ownerId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.from(listings.findByOwnerIdOrderByUpdatedAtDesc(ownerId, pageable).map(l -> {
            List<ListingPhoto> ps = photos.findByListingIdOrderBySortOrderAsc(l.getId());
            String cover = ps.isEmpty() ? null : ps.get(0).getUrl();
            return mapper.toSummary(l, cover, slots.countByListingIdAndActiveTrue(l.getId()));
        }));
    }

    @Transactional
    public ListingDetailDto updateBasics(Long ownerId, Long listingId, ListingBasicsRequest r) {
        ParkingListing listing = requireEditable(ownerId, listingId);
        City city = requireCity(r.cityId());
        checkWithinCity(city, r.lat(), r.lng());
        applyBasics(listing, r, city);
        return detail(listings.save(listing));
    }

    @Transactional
    public ListingDetailDto updatePricing(Long ownerId, Long listingId, PricingRequest r) {
        ParkingListing listing = requireEditable(ownerId, listingId);
        BigDecimal hour = scale(r.pricePerHour());
        BigDecimal day = scale(r.pricePerDay());
        BigDecimal month = scale(r.pricePerMonth());
        if (day != null && day.compareTo(hour) < 0) {
            throw ApiException.badRequest("INVALID_PRICING", "The daily price cannot be lower than the hourly price");
        }
        BigDecimal monthFloor = day != null ? day : hour;
        if (month != null && month.compareTo(monthFloor) < 0) {
            throw ApiException.badRequest("INVALID_PRICING", day != null
                    ? "The monthly price cannot be lower than the daily price"
                    : "The monthly price cannot be lower than the hourly price");
        }
        listing.setPricePerHour(hour);
        listing.setPricePerDay(day);
        listing.setPricePerMonth(month);
        listing.setCancellationPolicy(r.cancellationPolicy());
        listing.setAutoApprove(r.autoApprove());
        listing.getAmenities().clear();
        listing.getAmenities().addAll(r.amenities());
        listing.setRules(r.rules() == null || r.rules().isBlank() ? null : r.rules().trim());
        return detail(listings.save(listing));
    }

    @Transactional
    public void delete(Long ownerId, Long listingId) {
        ParkingListing listing = requireOwned(ownerId, listingId);
        if (!DELETABLE.contains(listing.getStatus())) {
            throw ApiException.conflict("INVALID_STATUS", "Pause the listing before deleting it");
        }
        List<String> keys = photos.findByListingIdOrderBySortOrderAsc(listingId).stream()
                .map(ListingPhoto::getStorageKey).toList();
        listings.delete(listing);
        listings.flush();
        keys.forEach(storage::delete);
    }

    private ListingDetailDto detail(ParkingListing l) {
        return mapper.toDetail(l, photos.findByListingIdOrderBySortOrderAsc(l.getId()),
                slots.findByListingIdOrderByLabelAsc(l.getId()),
                rules.findByListingIdOrderByDayOfWeekAsc(l.getId()));
    }

    private City requireCity(Long cityId) {
        return cities.findById(cityId).orElseThrow(() -> ApiException.badRequest("INVALID_CITY", "Unknown city"));
    }

    private static void checkWithinCity(City city, double lat, double lng) {
        if (GeoUtils.distanceKm(city.getLat(), city.getLng(), lat, lng) > MAX_DISTANCE_KM) {
            throw ApiException.badRequest("LOCATION_OUTSIDE_CITY",
                    "The map pin must be within 60 km of " + city.getName());
        }
    }

    private static void applyBasics(ParkingListing l, ListingBasicsRequest r, City city) {
        l.setCity(city);
        l.setTitle(r.title().trim());
        l.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        l.setAddress(r.address().trim());
        l.setPincode(r.pincode());
        l.setLat(r.lat());
        l.setLng(r.lng());
        l.setListingType(r.listingType());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
