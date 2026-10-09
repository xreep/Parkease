package com.smartparking.listing;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.availability.AvailabilityEvaluator.ListingAvailabilityInput;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.booking.BookingProperties;
import com.smartparking.booking.BookingRepository;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.dto.ListingQuoteResponse;
import com.smartparking.listing.dto.PublicListingDto;
import com.smartparking.pricing.PricingService;
import com.smartparking.pricing.QuoteDto;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import com.smartparking.slot.SlotSize;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicListingService {

    private final ParkingListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final AvailabilityEvaluator evaluator;
    private final PricingService pricing;
    private final ListingMapper mapper;
    private final BookingRepository bookings;
    private final BookingProperties bookingProperties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PublicListingDto get(Long id) {
        ParkingListing l = requireApproved(id);
        List<ParkingSlot> activeSlots = slots.findByListingIdOrderByLabelAsc(id).stream()
                .filter(ParkingSlot::isActive).toList();
        return new PublicListingDto(
                l.getId(), l.getTitle(), l.getDescription(), l.getListingType(), l.getAddress(), l.getPincode(),
                l.getLat(), l.getLng(), l.getCity().getName(), l.getCity().getSlug(),
                l.getCity().getState().getName(), l.getCity().getState().getSlug(),
                photos.findByListingIdOrderBySortOrderAsc(id).stream()
                        .map(p -> new PublicListingDto.PublicPhoto(p.getId(), mapper.photoUrl(p))).toList(),
                l.getAmenities().stream().sorted().toList(),
                l.getRules(), l.getCancellationPolicy(), l.isAutoApprove(), l.isOpen24x7(),
                rules.findByListingIdOrderByDayOfWeekAsc(id).stream().map(mapper::toRule).toList(),
                l.getPricePerHour(), l.getPricePerDay(), l.getPricePerMonth(),
                summarize(activeSlots), l.getAvgRating(), l.getReviewCount(), firstName(l.getOwner().getName()));
    }

    /** Availability for the window plus the price; the quote is returned even when the listing is unavailable. */
    @Transactional(readOnly = true)
    public ListingQuoteResponse quote(Long id, Instant start, Instant end, VehicleType vehicleType) {
        TimeWindow window = TimeWindow.of(start, end, clock);
        ParkingListing l = requireApproved(id);
        bookingProperties.requireNoticeForRequest(l.isAutoApprove(), window.start(), clock.instant());
        List<AvailabilityRule> listingRules = l.isOpen24x7() ? List.of() : rules.findByListingIdOrderByDayOfWeekAsc(id);
        List<AvailabilityBlock> overlapping = blocks.findOverlapping(List.of(id), window.start(), window.end());
        var bookedSlotIds = new HashSet<>(
                bookings.findLiveOverlappingSlotIds(List.of(id), window.start(), window.end(), clock.instant()));
        AvailabilityEvaluator.Result result = evaluator.evaluate(
                new ListingAvailabilityInput(l.isOpen24x7(), listingRules, slots.findByListingIdOrderByLabelAsc(id),
                        overlapping, bookedSlotIds),
                window, vehicleType);
        QuoteDto quote = QuoteDto.from(pricing.quote(l, window.start(), window.end()));
        return new ListingQuoteResponse(result.available(), result.reason(), result.freeSlots(),
                result.totalSlots(), quote);
    }

    private ParkingListing requireApproved(Long id) {
        return listings.findByIdAndStatus(id, ListingStatus.APPROVED)
                .orElseThrow(() -> ApiException.notFound("Listing not found"));
    }

    private static PublicListingDto.SlotSummary summarize(List<ParkingSlot> active) {
        return new PublicListingDto.SlotSummary(
                countType(active, VehicleType.TWO_WHEELER), countType(active, VehicleType.FOUR_WHEELER),
                countSize(active, SlotSize.SMALL), countSize(active, SlotSize.MEDIUM),
                countSize(active, SlotSize.LARGE));
    }

    private static int countType(List<ParkingSlot> s, VehicleType t) {
        return (int) s.stream().filter(x -> x.getVehicleType() == t).count();
    }

    private static int countSize(List<ParkingSlot> s, SlotSize size) {
        return (int) s.stream().filter(x -> x.getSize() == size).count();
    }

    private static String firstName(String name) {
        String trimmed = name == null ? "" : name.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
