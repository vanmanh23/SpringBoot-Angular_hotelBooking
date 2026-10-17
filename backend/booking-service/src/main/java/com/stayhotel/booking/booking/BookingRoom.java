package com.stayhotel.booking.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** One room-type line of a booking with its price snapshot (design 4.4.6, BR-14). Immutable once created. */
@Entity
@Table(name = "booking_rooms")
public class BookingRoom {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;

    @Column(name = "room_type_id", nullable = false, updatable = false)
    private UUID roomTypeId;

    @Column(name = "room_type_name", nullable = false, updatable = false)
    private String roomTypeName;

    @Column(nullable = false, updatable = false)
    private short quantity;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private short nights;

    @Column(name = "line_subtotal", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal lineSubtotal;

    protected BookingRoom() {
    }

    BookingRoom(Booking booking, UUID roomTypeId, String roomTypeName, int quantity, BigDecimal unitPrice, int nights,
                BigDecimal lineSubtotal) {
        this.id = UUID.randomUUID();
        this.booking = booking;
        this.roomTypeId = roomTypeId;
        this.roomTypeName = roomTypeName;
        this.quantity = (short) quantity;
        this.unitPrice = unitPrice;
        this.nights = (short) nights;
        this.lineSubtotal = lineSubtotal;
    }

    public UUID getRoomTypeId() {
        return roomTypeId;
    }

    public String getRoomTypeName() {
        return roomTypeName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getNights() {
        return nights;
    }

    public BigDecimal getLineSubtotal() {
        return lineSubtotal;
    }
}
