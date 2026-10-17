package com.stayhotel.hotel.amenity;

import com.stayhotel.hotel.HotelErrors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AmenityService {

    private final AmenityRepository amenities;

    AmenityService(AmenityRepository amenities) {
        this.amenities = amenities;
    }

    @Transactional(readOnly = true)
    public List<AmenityDtos.AmenityResponse> list(AmenityCategory category) {
        List<Amenity> result = category == null
                ? amenities.findAllByOrderByCategoryAscNameAsc()
                : amenities.findByCategoryOrderByNameAsc(category);
        return result.stream().map(AmenityDtos.AmenityResponse::from).toList();
    }

    @Transactional
    public AmenityDtos.AmenityResponse create(AmenityDtos.CreateAmenityRequest request) {
        if (amenities.existsByCode(request.code())) {
            throw HotelErrors.amenityCodeTaken();
        }
        return AmenityDtos.AmenityResponse.from(
                amenities.save(new Amenity(request.code(), request.name(), request.category())));
    }

    /** Resolves codes to amenities of the expected category; unknown or wrong-category codes are rejected. */
    @Transactional(readOnly = true)
    public Set<Amenity> resolve(Collection<String> codes, AmenityCategory category) {
        Set<String> wanted = new HashSet<>(codes);
        Set<Amenity> found = amenities.findByCodeIn(wanted).stream()
                .filter(a -> a.getCategory() == category)
                .collect(Collectors.toSet());
        if (found.size() != wanted.size()) {
            Set<String> foundCodes = found.stream().map(Amenity::getCode).collect(Collectors.toSet());
            String missing = wanted.stream().filter(c -> !foundCodes.contains(c)).sorted().findFirst().orElse("?");
            throw HotelErrors.unknownAmenity(missing);
        }
        return found;
    }
}
