package com.stayhotel.auth.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 150) String fullName,
                                       @Pattern(regexp = ValidationPatterns.PHONE) String phone) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Pattern(regexp = ValidationPatterns.PASSWORD, message = ValidationPatterns.PASSWORD_MESSAGE)
            String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }
}
