package com.stayhotel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.messaging.EventEnvelope;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.test.TestJwtDecoderConfiguration;
import com.stayhotel.hotel.hotel.RatingEventsListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static com.stayhotel.hotel.HotelApi.hotelRequest;
import static com.stayhotel.hotel.HotelApi.uniqueCity;
import static org.assertj.core.api.Assertions.assertThat;

/** Hotel score projection from review-service (D-028): highest version wins; hotel edits never overwrite it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtDecoderConfiguration.class})
class RatingProjectionIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    RatingEventsListener listener;
    @Autowired
    KafkaTemplate<String, String> kafka;

    HotelApi api;
    String hotelId;

    @BeforeEach
    void setUp() {
        api = new HotelApi(http, json);
        hotelId = api.activeHotel("Rated Inn", uniqueCity(), 2, 1_000_000, 3);
    }

    private String event(UUID eventId, String avg, int count, long version) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("hotelId", hotelId);
        payload.put("avgRating", avg);
        payload.put("reviewCount", count);
        payload.put("version", version);
        return json.writeValueAsString(new EventEnvelope(eventId, "HotelRatingChanged", 1, Instant.now(), "test", hotelId,
                json.valueToTree(payload)));
    }

    private JsonNode publicView() {
        return api.body(api.get("/api/v1/hotels/" + hotelId));
    }

    @Test
    void newestVersionWinsRegardlessOfDeliveryOrder() throws Exception {
        assertThat(publicView().get("reviewCount").asInt()).isZero();

        listener.onMessage(event(UUID.randomUUID(), "4.5", 2, 2));
        listener.onMessage(event(UUID.randomUUID(), "4.0", 1, 1)); // late, older version
        UUID dup = UUID.randomUUID();
        listener.onMessage(event(dup, "4.3", 3, 3));
        listener.onMessage(event(dup, "4.3", 3, 3));

        JsonNode view = publicView();
        assertThat(view.get("avgRating").decimalValue()).isEqualByComparingTo("4.3");
        assertThat(view.get("reviewCount").asInt()).isEqualTo(3);
    }

    @Test
    void managerEditDoesNotOverwriteTheScoreNorConflictWithIt() throws Exception {
        long before = api.body(api.call(HttpMethod.GET, "/api/v1/manage/hotels/" + hotelId, null, api.manager))
                .get("version").asLong();
        listener.onMessage(event(UUID.randomUUID(), "3.7", 4, 1));

        Map<String, Object> update = hotelRequest("Rated Inn Renamed", uniqueCity());
        update.put("version", before); // the rating event must not have bumped the optimistic-lock version
        var res = api.call(HttpMethod.PUT, "/api/v1/hotels/" + hotelId, update, api.manager);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode view = publicView();
        assertThat(view.get("name").asText()).isEqualTo("Rated Inn Renamed");
        assertThat(view.get("avgRating").decimalValue()).isEqualByComparingTo("3.7");
        assertThat(view.get("reviewCount").asInt()).isEqualTo(4);
    }

    @Test
    void consumesFromTheRealBroker() throws Exception {
        kafka.send(Topics.REVIEW_EVENTS, hotelId, event(UUID.randomUUID(), "5.0", 1, 1)).get();
        await(() -> publicView().get("reviewCount").asInt() == 1, "rating applied from Kafka");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
