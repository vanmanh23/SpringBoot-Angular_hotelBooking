package com.stayhotel.common.security;

import com.stayhotel.common.error.ApiError;
import com.stayhotel.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Security exceptions raised inside controllers (e.g. by {@code @PreAuthorize}) would otherwise reach the
 * catch-all 500 handler. Ordered before {@link com.stayhotel.common.error.GlobalExceptionHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(403).body(ApiError.of(403, ErrorCode.FORBIDDEN, "Access denied", req.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return ResponseEntity.status(401)
                .body(ApiError.of(401, ErrorCode.UNAUTHORIZED, "Authentication required", req.getRequestURI()));
    }
}
