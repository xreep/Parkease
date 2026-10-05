package com.smartparking.listing;

import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.dto.HoursRuleDto;
import com.smartparking.listing.dto.ListingDetailDto;
import com.smartparking.listing.dto.ListingSummaryDto;
import com.smartparking.listing.dto.PhotoDto;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.dto.SlotDto;
import com.smartparking.storage.FileStorage;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Maps a listing plus its separately loaded children to DTOs. Call within a transaction (lazy city/state). */
@Slf4j
@Component
public class ListingMapper {

    private final FileStorage storage;

    public ListingMapper(FileStorage storage) {
        this.storage = storage;
    }

    public ListingDetailDto toDetail(ParkingListing l, List<ListingPhoto> photos, List<ParkingSlot> slots,
                                     List<AvailabilityRule> rules) {
        return new ListingDetailDto(
                l.getId(), l.getTitle(), l.getDescription(), l.getAddress(), l.getPincode(), l.getLat(), l.getLng(),
                l.getListingType(), l.getCity().getId(), l.getCity().getName(), l.getCity().getState().getName(),
                l.getStatus(), l.getRejectionReason(), l.isOpen24x7(), l.getRules(), l.isAutoApprove(),
                l.getPricePerHour(), l.getPricePerDay(), l.getPricePerMonth(), l.getCancellationPolicy(),
                l.getAmenities().stream().sorted().toList(),
                photos.stream().sorted(Comparator.comparingInt(ListingPhoto::getSortOrder)).map(this::toPhoto).toList(),
                slots.stream().sorted(Comparator.comparing(ParkingSlot::getLabel)).map(this::toSlot).toList(),
                rules.stream().sorted(Comparator.comparingInt(AvailabilityRule::getDayOfWeek)).map(this::toRule).toList(),
                l.getSubmittedAt(), l.getApprovedAt(), l.getUpdatedAt());
    }

    public ListingSummaryDto toSummary(ParkingListing l, String coverUrl, long activeSlots) {
        return new ListingSummaryDto(l.getId(), l.getTitle(), l.getStatus(), l.getCity().getName(),
                l.getCity().getState().getName(), coverUrl, l.getPricePerHour(), activeSlots,
                l.getRejectionReason(), l.getUpdatedAt());
    }

    public PhotoDto toPhoto(ListingPhoto p) {
        return new PhotoDto(p.getId(), photoUrl(p), p.getSortOrder());
    }

    /** Public URL from the storage key, so a changed host or storage backend never leaves stale links. */
    public String photoUrl(ListingPhoto p) {
        if (p.getStorageKey() == null) {
            return p.getUrl();
        }
        try {
            return storage.publicUrl(p.getStorageKey());
        } catch (IllegalArgumentException e) {
            // Key written by the other storage backend (local <-> Cloudinary): keep the URL stored at upload time.
            log.warn("Photo {} has a storage key this backend cannot serve; using its stored url", p.getId());
            return p.getUrl();
        }
    }

    public SlotDto toSlot(ParkingSlot s) {
        return new SlotDto(s.getId(), s.getLabel(), s.getVehicleType(), s.getSize(), s.isActive());
    }

    public HoursRuleDto toRule(AvailabilityRule r) {
        return new HoursRuleDto(r.getDayOfWeek(), r.getOpenTime(), r.getCloseTime());
    }
}
