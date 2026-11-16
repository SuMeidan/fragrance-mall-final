package io.scent.account;

import io.scent.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    private final AuditService audit;
    public AuthController(AuthService auth, AuditService audit) { this.auth = auth; this.audit = audit; }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<AuthMapper.View> register(@Valid @RequestBody Register body, HttpServletRequest request) {
        return ApiResult.created(auth.register(body.phone(), body.password(), body.displayName(), request.getRemoteAddr()));
    }

    @PostMapping("/auth/login")
    ApiResult<AuthService.Login> login(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        return ApiResult.ok(auth.login(body.loginName(), body.password(), "BUYER", request.getRemoteAddr()));
    }

    @PostMapping("/auth/admin-login")
    ApiResult<AuthService.Login> admin(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        return ApiResult.ok(auth.login(body.loginName(), body.password(), "ADMIN", request.getRemoteAddr()));
    }

    @GetMapping("/auth/me")
    ApiResult<Actor> me(@AuthenticationPrincipal Actor actor) { return ApiResult.ok(actor); }

    @PostMapping("/auth/logout")
    ApiResult<Void> logout(@RequestHeader("Authorization") String header) {
        auth.logout(header.substring(7)); return ApiResult.ok(null);
    }

    @GetMapping("/admin/accounts")
    ApiResult<Page<AuthMapper.View>> accounts(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int pageSize) {
        return ApiResult.ok(auth.list(keyword, page, pageSize));
    }

    @PatchMapping("/admin/accounts/{id}")
    ApiResult<Void> status(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Status body) {
        auth.setEnabled(actor.id(), id, body.enabled()); return ApiResult.ok(null);
    }

    @GetMapping("/admin/audits")
    ApiResult<Page<AuditMapper.Entry>> audits(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "12") int pageSize) {
        return ApiResult.ok(audit.list(page, pageSize));
    }

    public record Register(@Pattern(regexp = "1[3-9][0-9]{9}") @NotNull String phone,
                           @NotBlank @Size(min = 8, max = 64) String password,
                           @NotBlank @Size(max = 32) String displayName) { }
    public record Credentials(@NotBlank @Size(max = 32) String loginName,
                              @NotBlank @Size(min = 8, max = 64) String password) { }
    public record Status(@NotNull Boolean enabled) { }
}
