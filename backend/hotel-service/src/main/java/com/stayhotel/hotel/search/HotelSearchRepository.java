package com.stayhotel.hotel.search;

import com.stayhotel.hotel.support.SearchText;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public hotel search in SQL: one count query, one page query and one batched amenity query per call (no N+1).
 * Only bound parameters are used; the dynamic parts are fixed SQL fragments chosen from a whitelist.
 */
@Repository
class HotelSearchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    HotelSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Row(UUID id, String name, String city, String address, String countryCode, Integer starRating,
               BigDecimal avgRating, int reviewCount, String thumbnailUrl, BigDecimal fromPrice, String currency) {
    }

    record Result(List<Row> rows, long total) {
    }

    Result search(HotelSearchCriteria c) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("guests", c.guests())
                .addValue("rooms", c.rooms());

        // Room types able to host the party; the cheapest one gives the "from" price (advisory, BR-08).
        StringBuilder suitable = new StringBuilder("""
                SELECT rt.hotel_id, MIN(rt.base_price) AS from_price, MIN(rt.currency_code) AS currency
                FROM room_types rt
                WHERE rt.status = 'ACTIVE' AND rt.capacity * :rooms >= :guests AND rt.total_units >= :rooms
                """);
        if (c.minPrice() != null) {
            suitable.append(" AND rt.base_price >= :minPrice");
            p.addValue("minPrice", c.minPrice());
        }
        if (c.maxPrice() != null) {
            suitable.append(" AND rt.base_price <= :maxPrice");
            p.addValue("maxPrice", c.maxPrice());
        }
        suitable.append(" GROUP BY rt.hotel_id");

        StringBuilder where = new StringBuilder(" WHERE h.status = 'ACTIVE'");
        if (c.cityKey() != null) {
            where.append(" AND h.city_key = :cityKey");
            p.addValue("cityKey", c.cityKey());
        }
        if (c.textKey() != null) {
            where.append(" AND (h.name_key LIKE :text ESCAPE '\\' OR h.city_key LIKE :text ESCAPE '\\')");
            p.addValue("text", SearchText.likeContains(c.textKey()));
        }
        if (!c.stars().isEmpty()) {
            where.append(" AND h.star_rating IN (:stars)");
            p.addValue("stars", c.stars());
        }
        if (!c.amenityCodes().isEmpty()) {
            // Hotel must have ALL requested amenities.
            where.append(" AND h.id IN (SELECT ha.hotel_id FROM hotel_amenities ha"
                    + " JOIN amenities a ON a.id = ha.amenity_id WHERE a.code IN (:amenityCodes)"
                    + " GROUP BY ha.hotel_id HAVING COUNT(DISTINCT a.code) = :amenityCount)");
            p.addValue("amenityCodes", c.amenityCodes()).addValue("amenityCount", c.amenityCodes().size());
        }

        String from = " FROM hotels h JOIN (" + suitable + ") s ON s.hotel_id = h.id" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        if (total == null || total == 0 || (long) c.page() * c.size() >= total) {
            return new Result(List.of(), total == null ? 0 : total);
        }

        p.addValue("limit", c.size()).addValue("offset", (long) c.page() * c.size());
        String sql = """
                SELECT h.id, h.name, h.city, h.address, h.country_code, h.star_rating, h.avg_rating, h.review_count,
                       s.from_price, s.currency,
                       (SELECT i.url FROM hotel_images i WHERE i.hotel_id = h.id ORDER BY i.sort_order LIMIT 1) AS thumbnail
                """ + from + " ORDER BY " + orderBy(c.sort()) + ", h.id LIMIT :limit OFFSET :offset";
        List<Row> rows = jdbc.query(sql, p, (rs, i) -> new Row(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("city"), rs.getString("address"),
                rs.getString("country_code"), (Integer) rs.getObject("star_rating", Integer.class),
                rs.getBigDecimal("avg_rating"), rs.getInt("review_count"), rs.getString("thumbnail"),
                rs.getBigDecimal("from_price").setScale(0, RoundingMode.UNNECESSARY), rs.getString("currency")));
        return new Result(rows, total);
    }

    Map<UUID, List<String>> amenityCodes(List<UUID> hotelIds) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (hotelIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT ha.hotel_id, a.code FROM hotel_amenities ha JOIN amenities a ON a.id = ha.amenity_id
                WHERE ha.hotel_id IN (:ids) ORDER BY a.code""", Map.of("ids", hotelIds), rs -> {
            result.computeIfAbsent(rs.getObject("hotel_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("code"));
        });
        return result;
    }

    private static String orderBy(HotelSearchCriteria.HotelSort sort) {
        return switch (sort) {
            case PRICE_ASC -> "s.from_price ASC";
            case PRICE_DESC -> "s.from_price DESC";
            case RATING_DESC -> "h.avg_rating DESC NULLS LAST, h.review_count DESC";
            case NAME_ASC -> "h.name_key ASC";
            case RECOMMENDED -> "h.avg_rating DESC NULLS LAST, h.star_rating DESC NULLS LAST, s.from_price ASC";
        };
    }
}
