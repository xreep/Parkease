package com.smartparking.availability;

import com.smartparking.availability.dto.HoursDto;
import com.smartparking.availability.dto.HoursRequest;
import com.smartparking.common.error.ApiException;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.OwnerListingService;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final OwnerListingService listings;
    private final ParkingListingRepository listingRepository;
    private final AvailabilityRuleRepository rules;
    private final ListingMapper mapper;

    @Transactional(readOnly = true)
    public HoursDto getHours(Long ownerId, Long listingId) {
        ParkingListing listing = listings.requireOwned(ownerId, listingId);
        return hours(listing.isOpen24x7(), rules.findByListingIdOrderByDayOfWeekAsc(listingId));
    }

    @Transactional
    public HoursDto saveHours(Long ownerId, Long listingId, HoursRequest r) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        boolean open24x7 = r.open24x7();
        if (!open24x7) {
            validateRules(r.rules());
        }
        // Set before the bulk delete: it flushes first, then clears the persistence context.
        listing.setOpen24x7(open24x7);
        rules.deleteByListingId(listingId);
        List<AvailabilityRule> saved = new ArrayList<>();
        if (!open24x7) {
            ParkingListing ref = listingRepository.getReferenceById(listingId);
            for (HoursRequest.RuleRequest rr : r.rules()) {
                AvailabilityRule rule = new AvailabilityRule();
                rule.setListing(ref);
                rule.setDayOfWeek(rr.dayOfWeek());
                rule.setOpenTime(rr.openTime());
                rule.setCloseTime(rr.closeTime());
                saved.add(rule);
            }
            rules.saveAll(saved);
        }
        return hours(open24x7, saved);
    }

    private static void validateRules(List<HoursRequest.RuleRequest> list) {
        Set<Integer> days = new HashSet<>();
        for (HoursRequest.RuleRequest rule : list) {
            if (!days.add(rule.dayOfWeek())) {
                throw ApiException.badRequest("DUPLICATE_DAY", "Each weekday can only be listed once");
            }
            if (!rule.closeTime().isAfter(rule.openTime())) {
                throw ApiException.badRequest("INVALID_HOURS", "Closing time must be after opening time");
            }
        }
    }

    private HoursDto hours(boolean open24x7, List<AvailabilityRule> list) {
        return new HoursDto(open24x7, list.stream()
                .sorted(java.util.Comparator.comparingInt(AvailabilityRule::getDayOfWeek))
                .map(mapper::toRule).toList());
    }
}
