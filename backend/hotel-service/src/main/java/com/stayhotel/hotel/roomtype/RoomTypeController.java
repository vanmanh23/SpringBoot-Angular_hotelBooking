package com.stayhotel.hotel.roomtype;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.hotel.amenity.AmenityDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class RoomTypeController {

    private final RoomTypeService service;

    RoomTypeController(RoomTypeService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/hotels/{hotelId}/room-types")
    List<RoomTypeDtos.RoomTypeResponse> listPublic(@PathVariable UUID hotelId,
                                                   @RequestParam(defaultValue = "1") @Min(1) @Max(40) int guests,
                                                   @RequestParam(defaultValue = "1") @Min(1) @Max(10) int rooms) {
        return service.listSuitable(hotelId, guests, rooms);
    }

    @PostMapping("/api/v1/hotels/{hotelId}/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    RoomTypeDtos.RoomTypeResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID hotelId,
                                         @Valid @RequestBody RoomTypeDtos.RoomTypeRequest request) {
        return service.create(CurrentUser.from(jwt), hotelId, request);
    }

    @PutMapping("/api/v1/room-types/{id}")
    RoomTypeDtos.RoomTypeResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @Valid @RequestBody RoomTypeDtos.RoomTypeRequest request) {
        return service.update(CurrentUser.from(jwt), id, request);
    }

    @PatchMapping("/api/v1/room-types/{id}/status")
    RoomTypeDtos.RoomTypeResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @Valid @RequestBody RoomTypeDtos.StatusRequest request) {
        return service.changeStatus(CurrentUser.from(jwt), id, request.status());
    }

    @PutMapping("/api/v1/room-types/{id}/amenities")
    RoomTypeDtos.RoomTypeResponse replaceAmenities(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @Valid @RequestBody AmenityDtos.AmenityCodesRequest request) {
        return service.replaceAmenities(CurrentUser.from(jwt), id, request.codes());
    }
}
