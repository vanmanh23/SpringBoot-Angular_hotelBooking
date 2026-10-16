package com.stayhotel.auth.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(UUID id, String email, String fullName, String phone, UserStatus status,
                           List<Role> roles, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(), user.getStatus(),
                user.getRoles().stream().sorted().toList(), user.getCreatedAt());
    }
}
