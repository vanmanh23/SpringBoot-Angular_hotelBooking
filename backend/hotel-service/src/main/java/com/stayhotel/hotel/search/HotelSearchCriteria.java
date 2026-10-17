package com.stayhotel.hotel.search;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** Validated search input (see {@link HotelSearchController} for the HTTP-level rules). */
public record HotelSearchCriteria(String cityKey, String textKey, LocalDate checkIn, LocalDate checkOut, int guests,
                                  int rooms, BigDecimal minPrice, BigDecimal maxPrice, Set<Integer> stars,
                                  Set<String> amenityCodes, HotelSort sort, int page, int size) {

    public enum HotelSort {
        RECOMMENDED, PRICE_ASC, PRICE_DESC, RATING_DESC, NAME_ASC
    }
}
