package com.stayhotel.hotel.amenity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AmenityDtos {

    private AmenityDtos() {
    }

    public record AmenityResponse(UUID id, String code, String name, AmenityCategory category) {
        public static AmenityResponse from(Amenity a) {
            return new AmenityResponse(a.getId(), a.getCode(), a.getName(), a.getCategory());
        }
    }

    /** Compact form embedded in hotels and room types. */
    public record AmenityRef(String code, String name) {
        public static List<AmenityRef> sorted(Set<Amenity> amenities) {
            return amenities.stream().sorted(Comparator.comparing(Amenity::getName))
                    .map(a -> new AmenityRef(a.getCode(), a.getName())).toList();
        }
    }

    public record CreateAmenityRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String code,
                                       @NotBlank @Size(max = 100) String name,
                                       @NotNull AmenityCategory category) {
    }

    public record AmenityCodesRequest(@NotNull @Size(max = 50) Set<@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String> codes) {
    }
}
