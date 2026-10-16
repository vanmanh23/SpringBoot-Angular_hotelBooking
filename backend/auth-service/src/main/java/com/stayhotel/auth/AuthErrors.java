package com.stayhotel.auth;

import com.stayhotel.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** Error codes of auth-service (documented in API_CONTRACT.md, M1). */
public final class AuthErrors {

    private AuthErrors() {
    }

    public static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    public static BusinessException accountLocked() {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "This account is locked or disabled");
    }

    public static BusinessException refreshInvalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "Session expired, please sign in again");
    }

    public static BusinessException refreshReused() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "Session revoked, please sign in again");
    }

    public static BusinessException refreshStale() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_STALE",
                "Session was just refreshed elsewhere, retry");
    }

    public static BusinessException emailTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
    }

    public static BusinessException currentPasswordIncorrect() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_PASSWORD_INCORRECT",
                "Current password is incorrect");
    }

    public static BusinessException userNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }

    public static BusinessException cannotModifySelf() {
        return new BusinessException(HttpStatus.CONFLICT, "CANNOT_MODIFY_SELF",
                "Administrators cannot change their own status or roles");
    }
}
