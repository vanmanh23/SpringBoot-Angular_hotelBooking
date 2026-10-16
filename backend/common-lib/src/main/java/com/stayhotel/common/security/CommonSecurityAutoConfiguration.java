package com.stayhotel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

/** Uniform 401/403 handling for every servlet service that uses Spring Security. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SecurityFilterChain.class)
@Import(SecurityExceptionHandler.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiErrorAuthenticationEntryPoint apiErrorAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ApiErrorAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiErrorAccessDeniedHandler apiErrorAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ApiErrorAccessDeniedHandler(objectMapper);
    }
}
