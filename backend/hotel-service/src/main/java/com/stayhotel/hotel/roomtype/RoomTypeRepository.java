package com.stayhotel.hotel.roomtype;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    List<RoomType> findByHotelIdOrderByBasePriceAscNameAsc(UUID hotelId);

    List<RoomType> findByHotelIdAndStatusOrderByBasePriceAscNameAsc(UUID hotelId, RoomTypeStatus status);

    /** Active room types able to host {@code guests} in {@code rooms} rooms (capacity and unit count only). */
    @Query("""
            select rt from RoomType rt
            where rt.hotelId = :hotelId and rt.status = com.stayhotel.hotel.roomtype.RoomTypeStatus.ACTIVE
              and rt.capacity * :rooms >= :guests and rt.totalUnits >= :rooms
            order by rt.basePrice asc, rt.name asc""")
    List<RoomType> findSuitable(UUID hotelId, int guests, int rooms);

    boolean existsByHotelIdAndNameIgnoreCase(UUID hotelId, String name);

    boolean existsByHotelIdAndNameIgnoreCaseAndIdNot(UUID hotelId, String name, UUID id);
}
