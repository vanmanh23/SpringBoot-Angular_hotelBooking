package com.stayhotel.booking.catalog;

import com.stayhotel.common.logging.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the public hotel detail of hotel-service over the internal network. Short timeouts keep a slow
 * catalogue from tying up booking threads (design 5.5.1); no retry because the caller can simply try again.
 */
@Component
class HttpHotelCatalog implements HotelCatalog {

    private static final Logger log = LoggerFactory.getLogger(HttpHotelCatalog.class);

    private final RestClient client;

    HttpHotelCatalog(RestClient.Builder builder,
                     @Value("${booking.hotel-service.url}") String baseUrl,
                     @Value("${booking.hotel-service.connect-timeout:2s}") Duration connectTimeout,
                     @Value("${booking.hotel-service.read-timeout:3s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public Optional<HotelSnapshot> findActiveHotel(UUID hotelId) {
        try {
            HotelDetail detail = client.get().uri("/api/v1/hotels/{id}", hotelId).retrieve().body(HotelDetail.class);
            if (detail == null) {
                throw new CatalogUnavailableException("Empty hotel response", null);
            }
            return Optional.of(detail.toSnapshot());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw unavailable(hotelId, e);
        } catch (RestClientException e) {
            throw unavailable(hotelId, e);
        }
    }

    private static CatalogUnavailableException unavailable(UUID hotelId, Exception e) {
        log.warn("hotel-service unavailable for hotel {}: {}", hotelId, e.getClass().getSimpleName());
        return new CatalogUnavailableException("hotel-service unavailable", e);
    }

    /** Subset of hotel-service's public HotelDetail (docs/API_CONTRACT.md M2); unknown fields are ignored. */
    record HotelDetail(UUID id, String name, String timeZone, LocalTime checkInTime, BigDecimal taxRatePercent,
                       BigDecimal serviceFeePercent, List<RoomType> roomTypes) {

        HotelSnapshot toSnapshot() {
            return new HotelSnapshot(id, name, timeZone, checkInTime, taxRatePercent, serviceFeePercent,
                    roomTypes.stream().filter(rt -> "ACTIVE".equals(rt.status()))
                            .map(rt -> new HotelSnapshot.RoomTypeSnapshot(rt.id(), rt.name(), rt.capacity(),
                                    rt.basePrice(), rt.currency(), rt.totalUnits()))
                            .toList());
        }
    }

    record RoomType(UUID id, String name, int capacity, BigDecimal basePrice, String currency, int totalUnits,
                    String status) {
    }
}
