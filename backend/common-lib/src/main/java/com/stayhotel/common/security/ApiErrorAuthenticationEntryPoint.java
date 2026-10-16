package com.stayhotel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** 401 with the uniform error body. Token validation details are not echoed back to the client. */
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter writer;

    public ApiErrorAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.writer = new ApiErrorWriter(objectMapper);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(request, response, 401, ErrorCode.UNAUTHORIZED, "Authentication required");
    }
}
