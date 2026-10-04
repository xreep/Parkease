package com.smartparking.slot;

import com.smartparking.common.error.ApiException;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.OwnerListingService;
import com.smartparking.listing.ParkingListing;
import com.smartparking.slot.dto.BulkSlotRequest;
import com.smartparking.slot.dto.SlotDto;
import com.smartparking.slot.dto.SlotRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlotService {

    static final int MAX_SLOTS_PER_LISTING = 200;

    private final OwnerListingService listings;
    private final ParkingSlotRepository slots;
    private final ListingMapper mapper;

    @Transactional(readOnly = true)
    public List<SlotDto> list(Long ownerId, Long listingId) {
        listings.requireOwned(ownerId, listingId);
        return slots.findByListingIdOrderByLabelAsc(listingId).stream().map(mapper::toSlot).toList();
    }

    @Transactional
    public SlotDto create(Long ownerId, Long listingId, SlotRequest r) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        requireCapacity(listingId, 1);
        String label = r.label().trim();
        requireLabelFree(listingId, label);
        ParkingSlot slot = new ParkingSlot();
        slot.setListing(listing);
        slot.setLabel(label);
        slot.setVehicleType(r.vehicleType());
        slot.setSize(r.size());
        slot.setActive(r.active() == null || r.active());
        return mapper.toSlot(slots.save(slot));
    }

    @Transactional
    public List<SlotDto> createBulk(Long ownerId, Long listingId, BulkSlotRequest r) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        requireCapacity(listingId, r.count());
        int last = r.startNumber() + r.count() - 1;
        int width = last >= 100 ? 3 : 2;
        List<String> labels = new ArrayList<>();
        for (int n = r.startNumber(); n <= last; n++) {
            labels.add(r.prefix() + String.format("%0" + width + "d", n));
        }
        // All-or-nothing: nothing is saved unless every label is free.
        labels.forEach(label -> requireLabelFree(listingId, label));
        List<ParkingSlot> created = new ArrayList<>();
        for (String label : labels) {
            ParkingSlot slot = new ParkingSlot();
            slot.setListing(listing);
            slot.setLabel(label);
            slot.setVehicleType(r.vehicleType());
            slot.setSize(r.size());
            created.add(slot);
        }
        return slots.saveAll(created).stream().map(mapper::toSlot).toList();
    }

    @Transactional
    public SlotDto update(Long ownerId, Long listingId, Long slotId, SlotRequest r) {
        listings.requireEditable(ownerId, listingId);
        ParkingSlot slot = requireSlot(listingId, slotId);
        String label = r.label().trim();
        if (!label.equalsIgnoreCase(slot.getLabel())) {
            requireLabelFree(listingId, label);
        }
        slot.setLabel(label);
        slot.setVehicleType(r.vehicleType());
        slot.setSize(r.size());
        if (r.active() != null) {
            slot.setActive(r.active());
        }
        return mapper.toSlot(slots.save(slot));
    }

    @Transactional
    public void delete(Long ownerId, Long listingId, Long slotId) {
        listings.requireEditable(ownerId, listingId);
        slots.delete(requireSlot(listingId, slotId));
    }

    private ParkingSlot requireSlot(Long listingId, Long slotId) {
        return slots.findByIdAndListingId(slotId, listingId)
                .orElseThrow(() -> ApiException.notFound("Slot not found"));
    }

    private void requireCapacity(Long listingId, int adding) {
        if (slots.countByListingId(listingId) + adding > MAX_SLOTS_PER_LISTING) {
            throw ApiException.conflict("SLOT_LIMIT", "A listing can have at most " + MAX_SLOTS_PER_LISTING + " slots");
        }
    }

    private void requireLabelFree(Long listingId, String label) {
        if (slots.existsByListingIdAndLabelIgnoreCase(listingId, label)) {
            throw ApiException.conflict("SLOT_LABEL_TAKEN", "A slot labelled \"" + label + "\" already exists");
        }
    }
}
