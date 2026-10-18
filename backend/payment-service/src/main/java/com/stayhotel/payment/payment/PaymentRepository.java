package com.stayhotel.payment.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    @Query("""
            select p from Payment p where p.bookingId = :bookingId and p.status in
            (com.stayhotel.payment.payment.PaymentStatus.PENDING, com.stayhotel.payment.payment.PaymentStatus.SUCCEEDED,
             com.stayhotel.payment.payment.PaymentStatus.REFUND_PENDING, com.stayhotel.payment.payment.PaymentStatus.REFUNDED)""")
    Optional<Payment> findActiveByBooking(UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.providerReference = :reference")
    Optional<Payment> findByProviderReferenceForUpdate(String provider, String reference);
}
