package com.smartparking.listing;

import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.common.error.ApiException;
import com.smartparking.slot.ParkingSlotRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** What a listing still needs before it can be reviewed or go live, and the guards that keep it that way. */
@Component
@RequiredArgsConstructor
public class ListingCompleteness {

    /** Statuses in which a listing is awaiting review or visible: it must stay bookable. */
    private static final Set<ListingStatus> SUBMITTED_OR_LIVE =
            EnumSet.of(ListingStatus.PENDING_REVIEW, ListingStatus.APPROVED, ListingStatus.PAUSED);

    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;

    /** Missing parts in a stable order: PHOTOS, SLOTS, PRICING, AVAILABILITY. Empty when complete. */
    public List<String> missingParts(ParkingListing listing) {
        Long id = listing.getId();
        List<String> missing = new ArrayList<>();
        if (photos.countByListingId(id) == 0) missing.add("PHOTOS");
        if (slots.countByListingIdAndActiveTrue(id) == 0) missing.add("SLOTS");
        if (listing.getPricePerHour() == null) missing.add("PRICING");
        if (!listing.isOpen24x7() && rules.findByListingIdOrderByDayOfWeekAsc(id).isEmpty()) {
            missing.add("AVAILABILITY");
        }
        return missing;
    }

    public static boolean isSubmittedOrLive(ParkingListing listing) {
        return SUBMITTED_OR_LIVE.contains(listing.getStatus());
    }

    /** 409 for removing the last photo or active slot of a submitted or live listing. */
    public static ApiException stillNeeds(String part) {
        return ApiException.conflict("LISTING_INCOMPLETE",
                "A submitted or live listing needs at least one photo and one active slot")
                .with("missing", List.of(part));
    }
}
