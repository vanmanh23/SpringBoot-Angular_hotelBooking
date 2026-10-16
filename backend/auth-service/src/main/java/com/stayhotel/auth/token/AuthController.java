package com.stayhotel.auth.token;

import com.stayhotel.auth.user.UserResponse;
import com.stayhotel.common.error.ApiError;
import com.stayhotel.common.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;
    private final RefreshCookies cookies;

    AuthController(AuthService authService, RefreshCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return sessionResponse(authService.login(request));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = "${auth.refresh.cookie-name}", required = false) String refreshToken,
                              HttpServletRequest request) {
        try {
            return sessionResponse(authService.refresh(refreshToken));
        } catch (BusinessException e) {
            if ("REFRESH_TOKEN_STALE".equals(e.getCode())) {
                throw e; // keep the cookie: the browser already holds the newer token
            }
            ApiError body = ApiError.of(e.getStatus().value(), e.getCode(), e.getMessage(), request.getRequestURI());
            return ResponseEntity.status(e.getStatus())
                    .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                    .body(body);
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = "${auth.refresh.cookie-name}", required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    private ResponseEntity<AuthDtos.AuthResponse> sessionResponse(AuthService.Session session) {
        AuthDtos.AuthResponse body = new AuthDtos.AuthResponse(session.accessToken(), "Bearer",
                authService.accessTokenTtlSeconds(), UserResponse.from(session.user()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()).toString())
                .body(body);
    }
}
