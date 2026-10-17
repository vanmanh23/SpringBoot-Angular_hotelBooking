package com.stayhotel.booking.booking;

import com.stayhotel.common.error.FieldValidationException;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/bookings")
class BookingController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final BookingService service;

    BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('CUSTOMER')")
    BookingDtos.QuoteResponse quote(@Valid @RequestBody BookingDtos.BookingRequest request) {
        return service.quote(request);
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    ResponseEntity<BookingDtos.BookingResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
                                                       @Valid @RequestBody BookingDtos.BookingRequest request) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new FieldValidationException("idempotencyKey",
                    "Idempotency-Key header is required (8-100 characters: letters, digits, '-', '_')");
        }
        BookingService.CreateResult result = service.create(CurrentUser.from(jwt), key, request);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.booking())
                : ResponseEntity.status(HttpStatus.CREATED).body(result.booking());
    }

    @GetMapping("/me")
    PageResponse<BookingDtos.BookingSummary> mine(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(required = false) BookingStatus status,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return service.listMine(CurrentUser.from(jwt), status, page, size);
    }

    @GetMapping("/{id}")
    BookingDtos.BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.from(jwt), id);
    }

    @GetMapping("/{id}/status")
    BookingDtos.StatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.status(CurrentUser.from(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    BookingDtos.BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody(required = false) BookingDtos.CancelRequest request) {
        return service.cancel(CurrentUser.from(jwt), id, request == null ? null : request.reason());
    }
}
