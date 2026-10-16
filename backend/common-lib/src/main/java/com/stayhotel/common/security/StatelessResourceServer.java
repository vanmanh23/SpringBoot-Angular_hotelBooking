package com.stayhotel.common.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * Baseline for every business service: stateless, bearer-JWT only, uniform 401/403 bodies,
 * health endpoints public. Each service then declares its own authorization rules.
 */
public final class StatelessResourceServer {

    public static final String[] PUBLIC_ACTUATOR = {"/actuator/health", "/actuator/health/**", "/actuator/info"};

    private StatelessResourceServer() {
    }

    public static HttpSecurity apply(HttpSecurity http, ApiErrorAuthenticationEntryPoint entryPoint,
                                     ApiErrorAccessDeniedHandler deniedHandler) throws Exception {
        return http
                // No cookies are used for authentication on these endpoints (Authorization header only), so CSRF is not applicable.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
    }
}
