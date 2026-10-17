package com.stayhotel.hotel.support;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Region-based IANA zone such as "Asia/Ho_Chi_Minh" (fixed offsets like "+07:00" are rejected). Null is valid. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IanaTimeZone.Validator.class)
public @interface IanaTimeZone {

    String message() default "must be an IANA time zone such as Asia/Ho_Chi_Minh";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IanaTimeZone, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                return !(ZoneId.of(value) instanceof ZoneOffset) && value.contains("/");
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
