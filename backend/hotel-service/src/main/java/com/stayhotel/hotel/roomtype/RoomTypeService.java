package com.stayhotel.hotel.roomtype;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.hotel.HotelErrors;
import com.stayhotel.hotel.amenity.AmenityCategory;
import com.stayhotel.hotel.amenity.AmenityService;
import com.stayhotel.hotel.hotel.HotelAccessPolicy;
import com.stayhotel.hotel.hotel.HotelManagementService;
import com.stayhotel.hotel.hotel.HotelQueryService;
import com.stayhotel.hotel.hotel.HotelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RoomTypeService {

    private final RoomTypeRepository roomTypes;
    private final HotelRepository hotels;
    private final HotelQueryService hotelQueries;
    private final HotelAccessPolicy access;
    private final AmenityService amenities;
    private final Clock clock;

    RoomTypeService(RoomTypeRepository roomTypes, HotelRepository hotels, HotelQueryService hotelQueries,
                    HotelAccessPolicy access, AmenityService amenities, Clock clock) {
        this.roomTypes = roomTypes;
        this.hotels = hotels;
        this.hotelQueries = hotelQueries;
        this.access = access;
        this.amenities = amenities;
        this.clock = clock;
    }

    /** Public: active room types of an active hotel that fit the party (capacity/units only, BR-07/BR-08). */
    @Transactional(readOnly = true)
    public List<RoomTypeDtos.RoomTypeResponse> listSuitable(UUID hotelId, int guests, int rooms) {
        hotelQueries.requirePublic(hotelId);
        return roomTypes.findSuitable(hotelId, guests, rooms).stream().map(RoomTypeDtos.RoomTypeResponse::from).toList();
    }

    @Transactional
    public RoomTypeDtos.RoomTypeResponse create(CurrentUser user, UUID hotelId, RoomTypeDtos.RoomTypeRequest request) {
        if (!hotels.existsById(hotelId)) {
            throw HotelErrors.hotelNotFound();
        }
        access.requireManage(user, hotelId);
        if (roomTypes.existsByHotelIdAndNameIgnoreCase(hotelId, request.name().trim())) {
            throw HotelErrors.roomTypeNameTaken();
        }
        return flushedView(roomTypes.save(RoomType.create(hotelId, request, clock.instant())));
    }

    @Transactional
    public RoomTypeDtos.RoomTypeResponse update(CurrentUser user, UUID roomTypeId, RoomTypeDtos.RoomTypeRequest request) {
        RoomType roomType = loadManaged(user, roomTypeId);
        HotelManagementService.requireVersion(request.version(), roomType.getVersion());
        if (roomTypes.existsByHotelIdAndNameIgnoreCaseAndIdNot(roomType.getHotelId(), request.name().trim(), roomTypeId)) {
            throw HotelErrors.roomTypeNameTaken();
        }
        roomType.apply(request, clock.instant());
        return flushedView(roomType);
    }

    @Transactional
    public RoomTypeDtos.RoomTypeResponse changeStatus(CurrentUser user, UUID roomTypeId, RoomTypeStatus status) {
        RoomType roomType = loadManaged(user, roomTypeId);
        roomType.changeStatus(status, clock.instant());
        return flushedView(roomType);
    }

    @Transactional
    public RoomTypeDtos.RoomTypeResponse replaceAmenities(CurrentUser user, UUID roomTypeId, Set<String> codes) {
        RoomType roomType = loadManaged(user, roomTypeId);
        roomType.replaceAmenities(amenities.resolve(codes, AmenityCategory.ROOM), clock.instant());
        return flushedView(roomType);
    }

    private RoomType loadManaged(CurrentUser user, UUID roomTypeId) {
        RoomType roomType = roomTypes.findById(roomTypeId).orElseThrow(HotelErrors::roomTypeNotFound);
        access.requireManage(user, roomType.getHotelId());
        return roomType;
    }

    private RoomTypeDtos.RoomTypeResponse flushedView(RoomType roomType) {
        roomTypes.flush();
        return RoomTypeDtos.RoomTypeResponse.from(roomType);
    }
}
