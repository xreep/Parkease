package com.smartparking.listing;

import com.smartparking.common.error.ApiException;
import com.smartparking.listing.dto.PhotoDto;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.StoredFile;
import com.smartparking.storage.UploadKind;
import com.smartparking.storage.UploadValidator;
import com.smartparking.storage.ValidatedUpload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ListingPhotoService {

    static final int MAX_PHOTOS = 8;

    private final OwnerListingService listings;
    private final ListingPhotoRepository photos;
    private final FileStorage storage;
    private final ListingMapper mapper;

    @Transactional
    public PhotoDto upload(Long ownerId, Long listingId, MultipartFile file) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        long count = photos.countByListingId(listingId);
        if (count >= MAX_PHOTOS) {
            throw ApiException.conflict("PHOTO_LIMIT", "A listing can have at most " + MAX_PHOTOS + " photos");
        }
        ValidatedUpload upload = UploadValidator.validate(file, UploadKind.IMAGE);
        StoredFile stored = storage.storePublic(upload, "listing-photos");
        try {
            ListingPhoto photo = new ListingPhoto();
            photo.setListing(listing);
            photo.setUrl(stored.url());
            photo.setStorageKey(stored.key());
            photo.setSortOrder((int) count);
            return mapper.toPhoto(photos.saveAndFlush(photo));
        } catch (RuntimeException e) {
            storage.delete(stored.key());
            throw e;
        }
    }

    @Transactional
    public void delete(Long ownerId, Long listingId, Long photoId) {
        ParkingListing listing = listings.requireEditable(ownerId, listingId);
        ListingPhoto photo = photos.findByIdAndListingId(photoId, listingId)
                .orElseThrow(() -> ApiException.notFound("Photo not found"));
        if (ListingCompleteness.isSubmittedOrLive(listing) && photos.countByListingId(listingId) <= 1) {
            throw ListingCompleteness.stillNeeds("PHOTOS");
        }
        String key = photo.getStorageKey();
        photos.delete(photo);
        photos.flush();
        List<ListingPhoto> remaining = photos.findByListingIdOrderBySortOrderAsc(listingId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSortOrder(i);
        }
        photos.saveAll(remaining);
        storage.delete(key);
    }

    @Transactional
    public List<PhotoDto> reorder(Long ownerId, Long listingId, List<Long> photoIds) {
        listings.requireEditable(ownerId, listingId);
        List<ListingPhoto> existing = photos.findByListingIdOrderBySortOrderAsc(listingId);
        if (photoIds.size() != existing.size() || new HashSet<>(photoIds).size() != photoIds.size()) {
            throw invalidOrder();
        }
        Map<Long, ListingPhoto> byId = existing.stream()
                .collect(Collectors.toMap(ListingPhoto::getId, Function.identity()));
        List<ListingPhoto> ordered = new ArrayList<>();
        for (Long id : photoIds) {
            ListingPhoto photo = id == null ? null : byId.get(id);
            if (photo == null) {
                throw invalidOrder();
            }
            ordered.add(photo);
        }
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSortOrder(i);
        }
        return photos.saveAll(ordered).stream().map(mapper::toPhoto).toList();
    }

    private static ApiException invalidOrder() {
        return ApiException.badRequest("INVALID_PHOTO_ORDER", "The photo order must list every photo exactly once");
    }
}
