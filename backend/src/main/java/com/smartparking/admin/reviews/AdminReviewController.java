package com.smartparking.admin.reviews;

import com.smartparking.admin.dto.ShortReasonRequest;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
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
@RequestMapping("/api/v1/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

    private final AdminReviewModerationService service;

    @GetMapping
    public PageResponse<AdminReviewDto> list(@RequestParam(required = false) Boolean hidden,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.list(hidden, q, page, size);
    }

    @PostMapping("/{id}/hide")
    public AdminReviewDto hide(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                               @Valid @RequestBody ShortReasonRequest request) {
        return service.hide(admin, id, request.reason());
    }

    @PostMapping("/{id}/unhide")
    public AdminReviewDto unhide(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        return service.unhide(admin, id);
    }
}
