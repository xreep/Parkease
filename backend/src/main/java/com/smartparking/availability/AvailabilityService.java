package com.smartparking.availability;

import com.smartparking.availability.dto.BlockDto;
import com.smartparking.availability.dto.BlockRequest;
import com.smartparking.availability.dto.HoursDto;
import com.smartparking.availability.dto.HoursRequest;
import com.smartparking.common.error.ApiException;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.OwnerListingService;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final AvailabilityBlockRepository blocks;
    private final ParkingSlotRepository slots;
    private final ListingMapper mapper;
    private final Clock clock;

    static final Duration MAX_BLOCK_LEAD = Duration.ofDays(365);

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

    @Transactional(readOnly = true)
    public List<BlockDto> listBlocks(Long ownerId, Long listingId) {
        listings.requireOwned(ownerId, listingId);
        return blocks.findByListingIdAndEndTimeAfterOrderByStartTimeAsc(listingId, clock.instant()).stream()
                .map(AvailabilityService::toDto).toList();
    }

    @Transactional
    public BlockDto createBlock(Long ownerId, Long listingId, BlockRequest r) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        Instant now = clock.instant();
        if (!r.endTime().isAfter(r.startTime())) {
            throw invalidBlock("The block must end after it starts");
        }
        if (!r.endTime().isAfter(now)) {
            throw invalidBlock("The block must end in the future");
        }
        if (r.startTime().isAfter(now.plus(MAX_BLOCK_LEAD))) {
            throw invalidBlock("Blocks can start at most 365 days ahead");
        }
        ParkingSlot slot = null;
        if (r.slotId() != null) {
            slot = slots.findByIdAndListingId(r.slotId(), listingId)
                    .orElseThrow(() -> invalidBlock("Slot not found in this listing"));
        }
        AvailabilityBlock block = new AvailabilityBlock();
        block.setListing(listing);
        block.setSlot(slot);
        block.setStartTime(r.startTime());
        block.setEndTime(r.endTime());
        block.setReason(r.reason() == null || r.reason().isBlank() ? null : r.reason().trim());
        return toDto(blocks.save(block));
    }

    @Transactional
    public void deleteBlock(Long ownerId, Long listingId, Long blockId) {
        listings.requireEditable(ownerId, listingId);
        blocks.delete(blocks.findByIdAndListingId(blockId, listingId)
                .orElseThrow(() -> ApiException.notFound("Block not found")));
    }

    private static ApiException invalidBlock(String message) {
        return ApiException.badRequest("INVALID_BLOCK", message);
    }

    private static BlockDto toDto(AvailabilityBlock b) {
        ParkingSlot slot = b.getSlot();
        return new BlockDto(b.getId(), slot == null ? null : slot.getId(), slot == null ? null : slot.getLabel(),
                b.getStartTime(), b.getEndTime(), b.getReason());
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
