package com.stayhotel.hotel.hotel;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Assignment of a HOTEL_MANAGER user to a hotel (DECISIONS D-013). */
@Entity
@Table(name = "hotel_managers")
public class HotelManager {

    @EmbeddedId
    private Key id;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected HotelManager() {
    }

    HotelManager(UUID hotelId, UUID userId, UUID assignedBy, Instant now) {
        this.id = new Key(hotelId, userId);
        this.assignedBy = assignedBy;
        this.assignedAt = now;
    }

    public UUID getUserId() {
        return id.userId;
    }

    @Embeddable
    public record Key(@Column(name = "hotel_id") UUID hotelId, @Column(name = "user_id") UUID userId)
            implements Serializable {
    }
}
