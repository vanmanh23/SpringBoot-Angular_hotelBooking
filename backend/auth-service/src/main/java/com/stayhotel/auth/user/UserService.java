package com.stayhotel.auth.user;

import com.stayhotel.auth.AuthErrors;
import com.stayhotel.auth.token.RefreshTokenService;
import com.stayhotel.auth.token.RevokeReason;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Self-service operations. The user id always comes from the validated token, never from the request. */
@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    UserService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID userId) {
        return UserResponse.from(load(userId));
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UserDtos.UpdateProfileRequest request) {
        User user = load(userId);
        user.updateProfile(request.fullName(), request.phone(), clock.instant());
        return UserResponse.from(user);
    }

    /** Changing the password ends every other session (all refresh tokens revoked). */
    @Transactional
    public void changePassword(UUID userId, UserDtos.ChangePasswordRequest request) {
        User user = load(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw AuthErrors.currentPasswordIncorrect();
        }
        Instant now = clock.instant();
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()), now);
        refreshTokens.revokeAllForUser(userId, now, RevokeReason.PASSWORD_CHANGED);
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(AuthErrors::userNotFound);
    }
}
