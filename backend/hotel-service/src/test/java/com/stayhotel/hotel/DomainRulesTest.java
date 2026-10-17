package com.stayhotel.hotel;

import com.stayhotel.hotel.hotel.HotelStatus;
import com.stayhotel.hotel.support.SearchText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.stayhotel.hotel.hotel.HotelStatus.ACTIVE;
import static com.stayhotel.hotel.hotel.HotelStatus.DRAFT;
import static com.stayhotel.hotel.hotel.HotelStatus.INACTIVE;
import static com.stayhotel.hotel.hotel.HotelStatus.SUSPENDED;
import static org.assertj.core.api.Assertions.assertThat;

class DomainRulesTest {

    @ParameterizedTest
    @CsvSource({
            "'Đà Nẵng', 'da nang'",
            "'  HỒ   Chí Minh ', 'ho chi minh'",
            "'Huế', 'hue'",
            "'Phú Quốc', 'phu quoc'"
    })
    void searchKeysIgnoreCaseDiacriticsAndSpacing(String input, String expected) {
        assertThat(SearchText.normalize(input)).isEqualTo(expected);
    }

    @Test
    void likePatternEscapesWildcards() {
        assertThat(SearchText.likeContains("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }

    /** from, to, admin, wasApproved, allowed */
    @ParameterizedTest(name = "{0} -> {1} admin={2} approved={3}: {4}")
    @CsvSource({
            "DRAFT, ACTIVE, true, false, true",
            "DRAFT, ACTIVE, false, false, false",
            "DRAFT, INACTIVE, true, false, false",
            "ACTIVE, INACTIVE, false, true, true",
            "ACTIVE, SUSPENDED, false, true, false",
            "ACTIVE, SUSPENDED, true, true, true",
            "INACTIVE, ACTIVE, false, true, true",
            "INACTIVE, ACTIVE, false, false, false",
            "SUSPENDED, ACTIVE, false, true, false",
            "SUSPENDED, ACTIVE, true, true, true",
            "ACTIVE, ACTIVE, true, true, false",
            "SUSPENDED, DRAFT, true, true, false"
    })
    void hotelStatusTransitions(HotelStatus from, HotelStatus to, boolean admin, boolean approved, boolean allowed) {
        assertThat(from.canTransitionTo(to, admin, approved)).isEqualTo(allowed);
    }

    @Test
    void nothingReturnsToDraft() {
        for (HotelStatus from : HotelStatus.values()) {
            assertThat(from.canTransitionTo(DRAFT, true, true)).isFalse();
        }
        assertThat(INACTIVE.canTransitionTo(SUSPENDED, true, true)).isTrue();
        assertThat(SUSPENDED.canTransitionTo(INACTIVE, true, true)).isTrue();
        assertThat(ACTIVE.canTransitionTo(SUSPENDED, true, false)).isTrue();
    }
}
