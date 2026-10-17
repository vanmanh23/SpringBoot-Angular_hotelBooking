package com.stayhotel.hotel.roomtype;

import com.stayhotel.hotel.amenity.Amenity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A sellable room category. Inventory is counted per room type ({@link #totalUnits}, DECISIONS D-015). */
@Entity
@Table(name = "room_types")
public class RoomType {

    public static final String CURRENCY = "VND";

    @Id
    private UUID id;

    @Column(name = "hotel_id", nullable = false, updatable = false)
    private UUID hotelId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private short capacity;

    @Column(name = "bed_description")
    private String bedDescription;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "currency_code", nullable = false, columnDefinition = "bpchar(3)")
    private String currencyCode;

    @Column(name = "total_units", nullable = false)
    private int totalUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomTypeStatus status;

    @ManyToMany
    @JoinTable(name = "room_type_amenities", joinColumns = @JoinColumn(name = "room_type_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    @BatchSize(size = 50)
    private Set<Amenity> amenities = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RoomType() {
    }

    static RoomType create(UUID hotelId, RoomTypeDtos.RoomTypeRequest request, Instant now) {
        RoomType roomType = new RoomType();
        roomType.id = UUID.randomUUID();
        roomType.hotelId = hotelId;
        roomType.currencyCode = CURRENCY;
        roomType.status = RoomTypeStatus.ACTIVE;
        roomType.createdAt = now;
        roomType.apply(request, now);
        return roomType;
    }

    void apply(RoomTypeDtos.RoomTypeRequest r, Instant now) {
        name = r.name().trim();
        description = r.description() == null || r.description().isBlank() ? null : r.description().trim();
        capacity = r.capacity().shortValue();
        bedDescription = r.bedDescription() == null || r.bedDescription().isBlank() ? null : r.bedDescription().trim();
        basePrice = r.basePrice();
        totalUnits = r.totalUnits();
        updatedAt = now;
    }

    void changeStatus(RoomTypeStatus target, Instant now) {
        status = target;
        updatedAt = now;
    }

    void replaceAmenities(Set<Amenity> newAmenities, Instant now) {
        amenities.clear();
        amenities.addAll(newAmenities);
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHotelId() {
        return hotelId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public short getCapacity() {
        return capacity;
    }

    public String getBedDescription() {
        return bedDescription;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public int getTotalUnits() {
        return totalUnits;
    }

    public RoomTypeStatus getStatus() {
        return status;
    }

    public Set<Amenity> getAmenities() {
        return Set.copyOf(amenities);
    }

    public long getVersion() {
        return version;
    }
}
