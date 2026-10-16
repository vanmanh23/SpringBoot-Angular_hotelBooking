package com.stayhotel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/** Writes an {@link ApiError} directly to the servlet response (used by security filters, outside MVC). */
final class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(status, code, message, request.getRequestURI()));
    }
}
