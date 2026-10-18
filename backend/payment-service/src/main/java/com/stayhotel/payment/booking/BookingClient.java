package com.stayhotel.payment.booking;

import com.stayhotel.common.logging.CorrelationIdFilter;
import com.stayhotel.payment.PaymentErrors;
import com.stayhotel.payment.config.PaymentProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a booking from booking-service WITH THE CALLER'S OWN TOKEN, so booking-service enforces ownership and the
 * payable amount comes from the booking itself (BR-22). No internal "trust me" endpoint is needed.
 */
@Component
public class BookingClient {

    private final RestClient client;

    BookingClient(RestClient.Builder builder, PaymentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.baseUrl(properties.bookingServiceUrl()).requestFactory(factory).build();
    }

    public Optional<BookingView> findForCaller(UUID bookingId, String bearerToken) {
        try {
            return Optional.ofNullable(client.get().uri("/api/v1/bookings/{id}", bookingId)
                    .headers(h -> {
                        h.setBearerAuth(bearerToken);
                        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (correlationId != null) {
                            h.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .retrieve().body(BookingView.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.FORBIDDEN
                    || e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return Optional.empty();
            }
            throw PaymentErrors.bookingServiceUnavailable();
        } catch (RestClientException e) {
            throw PaymentErrors.bookingServiceUnavailable();
        }
    }

    /** Subset of booking-service's Booking response. */
    public record BookingView(UUID id, String status, BigDecimal totalAmount, String currency, Instant expiresAt) {
    }
}
