package com.stayhotel.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID>, JpaSpecificationExecutor<Review> {

    Page<Review> findByHotelIdAndStatus(UUID hotelId, Review.Status status, Pageable pageable);

    List<Review> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Review> findByBookingId(UUID bookingId);
}
