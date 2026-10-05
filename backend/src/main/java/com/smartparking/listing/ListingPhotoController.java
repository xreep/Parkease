package com.smartparking.listing;

import com.smartparking.common.security.AuthUser;
import com.smartparking.listing.dto.PhotoDto;
import com.smartparking.listing.dto.PhotoOrderRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/owner/listings/{listingId}/photos")
@RequiredArgsConstructor
public class ListingPhotoController {

    private final ListingPhotoService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoDto upload(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                           @RequestPart(name = "file", required = false) MultipartFile file) {
        return service.upload(principal.id(), listingId, file);
    }

    @PutMapping("/order")
    public List<PhotoDto> reorder(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                                  @Valid @RequestBody PhotoOrderRequest request) {
        return service.reorder(principal.id(), listingId, request.photoIds());
    }

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal, @PathVariable Long listingId,
                       @PathVariable Long photoId) {
        service.delete(principal.id(), listingId, photoId);
    }
}
