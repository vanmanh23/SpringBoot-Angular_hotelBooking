package com.stayhotel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stayhotel.hotel.HotelApi.hotelRequest;
import static com.stayhotel.hotel.HotelApi.roomTypeRequest;
import static com.stayhotel.hotel.HotelApi.uniqueCity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class HotelManagementIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper objectMapper;

    HotelApi api;

    @BeforeEach
    void setUp() {
        api = new HotelApi(http, objectMapper);
    }

    @Nested
    class CreationAndApproval {

        @Test
        void onlyAdminsAndManagersCanCreate() {
            Map<String, Object> request = hotelRequest("Nope", uniqueCity());
            assertThat(api.call(POST, "/api/v1/hotels", request, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<String> customer = api.call(POST, "/api/v1/hotels", request, api.customer);
            assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.body(customer).get("code").asText()).isEqualTo("FORBIDDEN");
        }

        @Test
        void managerCreatesDraftThatStaysPrivateUntilAdminApproves() {
            ResponseEntity<String> created = api.call(POST, "/api/v1/hotels", hotelRequest("Draft Inn", uniqueCity()), api.manager);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = api.body(created);
            String id = body.get("id").asText();
            assertThat(body.get("status").asText()).isEqualTo("DRAFT");
            assertThat(body.get("timeZone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(body.get("checkInTime").asText()).isEqualTo("14:00");
            assertThat(body.get("taxRatePercent").decimalValue()).isEqualByComparingTo("8");
            assertThat(body.get("serviceFeePercent").decimalValue()).isEqualByComparingTo("5");
            assertThat(body.get("managerIds").toString()).contains(api.managerId.toString());

            assertThat(api.get("/api/v1/hotels/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            ResponseEntity<String> selfApprove = api.call(PATCH, "/api/v1/hotels/" + id + "/status", Map.of("status", "ACTIVE"), api.manager);
            assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            api.setStatus(api.admin, id, "ACTIVE");
            ResponseEntity<String> publicView = api.get("/api/v1/hotels/" + id);
            assertThat(publicView.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.body(publicView).has("status")).as("public view hides lifecycle data").isFalse();
        }

        @Test
        void invalidInputIsRejectedPerField() {
            Map<String, Object> request = hotelRequest("", uniqueCity());
            request.put("countryCode", "vn");
            request.put("starRating", 6);
            request.put("timeZone", "+07:00");
            ResponseEntity<String> res = api.call(POST, "/api/v1/hotels", request, api.admin);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.body(res).get("fieldErrors").findValuesAsText("field"))
                    .contains("name", "countryCode", "starRating", "timeZone");
        }
    }

    @Nested
    class StatusMachine {

        @Test
        void managerCanHideAndReshowApprovedHotelButNotLiftSuspension() {
            String id = api.activeHotel("Status Hotel", uniqueCity(), 2, 500_000, 3);

            api.setStatus(api.manager, id, "INACTIVE");
            assertThat(api.get("/api/v1/hotels/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            api.setStatus(api.manager, id, "ACTIVE");
            assertThat(api.get("/api/v1/hotels/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);

            api.setStatus(api.admin, id, "SUSPENDED");
            assertThat(api.call(PATCH, "/api/v1/hotels/" + id + "/status", Map.of("status", "ACTIVE"), api.manager)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void impossibleTransitionIsAConflict() {
            String id = api.createHotel(api.manager, hotelRequest("Draft", uniqueCity()));
            ResponseEntity<String> res = api.call(PATCH, "/api/v1/hotels/" + id + "/status", Map.of("status", "INACTIVE"), api.admin);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.body(res).get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
        }
    }

    @Nested
    class Ownership {

        @Test
        void otherManagersCannotTouchTheHotel() {
            String id = api.createHotel(api.manager, hotelRequest("Mine", uniqueCity()));
            String path = "/api/v1/hotels/" + id;

            assertThat(api.call(GET, "/api/v1/manage/hotels/" + id, null, api.otherManager).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            Map<String, Object> update = hotelRequest("Hijacked", uniqueCity());
            update.put("version", 0);
            assertThat(api.call(PUT, path, update, api.otherManager).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.call(POST, path + "/room-types", roomTypeRequest("X", 2, 100_000, 1), api.otherManager)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.call(POST, path + "/images", Map.of("url", "https://img.example/x.jpg"), api.otherManager)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.call(PUT, path + "/amenities", Map.of("codes", List.of("WIFI")), api.otherManager)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            JsonNode otherList = api.body(api.call(GET, "/api/v1/manage/hotels?size=100", null, api.otherManager));
            assertThat(otherList.get("content").findValuesAsText("id")).doesNotContain(id);
            JsonNode adminList = api.body(api.call(GET, "/api/v1/manage/hotels?size=100&status=DRAFT", null, api.admin));
            assertThat(adminList.get("content").findValuesAsText("id")).contains(id);
        }

        @Test
        void roomTypeOfAnotherManagersHotelCannotBeEdited() {
            String hotelId = api.createHotel(api.manager, hotelRequest("Mine 2", uniqueCity()));
            String roomTypeId = api.addRoomType(api.manager, hotelId, roomTypeRequest("Deluxe", 2, 900_000, 2));
            Map<String, Object> update = roomTypeRequest("Stolen", 2, 1_000, 2);
            update.put("version", 0);

            assertThat(api.call(PUT, "/api/v1/room-types/" + roomTypeId, update, api.otherManager).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.call(PATCH, "/api/v1/room-types/" + roomTypeId + "/status", Map.of("status", "INACTIVE"),
                    api.otherManager).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void adminAssignmentGrantsAndRevokesAccess() {
            String id = api.createHotel(api.manager, hotelRequest("Shared", uniqueCity()));
            String managers = "/api/v1/hotels/" + id + "/managers";

            assertThat(api.call(POST, managers, Map.of("userId", api.otherManagerId), api.manager).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<String> assigned = api.call(POST, managers, Map.of("userId", api.otherManagerId), api.admin);
            assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(api.call(GET, "/api/v1/manage/hotels/" + id, null, api.otherManager).getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(api.call(DELETE, managers + "/" + api.otherManagerId, null, api.admin).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(api.call(GET, "/api/v1/manage/hotels/" + id, null, api.otherManager).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void assignmentWithoutManagerRoleGrantsNothing() {
            String id = api.createHotel(api.manager, hotelRequest("Role check", uniqueCity()));
            api.call(POST, "/api/v1/hotels/" + id + "/managers", Map.of("userId", api.customerId), api.admin);

            assertThat(api.call(GET, "/api/v1/manage/hotels/" + id, null, api.customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void unknownHotelIs404NotForbidden() {
            assertThat(api.call(GET, "/api/v1/manage/hotels/" + UUID.randomUUID(), null, api.manager).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class OptimisticLocking {

        @Test
        void staleOrMissingVersionIsRejectedAndVersionAdvances() {
            String id = api.createHotel(api.manager, hotelRequest("Versioned", uniqueCity()));
            Map<String, Object> update = hotelRequest("Versioned v2", uniqueCity());

            assertThat(api.call(PUT, "/api/v1/hotels/" + id, update, api.manager).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            update.put("version", 0);
            ResponseEntity<String> ok = api.call(PUT, "/api/v1/hotels/" + id, update, api.manager);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.body(ok).get("version").asLong()).isEqualTo(1);

            ResponseEntity<String> stale = api.call(PUT, "/api/v1/hotels/" + id, update, api.manager);
            assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.body(stale).get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
        }
    }

    @Nested
    class RoomTypesImagesAmenities {

        @Test
        void roomTypeValidationAndUniqueness() {
            String hotelId = api.createHotel(api.manager, hotelRequest("Rooms", uniqueCity()));
            api.addRoomType(api.manager, hotelId, roomTypeRequest("Deluxe", 2, 900_000, 2));
            String path = "/api/v1/hotels/" + hotelId + "/room-types";

            ResponseEntity<String> duplicate = api.call(POST, path, roomTypeRequest("deluxe", 2, 900_000, 2), api.manager);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.body(duplicate).get("code").asText()).isEqualTo("ROOM_TYPE_NAME_TAKEN");

            Map<String, Object> bad = roomTypeRequest("Bad", 0, 999, -1);
            bad.put("basePrice", 1500.5);
            ResponseEntity<String> invalid = api.call(POST, path, bad, api.manager);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.body(invalid).get("fieldErrors").findValuesAsText("field"))
                    .contains("capacity", "basePrice", "totalUnits");
        }

        @Test
        void inactiveRoomTypesAreHiddenFromPublicDetail() {
            String hotelId = api.activeHotel("Visibility", uniqueCity(), 2, 400_000, 2);
            String suite = api.addRoomType(api.manager, hotelId, roomTypeRequest("Suite", 4, 2_000_000, 1));
            assertThat(api.body(api.get("/api/v1/hotels/" + hotelId)).get("roomTypes").findValuesAsText("name"))
                    .containsExactly("Standard", "Suite");

            api.call(PATCH, "/api/v1/room-types/" + suite + "/status", Map.of("status", "INACTIVE"), api.manager);

            assertThat(api.body(api.get("/api/v1/hotels/" + hotelId)).get("roomTypes").findValuesAsText("name"))
                    .containsExactly("Standard");
            assertThat(api.body(api.call(GET, "/api/v1/manage/hotels/" + hotelId, null, api.manager))
                    .get("roomTypes").findValuesAsText("name")).containsExactlyInAnyOrder("Standard", "Suite");
        }

        @Test
        void imagesMustBeHttpsLimitedAndScopedToHotel() {
            String hotelId = api.createHotel(api.manager, hotelRequest("Gallery", uniqueCity()));
            String otherHotel = api.createHotel(api.manager, hotelRequest("Other", uniqueCity()));
            String images = "/api/v1/hotels/" + hotelId + "/images";

            assertThat(api.call(POST, images, Map.of("url", "http://insecure.example/a.jpg"), api.manager).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            String firstImage = null;
            for (int i = 0; i < 20; i++) {
                ResponseEntity<String> res = api.call(POST, images, Map.of("url", "https://img.example/" + i + ".jpg"), api.manager);
                assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                if (i == 0) {
                    firstImage = api.body(res).get("id").asText();
                }
            }
            ResponseEntity<String> overLimit = api.call(POST, images, Map.of("url", "https://img.example/21.jpg"), api.manager);
            assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.body(overLimit).get("code").asText()).isEqualTo("IMAGE_LIMIT_REACHED");

            assertThat(api.call(DELETE, "/api/v1/hotels/" + otherHotel + "/images/" + firstImage, null, api.manager)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.call(DELETE, images + "/" + firstImage, null, api.manager).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        void amenitiesMustExistAndMatchCategory() {
            String hotelId = api.createHotel(api.manager, hotelRequest("Amenities", uniqueCity()));
            String path = "/api/v1/hotels/" + hotelId + "/amenities";

            ResponseEntity<String> unknown = api.call(PUT, path, Map.of("codes", List.of("WIFI", "TELEPORTER")), api.manager);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.body(unknown).get("code").asText()).isEqualTo("UNKNOWN_AMENITY");
            assertThat(api.call(PUT, path, Map.of("codes", List.of("SEA_VIEW")), api.manager).getStatusCode())
                    .as("room amenity on a hotel").isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseEntity<String> ok = api.call(PUT, path, Map.of("codes", List.of("WIFI", "POOL")), api.manager);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.body(ok).get("amenities").findValuesAsText("code")).containsExactlyInAnyOrder("WIFI", "POOL");
        }

        @Test
        void amenityCatalogueIsPublicButOnlyAdminsExtendIt() {
            assertThat(api.body(api.get("/api/v1/amenities?category=ROOM")).findValuesAsText("code")).contains("SEA_VIEW");
            Map<String, Object> amenity = Map.of("code", "ROOFTOP_BAR", "name", "Rooftop bar", "category", "HOTEL");
            assertThat(api.call(POST, "/api/v1/amenities", amenity, api.manager).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.call(POST, "/api/v1/amenities", amenity, api.admin).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(api.call(POST, "/api/v1/amenities", amenity, api.admin).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void writesWithForeignOrExpiredTokensAreRejected() {
        Map<String, Object> request = hotelRequest("x", uniqueCity());
        assertThat(api.call(HttpMethod.POST, "/api/v1/hotels", request,
                TestJwtSupport.tokenSignedByUnknownKey(UUID.randomUUID(), "ADMIN")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.call(HttpMethod.POST, "/api/v1/hotels", request,
                TestJwtSupport.expiredToken(UUID.randomUUID(), "ADMIN")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
