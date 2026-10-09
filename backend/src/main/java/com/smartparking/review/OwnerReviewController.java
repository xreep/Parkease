package com.smartparking.review;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import com.smartparking.review.dto.OwnerReviewDto;
import com.smartparking.review.dto.ReplyRequest;
import com.smartparking.review.dto.ReviewDto;
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

/** Reviews of the signed-in owner's listings (the OWNER role is enforced by the security rules for /owner/**). */
@RestController
@RequestMapping("/api/v1/owner/reviews")
@RequiredArgsConstructor
public class OwnerReviewController {

    private final ReviewService service;

    @GetMapping
    public PageResponse<OwnerReviewDto> list(@AuthenticationPrincipal AuthUser principal,
                                             @RequestParam(required = false) Long listingId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.listForOwner(principal.id(), listingId, page, size);
    }

    @PostMapping("/{id}/reply")
    public ReviewDto reply(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                           @Valid @RequestBody ReplyRequest request) {
        return service.reply(principal.id(), id, request.reply());
    }
}
