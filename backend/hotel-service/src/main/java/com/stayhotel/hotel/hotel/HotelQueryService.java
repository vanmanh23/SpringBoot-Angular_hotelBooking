package com.stayhotel.hotel.hotel;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import com.stayhotel.hotel.HotelErrors;
import com.stayhotel.hotel.support.SearchText;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class HotelQueryService {

    private final HotelRepository hotels;
    private final HotelViewAssembler views;

    HotelQueryService(HotelRepository hotels, HotelViewAssembler views) {
        this.hotels = hotels;
        this.views = views;
    }

    /** Public detail. Hotels that are not ACTIVE are reported as not found (BR-06). */
    @Transactional(readOnly = true)
    public HotelDtos.HotelDetailResponse publicDetail(UUID hotelId) {
        return views.publicView(requirePublic(hotelId));
    }

    @Transactional(readOnly = true)
    public Hotel requirePublic(UUID hotelId) {
        return hotels.findById(hotelId).filter(Hotel::isPublic).orElseThrow(HotelErrors::hotelNotFound);
    }

    /** ADMIN sees every hotel; a HOTEL_MANAGER only the hotels assigned to them. */
    @Transactional(readOnly = true)
    public PageResponse<ManagedHotelSummary> listManaged(CurrentUser user, HotelStatus status, String query,
                                                         Pageable pageable) {
        Specification<Hotel> spec = (root, cq, cb) -> cb.conjunction();
        if (!user.isAdmin()) {
            UUID userId = user.id();
            spec = spec.and((root, cq, cb) -> {
                Subquery<UUID> assigned = cq.subquery(UUID.class);
                var manager = assigned.from(HotelManager.class);
                assigned.select(manager.get("id").get("hotelId"))
                        .where(cb.equal(manager.get("id").get("userId"), userId));
                return root.get("id").in(assigned);
            });
        }
        if (status != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), status));
        }
        if (query != null && !query.isBlank()) {
            String like = SearchText.likeContains(SearchText.normalize(query));
            spec = spec.and((root, cq, cb) -> cb.or(cb.like(root.get("nameKey"), like, '\\'),
                    cb.like(root.get("cityKey"), like, '\\')));
        }
        return PageResponse.from(hotels.findAll(spec, pageable), ManagedHotelSummary::from);
    }

    public record ManagedHotelSummary(UUID id, String name, String city, String countryCode, Short starRating,
                                      HotelStatus status, Instant approvedAt, long version) {
        static ManagedHotelSummary from(Hotel h) {
            return new ManagedHotelSummary(h.getId(), h.getName(), h.getCity(), h.getCountryCode(),
                    h.getStarRating(), h.getStatus(), h.getApprovedAt(), h.getVersion());
        }
    }
}
