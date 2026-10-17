package com.stayhotel.hotel.hotel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface HotelImageRepository extends JpaRepository<HotelImage, UUID> {

    List<HotelImage> findByHotelIdOrderBySortOrderAsc(UUID hotelId);

    long countByHotelId(UUID hotelId);

    @Query("select coalesce(max(i.sortOrder), -1) from HotelImage i where i.hotelId = :hotelId")
    int maxSortOrder(UUID hotelId);
}
