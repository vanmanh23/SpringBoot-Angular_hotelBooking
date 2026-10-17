package com.stayhotel.hotel.search;

import com.stayhotel.common.error.FieldValidationException;
import com.stayhotel.common.web.PageResponse;
import com.stayhotel.hotel.support.SearchText;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** GET /api/v1/hotels — public, paginated, advisory availability (BR-08). */
@RestController
class HotelSearchController {

    static final int MAX_NIGHTS = 30;
    private static final Pattern AMENITY_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final HotelSearchService service;
    private final Clock clock;

    HotelSearchController(HotelSearchService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/api/v1/hotels")
    PageResponse<HotelSearchService.HotelSummary> search(
            @RequestParam(required = false) @Size(max = 100) String city,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(defaultValue = "1") @Min(1) @Max(40) int guests,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10) int rooms,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(required = false) @Size(max = 20) String stars,
            @RequestParam(required = false) @Size(max = 500) String amenities,
            @RequestParam(defaultValue = "recommended") String sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        validateDates(checkIn, checkOut);
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw invalid("minPrice", "must not be greater than maxPrice");
        }
        HotelSearchCriteria criteria = new HotelSearchCriteria(blankToNullKey(city), blankToNullKey(q), checkIn,
                checkOut, guests, rooms, minPrice, maxPrice, parseStars(stars), parseAmenities(amenities),
                parseSort(sort), page, size);
        return service.search(criteria);
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null && checkOut == null) {
            return;
        }
        if (checkIn == null || checkOut == null) {
            throw invalid(checkIn == null ? "checkIn" : "checkOut", "checkIn and checkOut must be provided together");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw invalid("checkOut", "must be after checkIn");
        }
        // Hotels span time zones; allow "yesterday" in UTC so a local "today" is never rejected (D-014).
        if (checkIn.isBefore(LocalDate.now(clock).minusDays(1))) {
            throw invalid("checkIn", "must not be in the past");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_NIGHTS) {
            throw invalid("checkOut", "stay must be at most " + MAX_NIGHTS + " nights");
        }
    }

    private static Set<Integer> parseStars(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        try {
            Set<Integer> values = Arrays.stream(csv.split(",")).map(String::trim).map(Integer::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (values.stream().anyMatch(v -> v < 1 || v > 5)) {
                throw invalid("stars", "values must be between 1 and 5");
            }
            return values;
        } catch (NumberFormatException e) {
            throw invalid("stars", "must be a comma-separated list of numbers");
        }
    }

    private static Set<String> parseAmenities(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> codes = Arrays.stream(csv.split(",")).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.size() > 20 || codes.stream().anyMatch(c -> !AMENITY_CODE.matcher(c).matches())) {
            throw invalid("amenities", "must be a comma-separated list of amenity codes");
        }
        return codes;
    }

    private static HotelSearchCriteria.HotelSort parseSort(String value) {
        try {
            return HotelSearchCriteria.HotelSort.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("sort", "must be one of recommended, price_asc, price_desc, rating_desc, name_asc");
        }
    }

    private static String blankToNullKey(String value) {
        return value == null || value.isBlank() ? null : SearchText.normalize(value);
    }

    private static FieldValidationException invalid(String field, String message) {
        return new FieldValidationException(field, message);
    }
}
