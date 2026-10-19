package com.stayhotel.payment.payment;

import com.stayhotel.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Transaction monitoring for administrators (FR-ADMIN-04), including payments flagged for review. */
@RestController
class PaymentAdminController {

    private final PaymentRepository payments;
    private final RefundRepository refunds;

    PaymentAdminController(PaymentRepository payments, RefundRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @GetMapping("/api/v1/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<PaymentDtos.PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                                         @RequestParam(required = false) Boolean needsReview,
                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Specification<Payment> spec = (root, q, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
        if (needsReview != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("needsReview"), needsReview));
        }
        return PageResponse.from(payments.findAll(spec, PageRequest.of(page, size,
                        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                p -> PaymentDtos.PaymentResponse.from(p, refunds.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElse(null)));
    }
}
