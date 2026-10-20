package com.stayhotel.hotel.user;

import com.stayhotel.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Calls auth-service {@code GET /api/v1/admin/users/{id}} forwarding the admin's token (no service credentials). */
@Component
class HttpUserDirectory implements UserDirectory {

    private final RestClient client;

    HttpUserDirectory(RestClient.Builder builder, @Value("${hotel.auth-service.url:http://localhost:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public void requireActiveHotelManager(UUID userId, String callerBearerToken) {
        UserView user;
        try {
            user = client.get().uri("/api/v1/admin/users/{id}", userId)
                    .headers(h -> h.setBearerAuth(callerBearerToken))
                    .retrieve().body(UserView.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        } catch (RestClientException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_SERVICE_UNAVAILABLE",
                    "User directory is temporarily unavailable");
        }
        if (user == null || !"ACTIVE".equals(user.status()) || user.roles() == null || !user.roles().contains("HOTEL_MANAGER")) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_NOT_HOTEL_MANAGER",
                    "Only active users with the Hotel manager role can be assigned");
        }
    }

    record UserView(UUID id, String status, List<String> roles) {
    }
}
