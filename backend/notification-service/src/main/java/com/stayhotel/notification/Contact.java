package com.stayhotel.notification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Projection row of booking_contacts. */
record Contact(UUID bookingId, UUID userId, String name, String email, String bookingCode, String hotelName,
               LocalDate checkIn, LocalDate checkOut, BigDecimal totalAmount, String currency) {
}
