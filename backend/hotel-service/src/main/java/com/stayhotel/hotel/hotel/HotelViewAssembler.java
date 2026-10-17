package com.stayhotel.hotel.hotel;

import com.stayhotel.hotel.amenity.AmenityDtos.AmenityRef;
import com.stayhotel.hotel.roomtype.RoomTypeDtos.RoomTypeResponse;
import com.stayhotel.hotel.roomtype.RoomTypeRepository;
import com.stayhotel.hotel.roomtype.RoomTypeStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Builds hotel views with a fixed number of queries (hotel, amenities, images, room types + batched amenities). */
@Component
class HotelViewAssembler {

    private final HotelImageRepository images;
    private final HotelManagerRepository managers;
    private final RoomTypeRepository roomTypes;

    HotelViewAssembler(HotelImageRepository images, HotelManagerRepository managers, RoomTypeRepository roomTypes) {
        this.images = images;
        this.managers = managers;
        this.roomTypes = roomTypes;
    }

    HotelDtos.HotelDetailResponse publicView(Hotel h) {
        List<RoomTypeResponse> types = roomTypes
                .findByHotelIdAndStatusOrderByBasePriceAscNameAsc(h.getId(), RoomTypeStatus.ACTIVE)
                .stream().map(RoomTypeResponse::from).toList();
        return new HotelDtos.HotelDetailResponse(h.getId(), h.getName(), h.getDescription(), h.getAddress(),
                h.getCity(), h.getCountryCode(), h.getStarRating(), h.getTimeZone(), h.getCheckInTime(),
                h.getCheckOutTime(), h.getCancellationPolicy(), h.getTaxRatePercent(), h.getServiceFeePercent(),
                h.getAvgRating(), h.getReviewCount(), imagesOf(h), AmenityRef.sorted(h.getAmenities()), types);
    }

    HotelDtos.ManagedHotelResponse managedView(Hotel h) {
        List<RoomTypeResponse> types = roomTypes.findByHotelIdOrderByBasePriceAscNameAsc(h.getId())
                .stream().map(RoomTypeResponse::from).toList();
        return new HotelDtos.ManagedHotelResponse(h.getId(), h.getName(), h.getDescription(), h.getAddress(),
                h.getCity(), h.getCountryCode(), h.getStarRating(), h.getTimeZone(), h.getCheckInTime(),
                h.getCheckOutTime(), h.getCancellationPolicy(), h.getTaxRatePercent(), h.getServiceFeePercent(),
                h.getAvgRating(), h.getReviewCount(), imagesOf(h), AmenityRef.sorted(h.getAmenities()), types,
                h.getStatus(), h.getApprovedAt(),
                managers.findByIdHotelId(h.getId()).stream().map(HotelManager::getUserId).sorted().toList(),
                h.getVersion());
    }

    private List<HotelDtos.ImageResponse> imagesOf(Hotel h) {
        return images.findByHotelIdOrderBySortOrderAsc(h.getId()).stream().map(HotelDtos.ImageResponse::from).toList();
    }
}
