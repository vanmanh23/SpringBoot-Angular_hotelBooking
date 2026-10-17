package com.stayhotel.booking;

import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.booking.pricing.PriceCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private static HotelSnapshot hotel(String tax, String fee, HotelSnapshot.RoomTypeSnapshot... types) {
        return new HotelSnapshot(UUID.randomUUID(), "H", "Asia/Ho_Chi_Minh", LocalTime.NOON, new BigDecimal(tax),
                new BigDecimal(fee), List.of(types));
    }

    private static HotelSnapshot.RoomTypeSnapshot room(String price) {
        return new HotelSnapshot.RoomTypeSnapshot(UUID.randomUUID(), "R", 2, new BigDecimal(price), "VND", 10);
    }

    @Test
    void feeOnSubtotalThenVatOnSubtotalPlusFee() {
        HotelSnapshot.RoomTypeSnapshot rt = room("1000000.00");
        var price = PriceCalculator.price(hotel("8.00", "5.00", rt), List.of(new PriceCalculator.Line(rt, 2)), 2);

        assertThat(price.subtotal()).isEqualByComparingTo("4000000");      // 1,000,000 x 2 rooms x 2 nights
        assertThat(price.feeAmount()).isEqualByComparingTo("200000");      // 5 %
        assertThat(price.taxAmount()).isEqualByComparingTo("336000");      // 8 % of 4,200,000
        assertThat(price.totalAmount()).isEqualByComparingTo("4536000");
        assertThat(price.lines().get(0).lineSubtotal()).isEqualByComparingTo("4000000");
    }

    @Test
    void roundsHalfUpToWholeDong() {
        HotelSnapshot.RoomTypeSnapshot rt = room("333333");
        var price = PriceCalculator.price(hotel("8.00", "5.00", rt), List.of(new PriceCalculator.Line(rt, 1)), 1);

        assertThat(price.feeAmount()).isEqualByComparingTo("16667");       // 16,666.65 -> 16,667
        assertThat(price.taxAmount()).isEqualByComparingTo("28000");       // 8 % of 350,000
        assertThat(price.totalAmount()).isEqualByComparingTo("378000");
        assertThat(price.totalAmount().scale()).isZero();
    }

    @Test
    void multipleLinesAndZeroRates() {
        HotelSnapshot.RoomTypeSnapshot a = room("500000");
        HotelSnapshot.RoomTypeSnapshot b = room("1200000");
        var price = PriceCalculator.price(hotel("0", "0", a, b),
                List.of(new PriceCalculator.Line(a, 1), new PriceCalculator.Line(b, 2)), 3);

        assertThat(price.subtotal()).isEqualByComparingTo("8700000");      // 1,500,000 + 7,200,000
        assertThat(price.totalAmount()).isEqualByComparingTo(price.subtotal());
    }

    @Test
    void rejectsForeignCurrency() {
        var usd = new HotelSnapshot.RoomTypeSnapshot(UUID.randomUUID(), "R", 2, new BigDecimal("100"), "USD", 1);
        assertThatThrownBy(() -> PriceCalculator.price(hotel("8", "5", usd), List.of(new PriceCalculator.Line(usd, 1)), 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
