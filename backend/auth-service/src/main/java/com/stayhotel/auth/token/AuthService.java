package com.stayhotel.auth.token;

import com.stayhotel.auth.AuthErrors;
import com.stayhotel.auth.config.AuthProperties;
import com.stayhotel.auth.user.Role;
import com.stayhotel.auth.user.User;
import com.stayhotel.auth.user.UserRepository;
import com.stayhotel.auth.user.UserResponse;
import com.stayhotel.common.error.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;
    private final AuthProperties.Login loginPolicy;
    private final Clock clock;
    /** Compared against when the email is unknown, so timing does not reveal which accounts exist. */
    private final String dummyHash;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens,
                RefreshTokenService refreshTokens, AuthProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.loginPolicy = properties.login();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-1");
    }

    @Transactional
    public UserResponse register(AuthDtos.RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw AuthErrors.emailTaken();
        }
        User user = User.register(email, passwordEncoder.encode(request.password()), request.fullName(),
                request.phone(), Set.of(Role.CUSTOMER), clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw AuthErrors.emailTaken(); // concurrent registration of the same email
        }
        return UserResponse.from(user);
    }

    /** Failed-attempt counters must be committed even though the call ends with a 401. */
    @Transactional(noRollbackFor = BusinessException.class)
    public Session login(AuthDtos.LoginRequest request) {
        Instant now = clock.instant();
        User user = users.findByEmailForUpdate(User.normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw AuthErrors.invalidCredentials();
        }
        if (user.isTemporarilyLockedOut(now)) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw AuthErrors.invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin(now, loginPolicy.maxFailedAttempts(), loginPolicy.lockoutDuration());
            throw AuthErrors.invalidCredentials();
        }
        if (!user.isActive()) {
            throw AuthErrors.accountLocked();
        }
        user.recordSuccessfulLogin(now);
        return new Session(accessTokens.issue(user, now), refreshTokens.startSession(user.getId(), now), user);
    }

    /** Reuse detection revokes a whole token family; that write must survive the 401 that follows. */
    @Transactional(noRollbackFor = BusinessException.class)
    public Session refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, now);
        User user = users.findById(rotation.userId()).orElseThrow(AuthErrors::refreshInvalid);
        if (!user.isActive()) {
            refreshTokens.revokeAllForUser(user.getId(), now, RevokeReason.ACCOUNT_DISABLED);
            throw AuthErrors.accountLocked();
        }
        return new Session(accessTokens.issue(user, now), refreshTokens.continueSession(rotation, now), user);
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken, clock.instant());
    }

    public long accessTokenTtlSeconds() {
        return accessTokens.ttlSeconds();
    }

    public record Session(String accessToken, String refreshToken, User user) {
    }
}
