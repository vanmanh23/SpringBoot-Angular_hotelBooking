package com.stayhotel.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Persistence conflicts become 409 instead of 500. Services should still pre-check and raise specific
 * {@link BusinessException}s; this is the safety net for races. Constraint names/SQL are never returned.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DataAccessExceptionHandler {

    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
    private static final Logger log = LoggerFactory.getLogger(DataAccessExceptionHandler.class);

    /** Optimistic lock conflicts, deadlocks and lock timeouts: the client may simply retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest req) {
        if (!(ex instanceof OptimisticLockingFailureException)) {
            log.warn("Concurrency failure on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, CONCURRENT_MODIFICATION,
                "The resource was modified by someone else. Reload and try again.", req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {} {}: {}", req.getMethod(), req.getRequestURI(),
                ex.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, ErrorCode.CONFLICT,
                "The request conflicts with existing data", req.getRequestURI()));
    }
}
