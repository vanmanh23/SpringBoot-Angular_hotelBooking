package com.stayhotel.hotel.config;

import com.stayhotel.common.security.ApiErrorAccessDeniedHandler;
import com.stayhotel.common.security.ApiErrorAuthenticationEntryPoint;
import com.stayhotel.common.security.StatelessResourceServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

/**
 * Deny-by-default. Public: catalogue reads only (search, hotel detail, room types, amenities) — BR-28.
 * Resource ownership for writes is checked in the services (HotelAccessPolicy).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrorAuthenticationEntryPoint entryPoint,
                                    ApiErrorAccessDeniedHandler deniedHandler) throws Exception {
        StatelessResourceServer.apply(http, entryPoint, deniedHandler)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(StatelessResourceServer.PUBLIC_ACTUATOR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/hotels", "/api/v1/hotels/*",
                                "/api/v1/hotels/*/room-types", "/api/v1/amenities").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
