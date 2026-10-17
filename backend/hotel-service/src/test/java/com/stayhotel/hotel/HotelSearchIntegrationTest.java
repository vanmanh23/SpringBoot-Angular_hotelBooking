package com.stayhotel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stayhotel.hotel.HotelApi.hotelRequest;
import static com.stayhotel.hotel.HotelApi.roomTypeRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;

/**
 * Fixture (one city, built once): five ACTIVE hotels + one DRAFT, with different prices, capacities, stars and
 * amenities. The city name has Vietnamese diacritics and a random suffix so other tests cannot interfere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HotelSearchIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper objectMapper;

    HotelApi api;
    final String suffix = UUID.randomUUID().toString().substring(0, 6);
    final String city = "Đà Nẵng " + suffix;
    String budget;
    String family;
    String luxury;

    @BeforeAll
    void fixture() {
        api = new HotelApi(http, objectMapper);
        budget = hotel("Budget Hostel", 2, 300_000, 5, 2, List.of("WIFI"));
        family = hotel("Family Resort", 4, 1_200_000, 3, 4, List.of("WIFI", "POOL", "PARKING"));
        luxury = hotel("Luxury Palace", 2, 5_000_000, 2, 5, List.of("WIFI", "POOL", "SPA"));
        hotel("Tiny Room", 1, 200_000, 1, 1, List.of());
        hotel("Mid Hotel", 2, 800_000, 4, 3, List.of("PARKING"));
        // A cheap second room type must lower the family resort's "from" price only when it fits the party.
        api.addRoomType(api.manager, family, roomTypeRequest("Family Twin", 2, 900_000, 2));
        String draft = api.createHotel(api.manager, hotelRequest("Hidden Draft", city));
        api.addRoomType(api.manager, draft, roomTypeRequest("Std", 4, 100_000, 10));
        api.call(POST, "/api/v1/hotels/" + draft + "/images", Map.of("url", "https://img.example/x.jpg"), api.manager);
        api.call(POST, "/api/v1/hotels/" + luxury + "/images", Map.of("url", "https://img.example/lux-1.jpg"), api.manager);
        api.call(POST, "/api/v1/hotels/" + luxury + "/images", Map.of("url", "https://img.example/lux-2.jpg"), api.manager);
    }

    private String hotel(String name, int capacity, long price, int units, int stars, List<String> amenities) {
        Map<String, Object> request = hotelRequest(name, city);
        request.put("starRating", stars);
        String id = api.createHotel(api.manager, request);
        api.addRoomType(api.manager, id, roomTypeRequest("Standard", capacity, price, units));
        api.setAmenities(api.manager, id, amenities);
        api.setStatus(api.admin, id, "ACTIVE");
        return id;
    }

    private JsonNode search(String query) {
        ResponseEntity<String> res = api.get("/api/v1/hotels?city=" + enc("da nang " + suffix) + "&" + query);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return api.body(res);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static List<String> names(JsonNode page) {
        return page.get("content").findValuesAsText("name");
    }

    @Test
    void cityMatchIgnoresCaseAndDiacriticsAndHidesNonActiveHotels() {
        JsonNode page = search("sort=name_asc");
        assertThat(names(page)).containsExactly("Budget Hostel", "Family Resort", "Luxury Palace", "Mid Hotel", "Tiny Room");
        assertThat(page.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page.get("content").get(0).get("city").asText()).isEqualTo(city);
    }

    @Test
    void capacityAndRoomCountFilterHotels() {
        assertThat(names(search("guests=3&sort=name_asc"))).containsExactly("Family Resort");
        // 4 guests in 2 rooms: capacity-2 types qualify if the hotel has >= 2 units.
        assertThat(names(search("guests=4&rooms=2&sort=name_asc")))
                .containsExactly("Budget Hostel", "Family Resort", "Luxury Palace", "Mid Hotel");
    }

    @Test
    void fromPriceIsCheapestSuitableRoomType() {
        JsonNode twoGuests = search("guests=2&sort=price_asc");
        JsonNode familyRow = findById(twoGuests, family);
        assertThat(familyRow.get("fromPrice").asLong()).isEqualTo(900_000);
        assertThat(familyRow.get("currency").asText()).isEqualTo("VND");

        JsonNode fourGuests = search("guests=4");
        assertThat(findById(fourGuests, family).get("fromPrice").asLong()).isEqualTo(1_200_000);
    }

    @Test
    void sortingByPrice() {
        assertThat(names(search("sort=price_asc")))
                .containsExactly("Tiny Room", "Budget Hostel", "Mid Hotel", "Family Resort", "Luxury Palace");
        assertThat(names(search("sort=price_desc")).get(0)).isEqualTo("Luxury Palace");
    }

    @Test
    void priceStarsAndAmenityFilters() {
        assertThat(names(search("minPrice=500000&maxPrice=1000000&sort=name_asc"))).containsExactly("Family Resort", "Mid Hotel");
        assertThat(names(search("stars=4,5&sort=name_asc"))).containsExactly("Family Resort", "Luxury Palace");
        assertThat(names(search("amenities=pool,wifi&sort=name_asc"))).containsExactly("Family Resort", "Luxury Palace");
        assertThat(names(search("amenities=POOL,PARKING"))).containsExactly("Family Resort");
    }

    @Test
    void summaryCarriesThumbnailAndAmenityCodes() {
        JsonNode luxuryRow = findById(search("q=palace"), luxury);
        assertThat(luxuryRow.get("thumbnailUrl").asText()).isEqualTo("https://img.example/lux-1.jpg");
        assertThat(luxuryRow.get("amenities").toString()).isEqualTo("[\"POOL\",\"SPA\",\"WIFI\"]");
        assertThat(luxuryRow.get("starRating").asInt()).isEqualTo(5);
    }

    @Test
    void paginationIsStableAndReportsTotals() {
        JsonNode first = search("sort=name_asc&size=2&page=0");
        JsonNode third = search("sort=name_asc&size=2&page=2");
        JsonNode beyond = search("sort=name_asc&size=2&page=5");

        assertThat(names(first)).containsExactly("Budget Hostel", "Family Resort");
        assertThat(names(third)).containsExactly("Tiny Room");
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(beyond.get("content").size()).isZero();
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(5);
    }

    @Test
    void validDatesAreAccepted() {
        LocalDate in = LocalDate.now().plusDays(10);
        assertThat(search("checkIn=" + in + "&checkOut=" + in.plusDays(3)).get("totalElements").asLong()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0} -> field {1}")
    @CsvSource({
            "checkIn=2999-01-10&checkOut=2999-01-10, checkOut",
            "checkIn=2999-01-10&checkOut=2999-03-10, checkOut",
            "checkIn=2999-01-10, checkOut",
            "checkIn=2000-01-01&checkOut=2000-01-02, checkIn",
            "minPrice=500&maxPrice=100, minPrice",
            "stars=6, stars",
            "stars=abc, stars",
            "amenities=bad-code, amenities",
            "sort=cheapest, sort",
            "size=51, size",
            "guests=0, guests",
            "page=-1, page"
    })
    void invalidParametersReturn400WithFieldError(String query, String field) {
        ResponseEntity<String> res = api.get("/api/v1/hotels?" + query);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = api.body(res);
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("fieldErrors").findValuesAsText("field")).contains(field);
    }

    @Test
    void publicRoomTypesFilterByParty() {
        assertThat(api.body(api.get("/api/v1/hotels/" + family + "/room-types?guests=3")).findValuesAsText("name"))
                .containsExactly("Standard");
        assertThat(api.body(api.get("/api/v1/hotels/" + family + "/room-types?guests=2")).findValuesAsText("name"))
                .containsExactly("Family Twin", "Standard");
        assertThat(api.get("/api/v1/hotels/" + UUID.randomUUID() + "/room-types").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publicDetailShowsCatalogueData() {
        JsonNode detail = api.body(api.get("/api/v1/hotels/" + luxury));
        assertThat(detail.get("images").findValuesAsText("url"))
                .containsExactly("https://img.example/lux-1.jpg", "https://img.example/lux-2.jpg");
        assertThat(detail.get("amenities").findValuesAsText("code")).containsExactlyInAnyOrder("WIFI", "POOL", "SPA");
        assertThat(detail.get("checkOutTime").asText()).isEqualTo("12:00");
        assertThat(detail.get("roomTypes").get(0).get("basePrice").toString()).isEqualTo("5000000");
    }

    private static JsonNode findById(JsonNode page, String id) {
        for (JsonNode row : page.get("content")) {
            if (row.get("id").asText().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("hotel " + id + " not in page " + page);
    }
}
