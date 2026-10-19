package com.stayhotel.review;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class ReviewController {

    private final ReviewService service;

    ReviewController(ReviewService service) {
        this.service = service;
    }

    /** Public; a token is optional and only used to flag the viewer's own review. */
    @GetMapping("/api/v1/hotels/{hotelId}/reviews")
    PageResponse<ReviewDtos.ReviewResponse> forHotel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID hotelId,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return service.forHotel(hotelId, jwt == null ? null : CurrentUser.from(jwt).id(), page, size);
    }

    @GetMapping("/api/v1/reviews/me")
    List<ReviewDtos.ReviewResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(CurrentUser.from(jwt));
    }

    @GetMapping("/api/v1/reviews/eligibility/{bookingId}")
    ReviewDtos.Eligibility eligibility(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId) {
        return service.eligibility(CurrentUser.from(jwt), bookingId);
    }

    @PostMapping("/api/v1/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    ReviewDtos.ReviewResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReviewDtos.ReviewRequest request) {
        return service.create(CurrentUser.from(jwt), request);
    }

    @PutMapping("/api/v1/reviews/{id}")
    ReviewDtos.ReviewResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody ReviewDtos.ReviewEdit request) {
        return service.update(CurrentUser.from(jwt), id, request);
    }

    @DeleteMapping("/api/v1/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(CurrentUser.from(jwt), id);
    }

    @GetMapping("/api/v1/admin/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    PageResponse<ReviewDtos.AdminReview> forModeration(@RequestParam(required = false) Review.Status status,
                                                      @RequestParam(required = false) UUID hotelId,
                                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.forModeration(status, hotelId, page, size);
    }

    @PatchMapping("/api/v1/admin/reviews/{id}/visibility")
    @PreAuthorize("hasRole('ADMIN')")
    ReviewDtos.ReviewResponse moderate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody ReviewDtos.VisibilityRequest request) {
        return service.moderate(CurrentUser.from(jwt), id, request.status(), request.reason());
    }
}
