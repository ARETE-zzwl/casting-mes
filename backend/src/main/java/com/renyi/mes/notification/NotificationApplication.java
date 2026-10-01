package com.renyi.mes.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class NotificationApplication {

	private static final List<String> CATEGORIES =
		List.of("TASK", "QUALITY", "INVENTORY", "WORKFLOW", "OUTSOURCING", "SYSTEM");
	private final JdbcTemplate jdbc;

	public NotificationApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public NotificationView create(CreateCommand command) {
		NotificationView notification = createInternal(command);
		if (needsManagementAttention(notification.category(), notification.title())) {
			notifyManagement(notification);
		}
		return notification;
	}

	@Transactional
	public NotificationView createDirect(CreateCommand command) {
		return createInternal(command);
	}

	private NotificationView createInternal(CreateCommand command) {
		String category = normalize(command.category());
		if (!CATEGORIES.contains(category)) {
			throw DomainException.badRequest("NOTIFICATION_CATEGORY_INVALID", "通知类型不受支持");
		}
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into notification_item (
				id, notification_no, recipient_code, category, title, content_text,
				business_link, status, created_at
			) values (?, ?, ?, ?, ?, ?, ?, 'UNREAD', ?)
			""", id, identifier(), normalize(command.recipientCode()), category, command.title().trim(),
			command.content().trim(), blankToNull(command.businessLink()), Timestamp.from(Instant.now()));
		return require(id, false);
	}

	private void notifyManagement(NotificationView notification) {
		List<String> recipients = jdbc.query("""
			select distinct member.employee_code
			from organization_member member
			join organization_member_role role on role.employee_code = member.employee_code
			where member.active = true and role.role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN')
			""", (rs, row) -> rs.getString("employee_code"));
		for (String recipient : recipients) {
			if (normalize(recipient).equals(notification.recipientCode())) continue;
			createInternal(new CreateCommand(recipient, notification.category(),
				"全厂异常 · " + notification.title(),
				"原责任对象：" + notification.recipientCode() + "。" + notification.content(),
				notification.businessLink()));
		}
	}

	private static boolean needsManagementAttention(String category, String title) {
		if ("QUALITY".equals(category)) return true;
		String normalizedTitle = title == null ? "" : title;
		return normalizedTitle.contains("异常") || normalizedTitle.contains("预警")
			|| normalizedTitle.contains("缺口") || normalizedTitle.contains("阻塞");
	}

	@Transactional
	public NotificationView markRead(UUID id, String recipientCode) {
		NotificationView notification = require(id, true);
		if (!notification.recipientCode().equals(normalize(recipientCode))) {
			throw DomainException.conflict("NOTIFICATION_RECIPIENT_MISMATCH", "只能处理自己的通知");
		}
		if (notification.status().equals("READ")) {
			return notification;
		}
		jdbc.update("""
			update notification_item set status = 'READ', read_at = ? where id = ?
			""", Timestamp.from(Instant.now()), id);
		return require(id, false);
	}

	@Transactional(readOnly = true)
	public List<NotificationView> list(String recipientCode, boolean unreadOnly) {
		if (unreadOnly) {
			return jdbc.query("""
				select * from notification_item
				where recipient_code = ? and status = 'UNREAD'
				order by created_at desc
				""", NotificationApplication::map, normalize(recipientCode));
		}
		return jdbc.query("""
			select * from notification_item
			where recipient_code = ?
			order by created_at desc
			""", NotificationApplication::map, normalize(recipientCode));
	}

	private NotificationView require(UUID id, boolean lock) {
		String sql = "select * from notification_item where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, NotificationApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("NOTIFICATION_NOT_FOUND", "通知不存在"));
	}

	private static NotificationView map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp readAt = rs.getTimestamp("read_at");
		return new NotificationView(rs.getObject("id", UUID.class), rs.getString("notification_no"),
			rs.getString("recipient_code"), rs.getString("category"), rs.getString("title"),
			rs.getString("content_text"), rs.getString("business_link"), rs.getString("status"),
			rs.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String identifier() {
		return "NTF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record CreateCommand(String recipientCode, String category, String title,
			String content, String businessLink) {
	}

	public record NotificationView(UUID id, String notificationNo, String recipientCode,
			String category, String title, String content, String businessLink, String status,
			Instant createdAt, Instant readAt) {
	}
}

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

	private final NotificationApplication notifications;

	NotificationController(NotificationApplication notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	List<NotificationApplication.NotificationView> list(
			@RequestParam String recipientCode,
			@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return notifications.list(recipientCode, unreadOnly);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	NotificationApplication.NotificationView create(@Valid @RequestBody CreateRequest request) {
		return notifications.create(new NotificationApplication.CreateCommand(
			request.recipientCode(), request.category(), request.title(),
			request.content(), request.businessLink()));
	}

	@PostMapping("/{id}/read")
	NotificationApplication.NotificationView markRead(@PathVariable UUID id,
			@Valid @RequestBody ReadRequest request) {
		return notifications.markRead(id, request.recipientCode());
	}

	record CreateRequest(@NotBlank @Size(max = 64) String recipientCode,
			@NotBlank String category, @NotBlank @Size(max = 200) String title,
			@NotBlank @Size(max = 1000) String content, @Size(max = 300) String businessLink) {
	}

	record ReadRequest(@NotBlank @Size(max = 64) String recipientCode) {
	}
}
