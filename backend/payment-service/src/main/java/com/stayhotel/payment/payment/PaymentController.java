package com.stayhotel.payment.payment;

import com.stayhotel.common.error.FieldValidationException;
import com.stayhotel.common.security.CurrentUser;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<PaymentDtos.PaymentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody PaymentDtos.CreatePaymentRequest request) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new FieldValidationException("idempotencyKey",
                    "Idempotency-Key header is required (8-100 characters: letters, digits, '-', '_')");
        }
        PaymentService.CreateResult result = service.create(CurrentUser.from(jwt), jwt.getTokenValue(), key, request.bookingId());
        return result.replayed() ? ResponseEntity.ok(result.payment())
                : ResponseEntity.status(HttpStatus.CREATED).body(result.payment());
    }

    @GetMapping("/{id}")
    PaymentDtos.PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.from(jwt), id);
    }

    @GetMapping("/booking/{bookingId}")
    List<PaymentDtos.PaymentResponse> forBooking(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId) {
        return service.listForBooking(CurrentUser.from(jwt), bookingId);
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PaymentDtos.PaymentResponse> refund(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.RefundRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.adminRefund(id, request.reason()));
    }
}
