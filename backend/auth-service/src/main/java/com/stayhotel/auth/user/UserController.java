package com.stayhotel.auth.user;

import com.stayhotel.auth.token.RefreshCookies;
import com.stayhotel.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
class UserController {

    private final UserService userService;
    private final RefreshCookies cookies;

    UserController(UserService userService, RefreshCookies cookies) {
        this.userService = userService;
        this.cookies = cookies;
    }

    @GetMapping
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.get(CurrentUser.from(jwt).id());
    }

    @PutMapping
    UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.from(jwt).id(), request);
    }

    /** Also clears this browser's refresh cookie: the client must sign in again with the new password. */
    @PutMapping("/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody UserDtos.ChangePasswordRequest request) {
        userService.changePassword(CurrentUser.from(jwt).id(), request);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }
}
