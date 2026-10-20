package com.stayhotel.hotel.hotel;

import com.stayhotel.common.audit.AuditTrail;
import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.error.ErrorCode;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.hotel.HotelErrors;
import com.stayhotel.hotel.amenity.AmenityCategory;
import com.stayhotel.hotel.amenity.AmenityService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Write side of the hotel catalogue. Every method checks resource ownership via {@link HotelAccessPolicy}. */
@Service
public class HotelManagementService {

    static final int MAX_IMAGES = 20;

    private final HotelRepository hotels;
    private final HotelImageRepository images;
    private final HotelManagerRepository managers;
    private final HotelAccessPolicy access;
    private final AmenityService amenities;
    private final HotelViewAssembler views;
    private final AuditTrail audit;
    private final Clock clock;

    HotelManagementService(HotelRepository hotels, HotelImageRepository images, HotelManagerRepository managers,
                           HotelAccessPolicy access, AmenityService amenities, HotelViewAssembler views, AuditTrail audit,
                           Clock clock) {
        this.hotels = hotels;
        this.images = images;
        this.managers = managers;
        this.access = access;
        this.amenities = amenities;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public HotelDtos.ManagedHotelResponse create(CurrentUser user, HotelDtos.HotelRequest request) {
        access.requireCreator(user);
        Instant now = clock.instant();
        Hotel hotel = hotels.save(Hotel.draft(request.toDetails(), now));
        if (!user.isAdmin()) {
            managers.save(new HotelManager(hotel.getId(), user.id(), user.id(), now));
        }
        return flushedView(hotel);
    }

    @Transactional(readOnly = true)
    public HotelDtos.ManagedHotelResponse get(CurrentUser user, UUID hotelId) {
        return views.managedView(loadManaged(user, hotelId));
    }

    @Transactional
    public HotelDtos.ManagedHotelResponse update(CurrentUser user, UUID hotelId, HotelDtos.HotelRequest request) {
        Hotel hotel = loadManaged(user, hotelId);
        requireVersion(request.version(), hotel.getVersion());
        hotel.apply(request.toDetails(), clock.instant());
        return flushedView(hotel);
    }

    @Transactional
    public HotelDtos.ManagedHotelResponse changeStatus(CurrentUser user, UUID hotelId, HotelStatus target) {
        Hotel hotel = loadManaged(user, hotelId);
        if (!hotel.getStatus().canTransitionTo(target, user.isAdmin(), hotel.wasApproved())) {
            if (!user.isAdmin() && hotel.getStatus().canTransitionTo(target, true, hotel.wasApproved())) {
                throw HotelErrors.forbidden(); // allowed for admins only (approval, suspension)
            }
            throw HotelErrors.invalidTransition(hotel.getStatus(), target);
        }
        HotelStatus previous = hotel.getStatus();
        hotel.changeStatus(target, clock.instant());
        audit.record(user.id(), "HOTEL_STATUS_CHANGED", "HOTEL", hotelId, Map.of("from", previous, "to", target));
        return flushedView(hotel);
    }

    @Transactional
    public HotelDtos.ManagedHotelResponse replaceAmenities(CurrentUser user, UUID hotelId, Set<String> codes) {
        Hotel hotel = loadManaged(user, hotelId);
        hotel.replaceAmenities(amenities.resolve(codes, AmenityCategory.HOTEL), clock.instant());
        return flushedView(hotel);
    }

    @Transactional
    public HotelDtos.ImageResponse addImage(CurrentUser user, UUID hotelId, HotelDtos.ImageRequest request) {
        loadManaged(user, hotelId);
        if (images.countByHotelId(hotelId) >= MAX_IMAGES) {
            throw HotelErrors.imageLimitReached(MAX_IMAGES);
        }
        HotelImage image = images.save(new HotelImage(hotelId, request.url().trim(), request.caption(),
                images.maxSortOrder(hotelId) + 1, clock.instant()));
        return HotelDtos.ImageResponse.from(image);
    }

    @Transactional
    public void deleteImage(CurrentUser user, UUID hotelId, UUID imageId) {
        loadManaged(user, hotelId);
        HotelImage image = images.findById(imageId)
                .filter(i -> i.getHotelId().equals(hotelId)) // an image id of another hotel is "not found" here
                .orElseThrow(HotelErrors::imageNotFound);
        images.delete(image);
    }

    @Transactional(readOnly = true)
    public List<UUID> listManagers(UUID hotelId) {
        requireExists(hotelId);
        return managerIds(hotelId);
    }

    /** Idempotent: assigning an already assigned user is a no-op. */
    @Transactional
    public List<UUID> assignManager(CurrentUser admin, UUID hotelId, UUID userId) {
        requireExists(hotelId);
        if (!managers.existsByIdHotelIdAndIdUserId(hotelId, userId)) {
            managers.save(new HotelManager(hotelId, userId, admin.id(), clock.instant()));
            audit.record(admin.id(), "HOTEL_MANAGER_ASSIGNED", "HOTEL", hotelId, Map.of("userId", userId));
        }
        return managerIds(hotelId);
    }

    @Transactional
    public void unassignManager(CurrentUser admin, UUID hotelId, UUID userId) {
        requireExists(hotelId);
        HotelManager.Key key = new HotelManager.Key(hotelId, userId);
        if (!managers.existsById(key)) {
            throw HotelErrors.managerNotFound();
        }
        managers.deleteById(key);
        audit.record(admin.id(), "HOTEL_MANAGER_UNASSIGNED", "HOTEL", hotelId, Map.of("userId", userId));
    }

    /**
     * Flushing first makes optimistic-lock conflicts surface inside this call (as 409) and puts the incremented
     * version into the response, so the client's next update uses the right value.
     */
    private HotelDtos.ManagedHotelResponse flushedView(Hotel hotel) {
        hotels.flush();
        return views.managedView(hotel);
    }

    private Hotel loadManaged(CurrentUser user, UUID hotelId) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow(HotelErrors::hotelNotFound);
        access.requireManage(user, hotelId);
        return hotel;
    }

    private void requireExists(UUID hotelId) {
        if (!hotels.existsById(hotelId)) {
            throw HotelErrors.hotelNotFound();
        }
    }

    private List<UUID> managerIds(UUID hotelId) {
        return managers.findByIdHotelId(hotelId).stream().map(HotelManager::getUserId).sorted().toList();
    }

    public static void requireVersion(Long requested, long current) {
        if (requested == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "version is required for updates");
        }
        if (requested != current) {
            throw HotelErrors.staleVersion();
        }
    }
}
