package com.stayhotel.auth.admin;

import com.stayhotel.auth.AuthErrors;
import com.stayhotel.auth.token.RefreshTokenService;
import com.stayhotel.auth.token.RevokeReason;
import com.stayhotel.auth.user.Role;
import com.stayhotel.auth.user.User;
import com.stayhotel.auth.user.UserRepository;
import com.stayhotel.auth.user.UserResponse;
import com.stayhotel.auth.user.UserStatus;
import com.stayhotel.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Admin-only user management (FR-ADMIN-01). Every change is audited (BR-32). */
@Service
public class AdminUserService {

    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    AdminUserService(UserRepository users, AuditLogRepository auditLogs, RefreshTokenService refreshTokens, Clock clock) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String query, UserStatus status, Pageable pageable) {
        Specification<User> spec = (root, cq, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), status));
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, cq, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like, '\\'),
                    cb.like(cb.lower(root.get("fullName")), like, '\\')));
        }
        return PageResponse.from(users.findAll(spec, pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return users.findById(id).map(UserResponse::from).orElseThrow(AuthErrors::userNotFound);
    }

    @Transactional
    public UserResponse changeStatus(UUID actorId, UUID targetId, UserStatus status, String reason) {
        User user = loadOther(actorId, targetId);
        Instant now = clock.instant();
        UserStatus previous = user.getStatus();
        user.changeStatus(status, now);
        if (status != UserStatus.ACTIVE) {
            refreshTokens.revokeAllForUser(targetId, now, RevokeReason.ACCOUNT_DISABLED);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", previous.name());
        details.put("to", status.name());
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason.trim());
        }
        auditLogs.save(new AuditLog(actorId, "USER_STATUS_CHANGED", "USER", targetId.toString(), details, now));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse changeRoles(UUID actorId, UUID targetId, Set<Role> roles) {
        User user = loadOther(actorId, targetId);
        Instant now = clock.instant();
        Set<Role> previous = user.getRoles();
        user.replaceRoles(EnumSet.copyOf(roles), now);
        auditLogs.save(new AuditLog(actorId, "USER_ROLES_CHANGED", "USER", targetId.toString(),
                Map.of("from", sorted(previous), "to", sorted(roles)), now));
        return UserResponse.from(user);
    }

    /** Self-modification is refused, which also guarantees the last admin can never be demoted or locked. */
    private User loadOther(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            throw AuthErrors.cannotModifySelf();
        }
        return users.findById(targetId).orElseThrow(AuthErrors::userNotFound);
    }

    private static Object sorted(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().toList();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
