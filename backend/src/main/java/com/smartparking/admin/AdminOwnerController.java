package com.smartparking.admin;

import com.smartparking.admin.dto.AdminOwnerDto;
import com.smartparking.admin.dto.QueueCountsDto;
import com.smartparking.admin.dto.ReasonRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.storage.SignedUrlDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminOwnerController {

    private final AdminReviewService service;

    @GetMapping("/queues")
    public QueueCountsDto queues() {
        return service.queues();
    }

    @GetMapping("/owners")
    public PageResponse<AdminOwnerDto> owners(@RequestParam(defaultValue = "PENDING") VerificationStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.owners(status, page, size);
    }

    @GetMapping("/owners/{userId}/document-url")
    public SignedUrlDto documentUrl(@PathVariable Long userId) {
        return service.ownerDocumentUrl(userId);
    }

    @PostMapping("/owners/{userId}/verify")
    public AdminOwnerDto verify(@AuthenticationPrincipal AuthUser admin, @PathVariable Long userId) {
        return service.verifyOwner(admin, userId);
    }

    @PostMapping("/owners/{userId}/reject")
    public AdminOwnerDto reject(@AuthenticationPrincipal AuthUser admin, @PathVariable Long userId,
                                @Valid @RequestBody ReasonRequest request) {
        return service.rejectOwner(admin, userId, request.reason());
    }
}
