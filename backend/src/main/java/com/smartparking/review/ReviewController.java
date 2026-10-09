package com.smartparking.review;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.review.dto.CreateReviewRequest;
import com.smartparking.review.dto.ListingReviewsDto;
import com.smartparking.review.dto.ReviewDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The public review list of a listing and the driver's review of one of their bookings. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService service;

    @GetMapping("/listings/{id}/reviews")
    public ListingReviewsDto listForListing(@PathVariable Long id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return service.listForListing(id, page, size);
    }

    @PostMapping("/bookings/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto create(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                            @Valid @RequestBody CreateReviewRequest request) {
        Roles.requireDriver(principal);
        return service.create(principal.id(), id, request.rating(), request.comment());
    }
}
