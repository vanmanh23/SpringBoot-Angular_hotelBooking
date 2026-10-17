package com.stayhotel.hotel.amenity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/amenities")
class AmenityController {

    private final AmenityService service;

    AmenityController(AmenityService service) {
        this.service = service;
    }

    @GetMapping
    List<AmenityDtos.AmenityResponse> list(@RequestParam(required = false) AmenityCategory category) {
        return service.list(category);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    AmenityDtos.AmenityResponse create(@Valid @RequestBody AmenityDtos.CreateAmenityRequest request) {
        return service.create(request);
    }
}
