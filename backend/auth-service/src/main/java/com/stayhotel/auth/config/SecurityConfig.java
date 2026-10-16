package com.stayhotel.auth.config;

import com.stayhotel.common.security.ApiErrorAccessDeniedHandler;
import com.stayhotel.common.security.ApiErrorAuthenticationEntryPoint;
import com.stayhotel.common.security.StatelessResourceServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    /*
     * CSRF note: the refresh/logout endpoints read the SH_REFRESH cookie. The cookie is SameSite=Strict and scoped
     * to /api/v1/auth, so cross-site requests never carry it; the SPA calls these endpoints same-origin.
     */
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrorAuthenticationEntryPoint entryPoint,
                                    ApiErrorAccessDeniedHandler deniedHandler) throws Exception {
        StatelessResourceServer.apply(http, entryPoint, deniedHandler)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(StatelessResourceServer.PUBLIC_ACTUATOR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/oauth2/jwks").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
