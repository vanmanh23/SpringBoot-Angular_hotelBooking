package com.stayhotel.hotel.hotel;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import com.stayhotel.hotel.amenity.AmenityDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Public hotel detail plus management endpoints. Ownership is enforced in the services, not here. */
@RestController
class HotelController {

    private final HotelQueryService queries;
    private final HotelManagementService management;

    HotelController(HotelQueryService queries, HotelManagementService management) {
        this.queries = queries;
        this.management = management;
    }

    @GetMapping("/api/v1/hotels/{id}")
    HotelDtos.HotelDetailResponse detail(@PathVariable UUID id) {
        return queries.publicDetail(id);
    }

    @GetMapping("/api/v1/manage/hotels")
    PageResponse<HotelQueryService.ManagedHotelSummary> listManaged(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) HotelStatus status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.listManaged(CurrentUser.from(jwt), status, q,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id"))));
    }

    @GetMapping("/api/v1/manage/hotels/{id}")
    HotelDtos.ManagedHotelResponse getManaged(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return management.get(CurrentUser.from(jwt), id);
    }

    @PostMapping("/api/v1/hotels")
    @ResponseStatus(HttpStatus.CREATED)
    HotelDtos.ManagedHotelResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HotelDtos.HotelRequest request) {
        return management.create(CurrentUser.from(jwt), request);
    }

    @PutMapping("/api/v1/hotels/{id}")
    HotelDtos.ManagedHotelResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody HotelDtos.HotelRequest request) {
        return management.update(CurrentUser.from(jwt), id, request);
    }

    @PatchMapping("/api/v1/hotels/{id}/status")
    HotelDtos.ManagedHotelResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @Valid @RequestBody HotelDtos.StatusRequest request) {
        return management.changeStatus(CurrentUser.from(jwt), id, request.status());
    }

    @PutMapping("/api/v1/hotels/{id}/amenities")
    HotelDtos.ManagedHotelResponse replaceAmenities(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @Valid @RequestBody AmenityDtos.AmenityCodesRequest request) {
        return management.replaceAmenities(CurrentUser.from(jwt), id, request.codes());
    }

    @PostMapping("/api/v1/hotels/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    HotelDtos.ImageResponse addImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody HotelDtos.ImageRequest request) {
        return management.addImage(CurrentUser.from(jwt), id, request);
    }

    @DeleteMapping("/api/v1/hotels/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID imageId) {
        management.deleteImage(CurrentUser.from(jwt), id, imageId);
    }

    @GetMapping("/api/v1/hotels/{id}/managers")
    @PreAuthorize("hasRole('ADMIN')")
    List<UUID> managers(@PathVariable UUID id) {
        return management.listManagers(id);
    }

    @PostMapping("/api/v1/hotels/{id}/managers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    List<UUID> assignManager(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody HotelDtos.ManagerRequest request) {
        return management.assignManager(CurrentUser.from(jwt), id, request.userId());
    }

    @DeleteMapping("/api/v1/hotels/{id}/managers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void unassignManager(@PathVariable UUID id, @PathVariable UUID userId) {
        management.unassignManager(id, userId);
    }
}
