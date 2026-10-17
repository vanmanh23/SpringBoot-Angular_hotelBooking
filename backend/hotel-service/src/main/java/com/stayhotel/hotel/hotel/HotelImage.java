package com.stayhotel.hotel.hotel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Image metadata only; files live on external storage (design 4.4.11). */
@Entity
@Table(name = "hotel_images")
public class HotelImage {

    @Id
    private UUID id;

    @Column(name = "hotel_id", nullable = false, updatable = false)
    private UUID hotelId;

    @Column(nullable = false)
    private String url;

    private String caption;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HotelImage() {
    }

    HotelImage(UUID hotelId, String url, String caption, int sortOrder, Instant now) {
        this.id = UUID.randomUUID();
        this.hotelId = hotelId;
        this.url = url;
        this.caption = caption == null || caption.isBlank() ? null : caption.trim();
        this.sortOrder = sortOrder;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHotelId() {
        return hotelId;
    }

    public String getUrl() {
        return url;
    }

    public String getCaption() {
        return caption;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
