package com.stayhotel.booking.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(UUID userId, BookingStatus status, Pageable pageable);

    /** Serializes concurrent transitions (cancel vs. expire vs. confirm) on one booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(UUID id);

    /** Overdue holds; SKIP LOCKED lets several instances run the job without blocking each other. */
    @Query(value = """
            SELECT * FROM bookings WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now
            ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Booking> lockOverdueHolds(Instant now, int limit);

    /** Candidates for COMPLETED; the exact hotel-local date check is done in Java per booking. */
    @Query(value = """
            SELECT * FROM bookings WHERE status = 'CONFIRMED' AND check_out <= :latestCheckOut
            ORDER BY check_out LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Booking> lockCompletionCandidates(LocalDate latestCheckOut, int limit);
}
