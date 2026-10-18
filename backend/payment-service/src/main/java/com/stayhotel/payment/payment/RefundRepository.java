package com.stayhotel.payment.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(UUID id);

    Optional<Refund> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /** Refunds whose provider call failed or got no answer: candidates for a (safe, idempotent) resubmission. */
    @Query("""
            select r.id from Refund r where r.status = com.stayhotel.payment.payment.Refund.Status.PENDING
            and (r.lastAttemptAt is null or r.lastAttemptAt < :before) order by r.createdAt""")
    List<UUID> findStalePending(Instant before);
}
