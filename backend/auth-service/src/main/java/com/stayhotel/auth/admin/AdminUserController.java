package com.stayhotel.auth.admin;

import com.stayhotel.auth.user.Role;
import com.stayhotel.auth.user.UserResponse;
import com.stayhotel.auth.user.UserStatus;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** URL rule (/api/v1/admin/** → ADMIN) plus method-level check: two independent guards. */
@RestController
@Validated
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

    private final AdminUserService service;

    AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<UserResponse> search(@RequestParam(required = false) @Size(max = 100) String q,
                                      @RequestParam(required = false) UserStatus status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(q, status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PatchMapping("/{id}/status")
    UserResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody ChangeStatusRequest request) {
        return service.changeStatus(CurrentUser.from(jwt).id(), id, request.status(), request.reason());
    }

    @PutMapping("/{id}/roles")
    UserResponse changeRoles(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody ChangeRolesRequest request) {
        return service.changeRoles(CurrentUser.from(jwt).id(), id, request.roles());
    }

    record ChangeStatusRequest(@NotNull UserStatus status, @Size(max = 300) String reason) {
    }

    record ChangeRolesRequest(@NotEmpty Set<@NotNull Role> roles) {
    }
}
