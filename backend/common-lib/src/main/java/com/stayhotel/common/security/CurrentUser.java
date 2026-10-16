package com.stayhotel.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Identity of the caller, taken from a validated access token. Services use {@link #id()} for
 * ownership checks; never trust a user id supplied in a request body or query string.
 */
public record CurrentUser(UUID id, String email, Set<String> roles) {

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_HOTEL_MANAGER = "HOTEL_MANAGER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"),
                roles == null ? Set.of() : Set.copyOf(roles));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }
}
