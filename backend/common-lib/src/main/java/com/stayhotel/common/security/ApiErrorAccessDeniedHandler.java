package com.stayhotel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 403 with the uniform error body. */
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter writer;

    public ApiErrorAccessDeniedHandler(ObjectMapper objectMapper) {
        this.writer = new ApiErrorWriter(objectMapper);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(request, response, 403, ErrorCode.FORBIDDEN, "Access denied");
    }
}
