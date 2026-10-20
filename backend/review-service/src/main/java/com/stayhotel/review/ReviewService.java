package com.stayhotel.review;

import com.stayhotel.common.audit.AuditTrail;
import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.messaging.OutboxWriter;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reviews of completed stays (D-027). Authorship is the token subject; the stay must belong to the same user.
 * Every change that can affect the hotel score recomputes it and publishes a versioned rating event (D-028).
 */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final AuditTrail audit;
    private final Clock clock;

    ReviewService(ReviewRepository reviews, JdbcTemplate jdbc, OutboxWriter outbox, AuditTrail audit, Clock clock) {
        this.audit = audit;
        this.reviews = reviews;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDtos.ReviewResponse> forHotel(UUID hotelId, UUID viewer, int page, int size) {
        return PageResponse.from(reviews.findByHotelIdAndStatus(hotelId, Review.Status.VISIBLE,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                r -> ReviewDtos.ReviewResponse.from(r, viewer));
    }

    @Transactional(readOnly = true)
    public List<ReviewDtos.ReviewResponse> mine(CurrentUser user) {
        return reviews.findByUserIdOrderByCreatedAtDesc(user.id()).stream()
                .map(r -> ReviewDtos.ReviewResponse.from(r, user.id())).toList();
    }

    @Transactional(readOnly = true)
    public ReviewDtos.Eligibility eligibility(CurrentUser user, UUID bookingId) {
        boolean stay = ownsCompletedStay(user.id(), bookingId) != null;
        UUID existing = reviews.findByBookingId(bookingId).filter(r -> r.getUserId().equals(user.id()))
                .map(Review::getId).orElse(null);
        return new ReviewDtos.Eligibility(stay && existing == null, existing);
    }

    @Transactional
    public ReviewDtos.ReviewResponse create(CurrentUser user, ReviewDtos.ReviewRequest request) {
        UUID hotelId = ownsCompletedStay(user.id(), request.bookingId());
        if (hotelId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REVIEW_NOT_ALLOWED",
                    "Only guests with a completed stay of this booking can review it");
        }
        if (reviews.findByBookingId(request.bookingId()).isPresent()) {
            throw reviewExists();
        }
        Review review;
        try {
            review = reviews.saveAndFlush(Review.create(request.bookingId(), user.id(), hotelId, request.rating(),
                    request.comment(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw reviewExists(); // concurrent double submit
        }
        publishStats(hotelId);
        return ReviewDtos.ReviewResponse.from(review, user.id());
    }

    @Transactional
    public ReviewDtos.ReviewResponse update(CurrentUser user, UUID reviewId, ReviewDtos.ReviewEdit request) {
        Review review = ownReview(user, reviewId);
        review.edit(request.rating(), request.comment(), clock.instant());
        reviews.flush();
        publishStats(review.getHotelId());
        return ReviewDtos.ReviewResponse.from(review, user.id());
    }

    @Transactional
    public void delete(CurrentUser user, UUID reviewId) {
        Review review = reviews.findById(reviewId)
                .filter(r -> r.getUserId().equals(user.id()) || user.isAdmin())
                .orElseThrow(ReviewService::notFound);
        reviews.delete(review);
        reviews.flush();
        if (!review.getUserId().equals(user.id())) {
            audit.record(user.id(), "REVIEW_DELETED", "REVIEW", reviewId, Map.of("hotelId", review.getHotelId()));
        }
        publishStats(review.getHotelId());
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDtos.AdminReview> forModeration(Review.Status status, UUID hotelId, int page, int size) {
        Specification<Review> spec = (root, q, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
        if (hotelId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("hotelId"), hotelId));
        }
        return PageResponse.from(reviews.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), ReviewDtos.AdminReview::from);
    }

    @Transactional
    public ReviewDtos.ReviewResponse moderate(CurrentUser admin, UUID reviewId, Review.Status status, String reason) {
        Review review = reviews.findById(reviewId).orElseThrow(ReviewService::notFound);
        Review.Status previous = review.getStatus();
        review.moderate(status, reason, clock.instant());
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("from", previous);
        details.put("to", status);
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason.trim());
        }
        audit.record(admin.id(), "REVIEW_VISIBILITY_CHANGED", "REVIEW", reviewId, details);
        reviews.flush();
        publishStats(review.getHotelId());
        return ReviewDtos.ReviewResponse.from(review, admin.id());
    }

    /** Idempotent projection update from BookingCompleted. */
    @Transactional
    public void recordCompletedStay(UUID bookingId, UUID userId, UUID hotelId, LocalDate checkOut) {
        jdbc.update("""
                INSERT INTO eligible_stays (booking_id, user_id, hotel_id, check_out_date, recorded_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (booking_id) DO NOTHING""",
                bookingId, userId, hotelId, checkOut, Timestamp.from(clock.instant()));
    }

    /** @return the hotel of the stay if it is a completed stay of this user, else null */
    private UUID ownsCompletedStay(UUID userId, UUID bookingId) {
        return jdbc.query("SELECT hotel_id FROM eligible_stays WHERE booking_id = ? AND user_id = ?",
                (rs, i) -> rs.getObject("hotel_id", UUID.class), bookingId, userId).stream().findFirst().orElse(null);
    }

    /**
     * Locks the hotel's stats row FIRST, then recomputes visible stats and bumps the version. Because the aggregate is
     * read under the lock, a higher version always carries newer numbers; consumers keep the highest version, so
     * duplicates and reordering cannot corrupt the score (D-028).
     */
    private void publishStats(UUID hotelId) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO hotel_rating_stats (hotel_id, avg_rating, review_count, version, updated_at)
                VALUES (?, NULL, 0, 0, ?) ON CONFLICT (hotel_id) DO NOTHING""", hotelId, Timestamp.from(now));
        long version = jdbc.queryForObject("SELECT version FROM hotel_rating_stats WHERE hotel_id = ? FOR UPDATE",
                Long.class, hotelId) + 1;
        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT COUNT(*) AS cnt, AVG(rating) AS avg FROM reviews WHERE hotel_id = ? AND status = 'VISIBLE'""", hotelId);
        int count = ((Number) agg.get("cnt")).intValue();
        BigDecimal avg = agg.get("avg") == null ? null
                : new BigDecimal(agg.get("avg").toString()).setScale(1, RoundingMode.HALF_UP);
        jdbc.update("UPDATE hotel_rating_stats SET avg_rating = ?, review_count = ?, version = ?, updated_at = ? WHERE hotel_id = ?",
                avg, count, version, Timestamp.from(now), hotelId);
        outbox.append(Topics.REVIEW_EVENTS, "HotelRatingChanged", hotelId,
                new ReviewDtos.HotelRatingChanged(hotelId, avg, count, version));
    }

    private Review ownReview(CurrentUser user, UUID reviewId) {
        return reviews.findById(reviewId).filter(r -> r.getUserId().equals(user.id())).orElseThrow(ReviewService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review not found");
    }

    private static BusinessException reviewExists() {
        return new BusinessException(HttpStatus.CONFLICT, "REVIEW_EXISTS", "This stay has already been reviewed");
    }
}
