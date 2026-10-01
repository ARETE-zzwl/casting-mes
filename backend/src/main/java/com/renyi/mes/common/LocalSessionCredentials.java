package com.renyi.mes.common;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
@Profile("prod | secure")
public class LocalSessionCredentials {
    public static final String SESSION_ACTOR = "mes.auth.actor";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("unused-constant-time-comparison");
    private final String bootstrapPassword;

    public LocalSessionCredentials(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${mes.auth.bootstrap-admin-password:}") String bootstrapPassword) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bootstrapPassword = bootstrapPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (bootstrapPassword.isBlank()) return;
        validatePassword(bootstrapPassword);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForList("select employee_code from organization_member where employee_code = 'A001' for update");
            if (jdbc.queryForObject("select count(*) from identity_account where employee_code = 'A001'", Integer.class) == 0) {
                jdbc.update("""
                    insert into identity_account(employee_code, password_hash, must_change_password, created_at, updated_at)
                    values ('A001', ?, false, current_timestamp, current_timestamp)
                    """, passwords.encode(bootstrapPassword));
            }
        });
    }

    public SessionActor login(String employee, String password, HttpServletRequest request) {
        if (employee == null || employee.length() > 64 || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
        String code = employee.trim().toUpperCase(Locale.ROOT);
        // Failure counters must commit before the authentication error is raised.
        SessionActor actor = transactions.execute(status -> {
            var rows = jdbc.query("""
                select a.*, m.active from identity_account a join organization_member m on m.employee_code = a.employee_code
                where a.employee_code = ? for update
                """, (rs, row) -> new Account(rs.getString("password_hash"), rs.getInt("failed_attempts"),
                    rs.getTimestamp("locked_until"), rs.getBoolean("active"), rs.getLong("credential_version")), code);
            if (rows.isEmpty()) { passwords.matches(password, dummyHash); return null; }
            Account account = rows.getFirst();
            Instant now = Instant.now();
            if (!account.active() || account.lockedUntil() != null && account.lockedUntil().toInstant().isAfter(now)) return null;
            if (!passwords.matches(password, account.hash())) {
                int failures = account.lockedUntil() == null ? account.failures() + 1 : 1;
                jdbc.update("update identity_account set failed_attempts = ?, locked_until = ?, updated_at = ? where employee_code = ?",
                    failures, failures >= 5 ? Timestamp.from(now.plusSeconds(900)) : null, Timestamp.from(now), code);
                return null;
            }
            jdbc.update("update identity_account set failed_attempts = 0, locked_until = null, last_login_at = ?, updated_at = ? where employee_code = ?",
                Timestamp.from(now), Timestamp.from(now), code);
            return new SessionActor(code, account.version());
        });
        if (actor == null) throw invalid();
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_ACTOR, actor);
        return actor;
    }

    public boolean valid(SessionActor actor) {
        return jdbc.queryForObject("""
            select count(*) from identity_account a join organization_member m on m.employee_code = a.employee_code
            where a.employee_code = ? and a.credential_version = ? and m.active = true
            """, Integer.class, actor.employeeCode(), actor.version()) == 1;
    }

    public void reset(String employee, String password, boolean mustChange) {
        validatePassword(password);
        transactions.executeWithoutResult(status -> {
            var member = jdbc.queryForList("select employee_code from organization_member where employee_code = ? for update", String.class, employee);
            if (member.isEmpty()) throw DomainException.notFound("ACCESS_USER_NOT_FOUND", "用户不存在");
            int count = jdbc.update("""
                update identity_account set password_hash = ?, must_change_password = ?, credential_version = credential_version + 1,
                    failed_attempts = 0, locked_until = null, updated_at = current_timestamp where employee_code = ?
                """, passwords.encode(password), mustChange, employee);
            if (count == 0) jdbc.update("""
                insert into identity_account(employee_code, password_hash, must_change_password, created_at, updated_at)
                values (?, ?, ?, current_timestamp, current_timestamp)
                """, employee, passwords.encode(password), mustChange);
        });
    }

    public void change(SessionActor actor, String current, String next, HttpServletRequest request) {
        validatePassword(next);
        transactions.executeWithoutResult(status -> {
            String hash = jdbc.queryForObject("select password_hash from identity_account where employee_code = ? for update", String.class, actor.employeeCode());
            if (!valid(actor) || current == null || !passwords.matches(current, hash)) throw invalid();
            jdbc.update("""
                update identity_account set password_hash = ?, must_change_password = false, credential_version = credential_version + 1,
                updated_at = current_timestamp where employee_code = ?
                """, passwords.encode(next), actor.employeeCode());
        });
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_ACTOR, new SessionActor(actor.employeeCode(), actor.version() + 1));
    }

    public boolean mustChange(String employee) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select must_change_password from identity_account where employee_code = ?", Boolean.class, employee));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw DomainException.badRequest("PASSWORD_LENGTH", "密码需至少 12 个字符，UTF-8 长度不超过 72 字节");
        }
    }

    private static DomainException invalid() {
        return DomainException.unauthorized("LOGIN_INVALID", "工号或密码不正确，或账号暂时不可用");
    }

    private record Account(String hash, int failures, Timestamp lockedUntil, boolean active, long version) { }
    public record SessionActor(String employeeCode, long version) implements Serializable { }
}
