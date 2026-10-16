package com.stayhotel.auth.token;

import com.stayhotel.auth.AuthErrors;
import com.stayhotel.auth.config.AuthProperties;
import com.stayhotel.common.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque refresh tokens: 256-bit random values, only their SHA-256 hash is persisted.
 * Rotation on every use; presenting an already-rotated token revokes the whole family (theft detection).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuthProperties.Refresh properties;

    RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.properties = properties.refresh();
    }

    /** Starts a new family (a new login session). Returns the raw token for the cookie. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String startSession(UUID userId, Instant now) {
        return create(userId, UUID.randomUUID(), now);
    }

    /**
     * Validates and rotates {@code rawToken}. Must run inside a transaction that does NOT roll back on
     * {@link BusinessException}, so a reuse-triggered family revocation is kept. noRollbackFor is needed here too:
     * otherwise this participating method would mark the shared transaction rollback-only.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
    public Rotation rotate(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            throw AuthErrors.refreshInvalid();
        }
        RefreshToken token = repository.findByHashForUpdate(hash(rawToken)).orElseThrow(AuthErrors::refreshInvalid);
        if (token.isRevoked()) {
            if (token.getRevokeReason() == RevokeReason.ROTATED) {
                if (token.getRevokedAt().plus(properties.reuseGrace()).isAfter(now)) {
                    throw AuthErrors.refreshStale();
                }
                int revoked = repository.revokeFamily(token.getFamilyId(), now, RevokeReason.REUSE_DETECTED);
                log.warn("Refresh token reuse detected for user {}; revoked {} active token(s) in family",
                        token.getUserId(), revoked);
                throw AuthErrors.refreshReused();
            }
            throw AuthErrors.refreshInvalid();
        }
        if (token.isExpired(now)) {
            throw AuthErrors.refreshInvalid();
        }
        token.revoke(now, RevokeReason.ROTATED);
        return new Rotation(token.getUserId(), token.getFamilyId());
    }

    /** Issues the successor token of a rotation, in the same family. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String continueSession(Rotation rotation, Instant now) {
        return create(rotation.userId(), rotation.familyId(), now);
    }

    /** Idempotent: unknown or already revoked tokens are ignored. */
    @Transactional
    public void revoke(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByHashForUpdate(hash(rawToken))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), now, RevokeReason.LOGOUT));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllForUser(UUID userId, Instant now, RevokeReason reason) {
        repository.revokeAllForUser(userId, now, reason);
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }

    private String create(UUID userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, familyId, hash(raw), now, now.plus(properties.ttl())));
        return raw;
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Rotation(UUID userId, UUID familyId) {
    }
}
