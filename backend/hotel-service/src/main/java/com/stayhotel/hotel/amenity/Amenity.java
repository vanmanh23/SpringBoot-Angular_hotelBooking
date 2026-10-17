package com.stayhotel.hotel.amenity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "amenities")
public class Amenity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AmenityCategory category;

    protected Amenity() {
    }

    public Amenity(String code, String name, AmenityCategory category) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name.trim();
        this.category = category;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AmenityCategory getCategory() {
        return category;
    }
}
