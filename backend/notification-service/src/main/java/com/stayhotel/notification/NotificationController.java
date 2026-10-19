package com.stayhotel.notification;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** In-app notifications; every query is scoped to the caller's own user id (no IDOR surface). */
@RestController
@Validated
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/me")
    ResponseEntity<PageResponse<NotificationService.NotificationView>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID user = CurrentUser.from(jwt).id();
        return ResponseEntity.ok().header("X-Unread-Count", String.valueOf(notifications.unread(user)))
                .body(notifications.mine(user, page, size));
    }

    @GetMapping("/me/unread-count")
    Map<String, Long> unread(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", notifications.unread(CurrentUser.from(jwt).id()));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notifications.markRead(CurrentUser.from(jwt).id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/read-all")
    ResponseEntity<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(CurrentUser.from(jwt).id());
        return ResponseEntity.noContent().build();
    }
}
