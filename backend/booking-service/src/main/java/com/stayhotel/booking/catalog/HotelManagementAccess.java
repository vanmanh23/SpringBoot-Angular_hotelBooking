package com.stayhotel.booking.catalog;

import com.stayhotel.booking.BookingErrors;
import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.UUID;

/**
 * "May this caller manage this hotel?" answered by hotel-service, which owns manager assignments (D-030). The
 * caller's own token is forwarded, so the same rule (ADMIN, or HOTEL_MANAGER with an assignment) applies everywhere.
 */
@Component
public class HotelManagementAccess {

    private final RestClient client;

    HotelManagementAccess(RestClient.Builder builder, @Value("${booking.hotel-service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void requireManage(UUID hotelId, String bearerToken) {
        try {
            client.get().uri("/api/v1/manage/hotels/{id}", hotelId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "HOTEL_NOT_FOUND", "Hotel not found");
            }
            if (e.getStatusCode() == HttpStatus.FORBIDDEN || e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
            }
            throw BookingErrors.catalogUnavailable();
        } catch (RestClientException e) {
            throw BookingErrors.catalogUnavailable();
        }
    }
}
