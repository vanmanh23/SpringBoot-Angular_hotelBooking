package com.stayhotel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.security.test.TestJwtSupport;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP helpers and fixture builders for hotel-service integration tests. */
final class HotelApi {

    final UUID adminId = UUID.randomUUID();
    final UUID managerId = UUID.randomUUID();
    final UUID otherManagerId = UUID.randomUUID();
    final UUID customerId = UUID.randomUUID();

    final String admin = TestJwtSupport.token(adminId, "ADMIN");
    final String manager = TestJwtSupport.token(managerId, "HOTEL_MANAGER");
    final String otherManager = TestJwtSupport.token(otherManagerId, "HOTEL_MANAGER");
    final String customer = TestJwtSupport.token(customerId, "CUSTOMER");

    private final TestRestTemplate http;
    private final ObjectMapper json;

    HotelApi(TestRestTemplate http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    ResponseEntity<String> get(String path) {
        return call(HttpMethod.GET, path, null, null);
    }

    JsonNode body(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(res.getBody(), e);
        }
    }

    static Map<String, Object> hotelRequest(String name, String city) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("address", "1 Test Street");
        body.put("city", city);
        body.put("countryCode", "VN");
        body.put("starRating", 4);
        body.put("description", "A test hotel");
        return body;
    }

    static Map<String, Object> roomTypeRequest(String name, int capacity, long price, int units) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("capacity", capacity);
        body.put("basePrice", price);
        body.put("totalUnits", units);
        body.put("bedDescription", "1 queen bed");
        return body;
    }

    /** Creates a hotel as {@code token}; returns its id. */
    String createHotel(String token, Map<String, Object> request) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/hotels", request, token);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
        return body(res).get("id").asText();
    }

    String addRoomType(String token, String hotelId, Map<String, Object> request) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/hotels/" + hotelId + "/room-types", request, token);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
        return body(res).get("id").asText();
    }

    void setStatus(String token, String hotelId, String status) {
        ResponseEntity<String> res = call(HttpMethod.PATCH, "/api/v1/hotels/" + hotelId + "/status",
                Map.of("status", status), token);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
    }

    void setAmenities(String token, String hotelId, List<String> codes) {
        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/hotels/" + hotelId + "/amenities",
                Map.of("codes", codes), token);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Active hotel (created by the manager, approved by the admin) with one room type. */
    String activeHotel(String name, String city, int capacity, long price, int units) {
        String id = createHotel(manager, hotelRequest(name, city));
        addRoomType(manager, id, roomTypeRequest("Standard", capacity, price, units));
        setStatus(admin, id, "ACTIVE");
        return id;
    }

    static String uniqueCity() {
        return "Testcity " + UUID.randomUUID().toString().substring(0, 8);
    }
}
