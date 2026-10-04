package com.smartparking.owner;

import com.smartparking.common.security.AuthUser;
import com.smartparking.owner.dto.OwnerProfileDto;
import com.smartparking.owner.dto.PayoutRequest;
import com.smartparking.storage.SignedUrlDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerProfileService service;

    @GetMapping("/profile")
    public OwnerProfileDto profile(@AuthenticationPrincipal AuthUser principal) {
        return service.get(principal.id());
    }

    @PutMapping("/profile/payout")
    public OwnerProfileDto updatePayout(@AuthenticationPrincipal AuthUser principal,
                                        @Valid @RequestBody PayoutRequest request) {
        return service.updatePayout(principal.id(), request);
    }

    @PostMapping(value = "/verification", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OwnerProfileDto submitDocument(@AuthenticationPrincipal AuthUser principal,
                                          @RequestParam DocumentType documentType,
                                          @RequestPart(name = "file", required = false) MultipartFile file) {
        return service.submitDocument(principal.id(), documentType, file);
    }

    @GetMapping("/verification/document-url")
    public SignedUrlDto documentUrl(@AuthenticationPrincipal AuthUser principal) {
        return service.documentUrl(principal.id());
    }
}
