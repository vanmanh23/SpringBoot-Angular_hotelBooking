package com.stayhotel.booking.booking;

import com.stayhotel.booking.BookingErrors;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.booking.pricing.PriceCalculator;
import com.stayhotel.common.error.FieldValidationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stay rules BR-01..BR-07 evaluated against trusted catalogue data (API_CONTRACT.md M3 "Stay rules"). */
final class StayPolicy {

    static final int MAX_NIGHTS = 30;
    static final int MAX_DAYS_AHEAD = 365;
    static final int MAX_ROOMS = 10;

    private StayPolicy() {
    }

    /** Date rules that need no catalogue data (field errors → 400). */
    static int validateDates(BookingDtos.BookingRequest r) {
        if (!r.checkOutDate().isAfter(r.checkInDate())) {
            throw new FieldValidationException("checkOutDate", "must be after checkInDate");
        }
        long nights = ChronoUnit.DAYS.between(r.checkInDate(), r.checkOutDate());
        if (nights > MAX_NIGHTS) {
            throw new FieldValidationException("checkOutDate", "stay must be at most " + MAX_NIGHTS + " nights");
        }
        Set<Object> seen = new HashSet<>();
        int totalRooms = 0;
        for (BookingDtos.RoomLine line : r.rooms()) {
            if (!seen.add(line.roomTypeId())) {
                throw new FieldValidationException("rooms", "each room type may appear only once");
            }
            totalRooms += line.quantity();
        }
        if (totalRooms > MAX_ROOMS) {
            throw new FieldValidationException("rooms", "at most " + MAX_ROOMS + " rooms per booking");
        }
        if (r.adults() < totalRooms) {
            throw new FieldValidationException("adults", "each room needs at least one adult");
        }
        return (int) nights;
    }

    /** Rules that depend on the hotel: local "today", active room types, capacity. Returns lines sorted by id. */
    static List<PriceCalculator.Line> resolveLines(BookingDtos.BookingRequest r, HotelSnapshot hotel, Clock clock) {
        LocalDate hotelToday = LocalDate.now(clock.withZone(ZoneId.of(hotel.timeZone())));
        if (r.checkInDate().isBefore(hotelToday)) {
            throw new FieldValidationException("checkInDate", "must not be in the past (hotel local date)");
        }
        if (r.checkInDate().isAfter(hotelToday.plusDays(MAX_DAYS_AHEAD))) {
            throw new FieldValidationException("checkInDate", "can be at most " + MAX_DAYS_AHEAD + " days ahead");
        }
        List<PriceCalculator.Line> lines = r.rooms().stream()
                .map(l -> new PriceCalculator.Line(
                        hotel.roomType(l.roomTypeId()).orElseThrow(BookingErrors::roomTypeNotAvailable), l.quantity()))
                .sorted(Comparator.comparing(l -> l.roomType().id()))
                .toList();
        int capacity = lines.stream().mapToInt(l -> l.roomType().capacity() * l.quantity()).sum();
        if (r.adults() + r.children() > capacity) {
            throw BookingErrors.capacityExceeded();
        }
        return lines;
    }

}
