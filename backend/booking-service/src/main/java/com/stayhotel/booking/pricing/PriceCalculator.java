package com.stayhotel.booking.pricing;

import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.booking.catalog.HotelSnapshot.RoomTypeSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Backend-only pricing (BR-12, BR-13, BR-15; DECISIONS D-020). Inputs come exclusively from hotel-service data:
 * <pre>
 *   lineSubtotal = basePrice × quantity × nights
 *   subtotal     = Σ lineSubtotal
 *   fee          = round(subtotal × serviceFee% / 100)
 *   tax (VAT)    = round((subtotal + fee) × taxRate% / 100)
 *   total        = subtotal + fee + tax − discount        (discount = 0 until promotions exist)
 * </pre>
 * VND, whole đồng, HALF_UP.
 */
public final class PriceCalculator {

    public static final String CURRENCY = "VND";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceCalculator() {
    }

    public static PriceBreakdown price(HotelSnapshot hotel, List<Line> lines, int nights) {
        if (nights < 1 || lines.isEmpty()) {
            throw new IllegalArgumentException("nights and lines are required");
        }
        List<PricedLine> priced = lines.stream().map(l -> {
            if (!CURRENCY.equals(l.roomType().currency())) {
                throw new IllegalStateException("Unsupported currency " + l.roomType().currency());
            }
            BigDecimal unit = vnd(l.roomType().basePrice());
            BigDecimal subtotal = unit.multiply(BigDecimal.valueOf((long) l.quantity() * nights));
            return new PricedLine(l.roomType().id(), l.roomType().name(), l.quantity(), unit, nights, subtotal);
        }).toList();
        BigDecimal subtotal = priced.stream().map(PricedLine::lineSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = percentOf(subtotal, hotel.serviceFeePercent());
        BigDecimal tax = percentOf(subtotal.add(fee), hotel.taxRatePercent());
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal total = subtotal.add(fee).add(tax).subtract(discount);
        return new PriceBreakdown(priced, subtotal, hotel.serviceFeePercent(), fee, hotel.taxRatePercent(), tax, discount,
                total, CURRENCY);
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }

    private static BigDecimal vnd(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    public record Line(RoomTypeSnapshot roomType, int quantity) {
    }

    public record PricedLine(java.util.UUID roomTypeId, String roomTypeName, int quantity, BigDecimal unitPrice,
                             int nights, BigDecimal lineSubtotal) {
    }

    public record PriceBreakdown(List<PricedLine> lines, BigDecimal subtotal, BigDecimal serviceFeePercent,
                                 BigDecimal feeAmount, BigDecimal taxRatePercent, BigDecimal taxAmount,
                                 BigDecimal discountAmount, BigDecimal totalAmount, String currency) {
    }
}
