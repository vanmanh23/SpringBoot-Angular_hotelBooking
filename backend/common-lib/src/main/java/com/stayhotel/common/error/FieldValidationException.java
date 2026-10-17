package com.stayhotel.common.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/** 400 VALIDATION_FAILED for rules Bean Validation cannot express (cross-field, parsed lists, ...). */
public class FieldValidationException extends BusinessException {

    private final List<ApiError.FieldViolation> violations;

    public FieldValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        this.violations = List.of(new ApiError.FieldViolation(field, message));
    }

    public List<ApiError.FieldViolation> getViolations() {
        return violations;
    }
}
