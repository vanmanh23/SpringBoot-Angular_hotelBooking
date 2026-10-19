package com.stayhotel.hotel.hotel;

import com.stayhotel.hotel.amenity.Amenity;
import com.stayhotel.hotel.support.SearchText;
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
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "hotels")
public class Hotel {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    private String description;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(name = "city_key", nullable = false)
    private String cityKey;

    @Column(name = "country_code", nullable = false, columnDefinition = "bpchar(2)")
    private String countryCode;

    @Column(name = "star_rating")
    private Short starRating;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "check_in_time", nullable = false)
    private LocalTime checkInTime;

    @Column(name = "check_out_time", nullable = false)
    private LocalTime checkOutTime;

    @Column(name = "cancellation_policy")
    private String cancellationPolicy;

    @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "service_fee_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceFeePercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HotelStatus status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Owned by review-service events (RatingEventsListener); never written by entity updates. */
    @Column(name = "avg_rating", updatable = false)
    private BigDecimal avgRating;

    @Column(name = "review_count", nullable = false, updatable = false)
    private int reviewCount;

    @ManyToMany
    @JoinTable(name = "hotel_amenities", joinColumns = @JoinColumn(name = "hotel_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    @BatchSize(size = 50)
    private Set<Amenity> amenities = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Hotel() {
    }

    public static Hotel draft(HotelDetails details, Instant now) {
        Hotel hotel = new Hotel();
        hotel.id = UUID.randomUUID();
        hotel.status = HotelStatus.DRAFT;
        hotel.createdAt = now;
        hotel.apply(details, now);
        return hotel;
    }

    public void apply(HotelDetails d, Instant now) {
        name = d.name().trim();
        nameKey = SearchText.normalize(name);
        description = blankToNull(d.description());
        address = d.address().trim();
        city = d.city().trim();
        cityKey = SearchText.normalize(city);
        countryCode = d.countryCode();
        starRating = d.starRating() == null ? null : d.starRating().shortValue();
        timeZone = d.timeZone();
        checkInTime = d.checkInTime();
        checkOutTime = d.checkOutTime();
        cancellationPolicy = blankToNull(d.cancellationPolicy());
        taxRatePercent = d.taxRatePercent();
        serviceFeePercent = d.serviceFeePercent();
        updatedAt = now;
    }

    public void changeStatus(HotelStatus target, Instant now) {
        if (target == HotelStatus.ACTIVE && approvedAt == null) {
            approvedAt = now;
        }
        status = target;
        updatedAt = now;
    }

    public void replaceAmenities(Set<Amenity> newAmenities, Instant now) {
        amenities.clear();
        amenities.addAll(newAmenities);
        updatedAt = now;
    }

    public boolean isPublic() {
        return status == HotelStatus.ACTIVE;
    }

    public boolean wasApproved() {
        return approvedAt != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAddress() {
        return address;
    }

    public String getCity() {
        return city;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public Short getStarRating() {
        return starRating;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public LocalTime getCheckInTime() {
        return checkInTime;
    }

    public LocalTime getCheckOutTime() {
        return checkOutTime;
    }

    public String getCancellationPolicy() {
        return cancellationPolicy;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public BigDecimal getServiceFeePercent() {
        return serviceFeePercent;
    }

    public HotelStatus getStatus() {
        return status;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public BigDecimal getAvgRating() {
        return avgRating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public Set<Amenity> getAmenities() {
        return Set.copyOf(amenities);
    }

    public long getVersion() {
        return version;
    }
}
