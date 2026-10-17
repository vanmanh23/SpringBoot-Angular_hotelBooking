package com.stayhotel.hotel.amenity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    boolean existsByCode(String code);

    List<Amenity> findByCodeIn(Collection<String> codes);

    List<Amenity> findAllByOrderByCategoryAscNameAsc();

    List<Amenity> findByCategoryOrderByNameAsc(AmenityCategory category);
}
