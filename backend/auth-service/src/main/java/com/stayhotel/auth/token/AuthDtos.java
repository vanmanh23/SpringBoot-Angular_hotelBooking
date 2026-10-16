package com.stayhotel.auth.token;

import com.stayhotel.auth.user.UserResponse;
import com.stayhotel.auth.user.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** No role field on purpose: self-registration always yields CUSTOMER. Unknown JSON fields are ignored. */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = ValidationPatterns.PASSWORD, message = ValidationPatterns.PASSWORD_MESSAGE)
            String password,
            @NotBlank @Size(max = 150) String fullName,
            @Pattern(regexp = ValidationPatterns.PHONE) String phone) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=***]";
        }
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 255) String email,
                               @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }
}
