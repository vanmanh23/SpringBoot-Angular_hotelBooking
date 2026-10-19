package com.stayhotel.review.config;

import com.stayhotel.common.security.ApiErrorAccessDeniedHandler;
import com.stayhotel.common.security.ApiErrorAuthenticationEntryPoint;
import com.stayhotel.common.security.StatelessResourceServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

/** Deny-by-default; only the public list of a hotel's reviews is open. */
@Configuration
@EnableMethodSecurity
@EnableScheduling
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrorAuthenticationEntryPoint entryPoint,
                                    ApiErrorAccessDeniedHandler deniedHandler) throws Exception {
        StatelessResourceServer.apply(http, entryPoint, deniedHandler)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(StatelessResourceServer.PUBLIC_ACTUATOR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/hotels/*/reviews").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
