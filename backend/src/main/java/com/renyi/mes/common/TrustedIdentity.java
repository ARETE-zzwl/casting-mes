package com.renyi.mes.common;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class TrustedIdentity {
    private final JdbcTemplate jdbc;

    public TrustedIdentity(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String employeeCode() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LocalSessionCredentials.SessionActor actor) {
            if (jdbc.queryForObject("select count(*) from organization_member where employee_code = ? and active = true", Integer.class, actor.employeeCode()) == 1) return actor.employeeCode();
            throw denied();
        }
        if (!(authentication instanceof JwtAuthenticationToken token) || token.getToken().getIssuer() == null) {
            throw denied();
        }
        return jdbc.query("""
            select m.employee_code from trusted_identity_binding b
            join organization_member m on m.employee_code = b.employee_code
            where b.issuer = ? and b.subject = ? and m.active = true
            """, (rs, row) -> rs.getString(1), token.getToken().getIssuer().toString(), token.getToken().getSubject())
            .stream().findFirst().orElseThrow(TrustedIdentity::denied);
    }

    public void requireActor(String claimed) {
        if (claimed == null || !employeeCode().equals(claimed.trim().toUpperCase(Locale.ROOT))) throw denied();
    }

    public boolean isAdministrator() {
        String employee = employeeCode();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return (authentication.getPrincipal() instanceof LocalSessionCredentials.SessionActor
                || authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_mes.admin")))
            && jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code = 'SYSTEM_ADMIN'",
                Integer.class, employee) > 0;
    }

    static DomainException denied() {
        return DomainException.forbidden("TRUSTED_IDENTITY_REQUIRED", "登录身份未绑定有效员工，或请求身份与登录员工不一致");
    }
}
