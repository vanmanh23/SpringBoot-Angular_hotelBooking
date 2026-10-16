package com.stayhotel.auth.user;

/** Shared input rules (see API_CONTRACT.md M1 "Validation"). */
public final class ValidationPatterns {

    /** 8–72 chars (BCrypt input limit), at least one letter and one digit. */
    public static final String PASSWORD = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE =
            "must be 8-72 characters and contain at least one letter and one digit";
    public static final String PHONE = "^[0-9+() .-]{0,30}$";

    private ValidationPatterns() {
    }
}
