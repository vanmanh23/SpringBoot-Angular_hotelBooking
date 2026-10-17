package com.stayhotel.hotel.hotel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HotelManagerRepository extends JpaRepository<HotelManager, HotelManager.Key> {

    boolean existsByIdHotelIdAndIdUserId(UUID hotelId, UUID userId);

    List<HotelManager> findByIdHotelId(UUID hotelId);
}
