package com.stayhotel.booking.booking;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Human-friendly, unguessable booking code (BR-16), e.g. {@code SH-261110-7KQ2M9XA}: check-in date + 8 random
 * Crockford base32 characters (40 bits). Uniqueness is enforced by the database constraint.
 */
@Component
class BookingCodeGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private final SecureRandom random = new SecureRandom();

    String next(LocalDate checkIn) {
        StringBuilder code = new StringBuilder("SH-").append(DATE.format(checkIn)).append('-');
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
