package com.renyi.mes.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class WorkflowApplication {

	private final JdbcTemplate jdbc;
	private final com.renyi.mes.common.BusinessAccess access;

	public WorkflowApplication(JdbcTemplate jdbc, com.renyi.mes.common.BusinessAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Transactional
	public RequestView submit(SubmitCommand command) {
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		DefinitionView definition = activeDefinition(normalize(command.workflowType()));
		if (access.secured() && definition == null) throw DomainException.badRequest("WORKFLOW_DEFINITION_REQUIRED", "请选用已发布的审核模板");
		int requiredApprovals = definition == null || !access.secured() && "SYSTEM".equals(definition.createdBy()) ? command.requiredApprovals() : definition.requiredApprovals();
		jdbc.update("""
			insert into workflow_request (
				id, request_no, workflow_type, business_key, title, requester_code,
				status, required_approvals, approval_count, payload, created_at
			) values (?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, ?, ?)
			""",
			id,
			identifier("WF"),
			normalize(command.workflowType()),
			command.businessKey().trim(),
			command.title().trim(),
			normalize(command.requesterCode()),
			requiredApprovals,
			blankToNull(command.payload()),
			Timestamp.from(now)
		);
		return requireRequest(id);
	}

	@Transactional
	public RequestView act(UUID requestId, ActionCommand command) {
		RequestView request = requireRequestForUpdate(requestId);
		if (!request.status().equals("PENDING")) {
			throw DomainException.conflict("WORKFLOW_ALREADY_COMPLETED", "审批流程已经结束");
		}
		String actor = normalize(command.actorCode());
		if (actor.equals(request.requesterCode())) {
			throw DomainException.conflict("WORKFLOW_SELF_APPROVAL", "申请人不能审批自己的申请");
		}
		DefinitionView definition = activeDefinition(request.workflowType());
		if (definition != null && (access.secured() || !"SYSTEM".equals(definition.createdBy())) && !definition.approverRoles().isEmpty() && !hasAnyRole(actor, definition.approverRoles())) {
			throw DomainException.forbidden("WORKFLOW_APPROVER_ROLE_FORBIDDEN", "当前账号不在该审批模板的审批角色范围内");
		}
		if (count("""
			select count(*) from workflow_action where request_id = ? and actor_code = ?
			""", requestId, actor) > 0) {
			throw DomainException.conflict("WORKFLOW_DUPLICATE_ACTOR", "同一审批人不能重复处理");
		}
		String action = normalize(command.action());
		if (!List.of("APPROVE", "REJECT").contains(action)) {
			throw DomainException.badRequest("WORKFLOW_ACTION_INVALID", "审批动作必须是同意或拒绝");
		}

		Instant now = Instant.now();
		jdbc.update("""
			insert into workflow_action (id, request_id, action, actor_code, comment_text, occurred_at)
			values (?, ?, ?, ?, ?, ?)
			""",
			UUID.randomUUID(),
			requestId,
			action,
			actor,
			blankToNull(command.comment()),
			Timestamp.from(now)
		);

		if (action.equals("REJECT")) {
			jdbc.update("""
				update workflow_request set status = 'REJECTED', completed_at = ? where id = ?
				""", Timestamp.from(now), requestId);
		} else {
			int approvals = request.approvalCount() + 1;
			String status = approvals >= request.requiredApprovals() ? "APPROVED" : "PENDING";
			jdbc.update("""
				update workflow_request
				set approval_count = ?, status = ?, completed_at = ?
				where id = ?
				""",
				approvals,
				status,
				status.equals("APPROVED") ? Timestamp.from(now) : null,
				requestId
			);
		}
		return requireRequest(requestId);
	}

	@Transactional(readOnly = true)
	public List<RequestView> listRequests() {
		return jdbc.query("""
			select id, request_no, workflow_type, business_key, title, requester_code,
				status, required_approvals, approval_count, payload, created_at, completed_at
			from workflow_request order by created_at desc
			""", WorkflowApplication::mapRequest).stream().filter(this::visible).toList();
	}

	@Transactional(readOnly = true)
	public List<ActionView> actions(UUID requestId) {
		if (!visible(requireRequest(requestId))) throw DomainException.forbidden("WORKFLOW_SCOPE_DENIED", "无权查看此审批记录");
		return jdbc.query("""
			select id, request_id, action, actor_code, comment_text, occurred_at
			from workflow_action where request_id = ? order by occurred_at
			""", WorkflowApplication::mapAction, requestId);
	}

	@Transactional(readOnly = true)
	public List<DefinitionView> definitions() {
		return jdbc.query("select * from workflow_definition order by workflow_type", WorkflowApplication::mapDefinition);
	}

	@Transactional
	public DefinitionView createDefinition(CreateDefinitionCommand command) {
		String type = normalize(command.workflowType());
		if (activeDefinition(type) != null || count("select count(*) from workflow_definition where workflow_type = ?", type) > 0) {
			throw DomainException.conflict("WORKFLOW_DEFINITION_EXISTS", "审批模板编码已存在");
		}
		Instant now = Instant.now(); UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into workflow_definition (id, workflow_type, name, description, required_approvals, approver_roles, active, created_by, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, true, ?, ?, ?)
			""", id, type, command.name().trim(), blankToNull(command.description()), command.requiredApprovals(), joinRoles(command.approverRoles()), normalize(command.createdBy()), Timestamp.from(now), Timestamp.from(now));
		return requireDefinition(id);
	}

	private RequestView requireRequest(UUID id) {
		List<RequestView> rows = jdbc.query("""
			select id, request_no, workflow_type, business_key, title, requester_code,
				status, required_approvals, approval_count, payload, created_at, completed_at
			from workflow_request where id = ?
			""", WorkflowApplication::mapRequest, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("WORKFLOW_NOT_FOUND", "审批流程不存在");
		}
		return rows.getFirst();
	}

	private RequestView requireRequestForUpdate(UUID id) {
		List<RequestView> rows = jdbc.query("""
			select id, request_no, workflow_type, business_key, title, requester_code,
				status, required_approvals, approval_count, payload, created_at, completed_at
			from workflow_request where id = ? for update
			""", WorkflowApplication::mapRequest, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("WORKFLOW_NOT_FOUND", "审批流程不存在");
		}
		return rows.getFirst();
	}

	private long count(String sql, Object... args) {
		Long result = jdbc.queryForObject(sql, Long.class, args);
		return result == null ? 0 : result;
	}
	private boolean visible(RequestView request) {
		if (!access.secured() || access.role("SYSTEM_ADMIN", "GENERAL_MANAGER") || request.requesterCode().equals(access.actor())) return true;
		DefinitionView definition = activeDefinition(request.workflowType());
		return definition != null && !definition.approverRoles().isEmpty() && hasAnyRole(access.actor(), definition.approverRoles());
	}

	private DefinitionView activeDefinition(String type) {
		return jdbc.query("select * from workflow_definition where workflow_type = ? and active = true", WorkflowApplication::mapDefinition, type).stream().findFirst().orElse(null);
	}
	private DefinitionView requireDefinition(UUID id) { return jdbc.query("select * from workflow_definition where id = ?", WorkflowApplication::mapDefinition, id).stream().findFirst().orElseThrow(() -> DomainException.notFound("WORKFLOW_DEFINITION_NOT_FOUND", "审批模板不存在")); }
	private boolean hasAnyRole(String employeeCode, List<String> roles) { return count("select count(*) from organization_member_role where employee_code = ? and role_code in (" + String.join(",", java.util.Collections.nCopies(roles.size(), "?")) + ")", concat(employeeCode, roles)) > 0; }
	private static Object[] concat(String employeeCode, List<String> roles) { Object[] values = new Object[roles.size() + 1]; values[0] = employeeCode; for (int index = 0; index < roles.size(); index++) values[index + 1] = roles.get(index); return values; }
	private static List<String> roles(String value) { return value == null || value.isBlank() ? List.of() : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).map(item -> item.toUpperCase(Locale.ROOT)).distinct().toList(); }
	private static String joinRoles(List<String> roles) { return String.join(",", roles == null ? List.of() : roles.stream().map(WorkflowApplication::normalize).distinct().toList()); }

	private static RequestView mapRequest(ResultSet rs, int rowNum) throws SQLException {
		Timestamp completedAt = rs.getTimestamp("completed_at");
		return new RequestView(
			rs.getObject("id", UUID.class),
			rs.getString("request_no"),
			rs.getString("workflow_type"),
			rs.getString("business_key"),
			rs.getString("title"),
			rs.getString("requester_code"),
			rs.getString("status"),
			rs.getInt("required_approvals"),
			rs.getInt("approval_count"),
			rs.getString("payload"),
			rs.getTimestamp("created_at").toInstant(),
			completedAt == null ? null : completedAt.toInstant()
		);
	}

	private static ActionView mapAction(ResultSet rs, int rowNum) throws SQLException {
		return new ActionView(
			rs.getObject("id", UUID.class),
			rs.getObject("request_id", UUID.class),
			rs.getString("action"),
			rs.getString("actor_code"),
			rs.getString("comment_text"),
			rs.getTimestamp("occurred_at").toInstant()
		);
	}

	private static DefinitionView mapDefinition(ResultSet rs, int rowNum) throws SQLException {
		return new DefinitionView(rs.getObject("id", UUID.class), rs.getString("workflow_type"), rs.getString("name"), rs.getString("description"), rs.getInt("required_approvals"), roles(rs.getString("approver_roles")), rs.getBoolean("active"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record SubmitCommand(
		String workflowType,
		String businessKey,
		String title,
		String requesterCode,
		int requiredApprovals,
		String payload
	) {
	}

	public record ActionCommand(String action, String actorCode, String comment) {
	}

	public record RequestView(
		UUID id,
		String requestNo,
		String workflowType,
		String businessKey,
		String title,
		String requesterCode,
		String status,
		int requiredApprovals,
		int approvalCount,
		String payload,
		Instant createdAt,
		Instant completedAt
	) {
	}

	public record ActionView(
		UUID id,
		UUID requestId,
		String action,
		String actorCode,
		String comment,
		Instant occurredAt
	) {
	}
	public record DefinitionView(UUID id, String workflowType, String name, String description, int requiredApprovals, List<String> approverRoles, boolean active, String createdBy, Instant createdAt, Instant updatedAt) { }
	public record CreateDefinitionCommand(String workflowType, String name, String description, int requiredApprovals, List<String> approverRoles, String createdBy) { }
}

@RestController
@RequestMapping("/api/workflows")
class WorkflowController {

	private final WorkflowApplication workflows;

	WorkflowController(WorkflowApplication workflows) {
		this.workflows = workflows;
	}

	@GetMapping
	List<WorkflowApplication.RequestView> list() {
		return workflows.listRequests();
	}

	@GetMapping("/definitions")
	List<WorkflowApplication.DefinitionView> definitions() { return workflows.definitions(); }

	@PostMapping("/definitions")
	@ResponseStatus(HttpStatus.CREATED)
	WorkflowApplication.DefinitionView createDefinition(@Valid @RequestBody CreateDefinitionRequest request) {
		return workflows.createDefinition(new WorkflowApplication.CreateDefinitionCommand(request.workflowType(), request.name(), request.description(), request.requiredApprovals(), request.approverRoles(), request.createdBy()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	WorkflowApplication.RequestView submit(@Valid @RequestBody SubmitRequest request) {
		return workflows.submit(new WorkflowApplication.SubmitCommand(
			request.workflowType(),
			request.businessKey(),
			request.title(),
			request.requesterCode(),
			request.requiredApprovals(),
			request.payload()
		));
	}

	@PostMapping("/{requestId}/actions")
	WorkflowApplication.RequestView act(
		@PathVariable UUID requestId,
		@Valid @RequestBody ActionRequest request
	) {
		return workflows.act(requestId, new WorkflowApplication.ActionCommand(
			request.action(),
			request.actorCode(),
			request.comment()
		));
	}

	@GetMapping("/{requestId}/actions")
	List<WorkflowApplication.ActionView> actions(@PathVariable UUID requestId) {
		return workflows.actions(requestId);
	}

	record SubmitRequest(
		@NotBlank @Size(max = 64) String workflowType,
		@NotBlank @Size(max = 128) String businessKey,
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 64) String requesterCode,
		@Min(1) @Max(5) int requiredApprovals,
		@Size(max = 4000) String payload
	) {
	}

	record ActionRequest(
		@NotBlank String action,
		@NotBlank @Size(max = 64) String actorCode,
		@Size(max = 500) String comment
	) {
	}

	record CreateDefinitionRequest(@NotBlank @Size(max = 64) String workflowType, @NotBlank @Size(max = 160) String name, @Size(max = 500) String description, @Min(1) @Max(5) int requiredApprovals, List<@Size(max = 64) String> approverRoles, @NotBlank @Size(max = 64) String createdBy) { }
}
