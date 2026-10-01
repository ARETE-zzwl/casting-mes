package com.renyi.mes.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class IntegrationApplication {

	private static final List<String> DIRECTIONS = List.of("INBOUND", "OUTBOUND");
	private final JdbcTemplate jdbc;

	public IntegrationApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public Submission submit(SubmitCommand command) {
		List<JobView> existing = jdbc.query("select * from integration_job where operation_id = ?",
			IntegrationApplication::map, command.operationId());
		if (!existing.isEmpty()) {
			return new Submission(existing.getFirst(), true);
		}
		String direction = normalize(command.direction());
		if (!DIRECTIONS.contains(direction)) {
			throw DomainException.badRequest("INTEGRATION_DIRECTION_INVALID", "接口方向不受支持");
		}
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		try {
			jdbc.update("""
				insert into integration_job (
					id, operation_id, job_no, interface_code, business_key, direction,
					payload_text, status, attempt_count, created_at, updated_at
				) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
				""", id, command.operationId(), identifier(), normalize(command.interfaceCode()),
				command.businessKey().trim(), direction, command.payload().trim(),
				Timestamp.from(now), Timestamp.from(now));
		}
		catch (DuplicateKeyException exception) {
			JobView duplicate = jdbc.query("select * from integration_job where operation_id = ?",
				IntegrationApplication::map, command.operationId()).getFirst();
			return new Submission(duplicate, true);
		}
		return new Submission(require(id, false), false);
	}

	@Transactional
	public JobView complete(UUID id) {
		JobView job = require(id, true);
		if (!List.of("PENDING", "RETRYING", "PROCESSING").contains(job.status())) {
			throw DomainException.conflict("INTEGRATION_COMPLETE_INVALID", "当前接口任务不能标记成功");
		}
		jdbc.update("""
			update integration_job
			set status = 'SUCCEEDED', attempt_count = attempt_count + 1,
				last_error = null, next_retry_at = null, updated_at = ?
			where id = ?
			""", Timestamp.from(Instant.now()), id);
		return require(id, false);
	}

	@Transactional
	public JobView fail(UUID id, String error) {
		JobView job = require(id, true);
		if (!List.of("PENDING", "RETRYING", "PROCESSING").contains(job.status())) {
			throw DomainException.conflict("INTEGRATION_FAIL_INVALID", "当前接口任务不能标记失败");
		}
		Instant now = Instant.now();
		jdbc.update("""
			update integration_job
			set status = 'FAILED', attempt_count = attempt_count + 1, last_error = ?,
				next_retry_at = ?, updated_at = ?
			where id = ?
			""", error.trim(), Timestamp.from(now.plus(5, ChronoUnit.MINUTES)), Timestamp.from(now), id);
		return require(id, false);
	}

	@Transactional
	public JobView retry(UUID id) {
		JobView job = require(id, true);
		if (!job.status().equals("FAILED")) {
			throw DomainException.conflict("INTEGRATION_RETRY_INVALID", "只有失败任务可以重试");
		}
		jdbc.update("""
			update integration_job
			set status = 'RETRYING', next_retry_at = null, updated_at = ?
			where id = ?
			""", Timestamp.from(Instant.now()), id);
		return require(id, false);
	}

	@Transactional(readOnly = true)
	public List<JobView> list() {
		return jdbc.query("select * from integration_job order by created_at desc",
			IntegrationApplication::map);
	}

	private JobView require(UUID id, boolean lock) {
		String sql = "select * from integration_job where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, IntegrationApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("INTEGRATION_JOB_NOT_FOUND", "接口任务不存在"));
	}

	private static JobView map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp retryAt = rs.getTimestamp("next_retry_at");
		return new JobView(rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
			rs.getString("job_no"), rs.getString("interface_code"), rs.getString("business_key"),
			rs.getString("direction"), rs.getString("payload_text"), rs.getString("status"),
			rs.getInt("attempt_count"), rs.getString("last_error"),
			retryAt == null ? null : retryAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String identifier() {
		return "INT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record SubmitCommand(UUID operationId, String interfaceCode, String businessKey,
			String direction, String payload) {
	}

	public record Submission(JobView job, boolean duplicate) {
	}

	public record JobView(UUID id, UUID operationId, String jobNo, String interfaceCode,
			String businessKey, String direction, String payload, String status, int attemptCount,
			String lastError, Instant nextRetryAt, Instant createdAt, Instant updatedAt) {
	}
}

@RestController
@RequestMapping("/api/integration/jobs")
class IntegrationController {

	private final IntegrationApplication integration;

	IntegrationController(IntegrationApplication integration) {
		this.integration = integration;
	}

	@GetMapping
	List<IntegrationApplication.JobView> list() {
		return integration.list();
	}

	@PostMapping
	ResponseEntity<IntegrationApplication.Submission> submit(@Valid @RequestBody SubmitRequest request) {
		IntegrationApplication.Submission result = integration.submit(new IntegrationApplication.SubmitCommand(
			request.operationId(), request.interfaceCode(), request.businessKey(),
			request.direction(), request.payload()));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@PostMapping("/{id}/success")
	IntegrationApplication.JobView complete(@PathVariable UUID id) {
		return integration.complete(id);
	}

	@PostMapping("/{id}/failure")
	IntegrationApplication.JobView fail(@PathVariable UUID id,
			@Valid @RequestBody FailureRequest request) {
		return integration.fail(id, request.error());
	}

	@PostMapping("/{id}/retry")
	IntegrationApplication.JobView retry(@PathVariable UUID id) {
		return integration.retry(id);
	}

	record SubmitRequest(@NotNull UUID operationId, @NotBlank @Size(max = 64) String interfaceCode,
			@NotBlank @Size(max = 128) String businessKey, @NotBlank String direction,
			@NotBlank @Size(max = 4000) String payload) {
	}

	record FailureRequest(@NotBlank @Size(max = 1000) String error) {
	}
}
