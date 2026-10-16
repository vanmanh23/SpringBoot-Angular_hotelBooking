package com.stayhotel.auth.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 100)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "lockout_until")
    private Instant lockoutUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected User() {
    }

    public static User register(String email, String passwordHash, String fullName, String phone, Set<Role> roles,
                                Instant now) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = normalizeEmail(email);
        user.passwordHash = passwordHash;
        user.fullName = fullName.trim();
        user.phone = blankToNull(phone);
        user.status = UserStatus.ACTIVE;
        user.roles = EnumSet.copyOf(roles);
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isTemporarilyLockedOut(Instant now) {
        return lockoutUntil != null && lockoutUntil.isAfter(now);
    }

    /** Counts a failed password attempt; after {@code maxAttempts} the account is locked out for {@code lockout}. */
    public void recordFailedLogin(Instant now, int maxAttempts, Duration lockout) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockoutUntil = now.plus(lockout);
            failedLoginAttempts = 0;
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        if (failedLoginAttempts != 0 || lockoutUntil != null) {
            failedLoginAttempts = 0;
            lockoutUntil = null;
            updatedAt = now;
        }
    }

    public void updateProfile(String fullName, String phone, Instant now) {
        this.fullName = fullName.trim();
        this.phone = blankToNull(phone);
        this.updatedAt = now;
    }

    public void changePasswordHash(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    public void changeStatus(UserStatus status, Instant now) {
        this.status = status;
        if (status == UserStatus.ACTIVE) {
            failedLoginAttempts = 0;
            lockoutUntil = null;
        }
        this.updatedAt = now;
    }

    public void replaceRoles(Set<Role> newRoles, Instant now) {
        roles.clear();
        roles.addAll(newRoles);
        updatedAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
