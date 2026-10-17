package com.stayhotel.hotel.search;

import com.stayhotel.common.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HotelSearchService {

    private final HotelSearchRepository repository;

    HotelSearchService(HotelSearchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<HotelSummary> search(HotelSearchCriteria criteria) {
        HotelSearchRepository.Result result = repository.search(criteria);
        Map<UUID, List<String>> amenities = repository.amenityCodes(
                result.rows().stream().map(HotelSearchRepository.Row::id).toList());
        List<HotelSummary> content = result.rows().stream()
                .map(r -> new HotelSummary(r.id(), r.name(), r.city(), r.address(), r.countryCode(), r.starRating(),
                        r.avgRating(), r.reviewCount(), r.thumbnailUrl(), r.fromPrice(), r.currency(),
                        amenities.getOrDefault(r.id(), List.of())))
                .toList();
        int totalPages = (int) ((result.total() + criteria.size() - 1) / criteria.size());
        return new PageResponse<>(content, criteria.page(), criteria.size(), result.total(), totalPages);
    }

    /** {@code fromPrice}: cheapest suitable room type per night (advisory, BR-08). */
    public record HotelSummary(UUID id, String name, String city, String address, String countryCode,
                               Integer starRating, BigDecimal avgRating, int reviewCount, String thumbnailUrl,
                               BigDecimal fromPrice, String currency, List<String> amenities) {
    }
}
