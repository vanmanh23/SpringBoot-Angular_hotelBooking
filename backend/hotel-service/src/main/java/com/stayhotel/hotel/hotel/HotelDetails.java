package com.stayhotel.hotel.hotel;

import java.time.LocalTime;

/** Validated, defaulted hotel attributes (built from {@link HotelDtos.HotelRequest}). */
public record HotelDetails(String name, String description, String address, String city, String countryCode,
                           Integer starRating, String timeZone, LocalTime checkInTime, LocalTime checkOutTime,
                           String cancellationPolicy) {
}
