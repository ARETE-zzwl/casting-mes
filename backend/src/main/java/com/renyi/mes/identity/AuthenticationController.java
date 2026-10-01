package com.renyi.mes.identity;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.LocalSessionCredentials;
import com.renyi.mes.common.TrustedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class AuthenticationConfigurationController {
    private final Environment environment;
    AuthenticationConfigurationController(Environment environment) { this.environment = environment; }
    @GetMapping("/api/auth/config")
    java.util.Map<String, Boolean> config() {
        return java.util.Map.of("authenticationRequired", environment.acceptsProfiles(Profiles.of("prod | secure")));
    }
    @GetMapping("/api/auth/csrf")
    java.util.Map<String, String> csrf(HttpServletRequest request) {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return token == null ? java.util.Map.of() : java.util.Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}

@RestController
@Profile("prod | secure")
class AuthenticationController {
    private final LocalSessionCredentials credentials;
    private final IdentityAccessApplication access;
    private final TrustedIdentity identity;
    private final JdbcTemplate jdbc;
    AuthenticationController(LocalSessionCredentials credentials, IdentityAccessApplication access, TrustedIdentity identity, JdbcTemplate jdbc) {
        this.credentials = credentials; this.access = access; this.identity = identity; this.jdbc = jdbc;
    }
    @PostMapping("/api/auth/login")
    AuthSession login(@Valid @RequestBody Login request, HttpServletRequest servlet) {
        return view(credentials.login(request.employeeCode(), request.password(), servlet).employeeCode());
    }
    @GetMapping("/api/auth/me")
    AuthSession me() { return view(identity.employeeCode()); }
    @PostMapping("/api/auth/logout")
    java.util.Map<String, Boolean> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        return java.util.Map.of("loggedOut", true);
    }
    @PostMapping("/api/auth/password")
    AuthSession password(@Valid @RequestBody Password command, HttpServletRequest request) {
        if (request.getSession(false) == null || !(request.getSession(false).getAttribute(LocalSessionCredentials.SESSION_ACTOR) instanceof LocalSessionCredentials.SessionActor actor)) {
            throw DomainException.forbidden("LOCAL_SESSION_REQUIRED", "请通过账号登录后修改密码");
        }
        credentials.change(actor, command.currentPassword(), command.newPassword(), request);
        return view(actor.employeeCode());
    }
    @PostMapping("/api/access/users/{employeeCode}/password-reset")
    java.util.Map<String, Boolean> reset(@PathVariable String employeeCode, @Valid @RequestBody Reset request) {
        if (!identity.isAdministrator()) throw DomainException.forbidden("ACCESS_MANAGE_REQUIRED", "仅管理员可初始化或重置账号");
        credentials.reset(employeeCode, request.temporaryPassword(), request.forcePasswordChange());
        return java.util.Map.of("initialized", true);
    }
    @GetMapping("/api/access/users/{employeeCode}/account")
    java.util.Map<String, Object> account(@PathVariable String employeeCode) {
        if (!identity.isAdministrator()) throw DomainException.forbidden("ACCESS_MANAGE_REQUIRED", "仅管理员可查询账号配置");
        return java.util.Map.of("employeeCode", employeeCode, "initialized",
            jdbc.queryForObject("select count(*) from identity_account where employee_code = ?", Integer.class, employeeCode) == 1);
    }
    private AuthSession view(String code) {
        boolean mustChange = jdbc.queryForObject("select count(*) from identity_account where employee_code = ? and must_change_password = true", Integer.class, code) > 0;
        return new AuthSession(access.getUser(code), mustChange);
    }
    record Login(@NotBlank @Size(max=64) String employeeCode, @NotBlank @Size(max=128) String password) { }
    record Password(@NotBlank @Size(max=128) String currentPassword, @NotBlank @Size(max=128) String newPassword) { }
    record Reset(@NotBlank @Size(max=128) String temporaryPassword, boolean forcePasswordChange) { }
    record AuthSession(IdentityAccessApplication.UserView user, boolean mustChangePassword) { }
}
