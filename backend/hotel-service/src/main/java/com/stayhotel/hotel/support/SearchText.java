package com.stayhotel.hotel.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Search keys: lower-case, without diacritics, single-spaced. "Đà Nẵng" and "da  nang" both become "da nang".
 * Stored alongside the display value so that the comparison stays index-friendly.
 */
public final class SearchText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private SearchText() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        String plain = MARKS.matcher(decomposed).replaceAll("");
        return SPACES.matcher(plain.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Escapes LIKE wildcards; use with {@code ESCAPE '\'}. */
    public static String likeContains(String normalized) {
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
