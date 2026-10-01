package com.renyi.mes.quality;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.planning.PlanningApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class QualityApplication {

	private static final List<String> DECISIONS = List.of("REWORK", "SCRAP", "CONCESSION");

	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final com.renyi.mes.common.BusinessAccess access;

	public QualityApplication(JdbcTemplate jdbc, PlanningApplication planning, com.renyi.mes.common.BusinessAccess access) {
		this.jdbc = jdbc;
		this.planning = planning;
		this.access = access;
	}

	@Transactional
	public InspectionResult inspect(InspectionCommand command) {
		access.requireTask(command.taskId());
		InspectionView existing = findByOperationId(command.operationId());
		if (existing != null) {
			return duplicate(existing, command);
		}

		if (command.acceptedQuantity().add(command.rejectedQuantity()).compareTo(command.inspectedQuantity()) != 0) {
			throw DomainException.badRequest("QUALITY_QUANTITY_MISMATCH", "合格数与不合格数之和必须等于检验数");
		}
		PlanningApplication.TaskView task = planning.lockTask(command.taskId());
		existing = findByOperationId(command.operationId());
		if (existing != null) {
			return duplicate(existing, command);
		}
		BigDecimal inspected = jdbc.queryForObject(
			"select coalesce(sum(inspected_quantity), 0) from quality_inspection where task_id = ?",
			BigDecimal.class,
			command.taskId()
		);
		if (inspected.add(command.inspectedQuantity()).compareTo(task.goodQuantity()) > 0) {
			throw DomainException.conflict("QUALITY_QUANTITY_EXCEEDED", "累计检验数不能超过任务合格数");
		}

		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		String result = command.rejectedQuantity().signum() == 0 ? "PASSED" : "REJECTED";
		jdbc.update("""
			insert into quality_inspection (
				id, operation_id, inspection_no, task_id, inspected_quantity,
				accepted_quantity, rejected_quantity, result, defect_code,
				inspector_code, remark, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			id,
			command.operationId(),
			identifier("QI"),
			command.taskId(),
			command.inspectedQuantity(),
			command.acceptedQuantity(),
			command.rejectedQuantity(),
			result,
			blankToNull(command.defectCode()),
			normalize(command.inspectorCode()),
			blankToNull(command.remark()),
			Timestamp.from(now)
		);
		return new InspectionResult(requireInspection(id), false);
	}

	private InspectionResult duplicate(InspectionView existing, InspectionCommand command) {
		if (!existing.taskId().equals(command.taskId())) {
			throw DomainException.conflict("QUALITY_OPERATION_REUSED", "质量操作号已被其他任务使用");
		}
		if (existing.inspectedQuantity().compareTo(command.inspectedQuantity()) != 0
				|| existing.acceptedQuantity().compareTo(command.acceptedQuantity()) != 0
				|| existing.rejectedQuantity().compareTo(command.rejectedQuantity()) != 0
				|| !existing.inspectorCode().equals(normalize(command.inspectorCode()))) {
			throw DomainException.conflict(
				"QUALITY_OPERATION_PAYLOAD_MISMATCH", "相同操作号的检验内容不一致");
		}
		return new InspectionResult(existing, true);
	}

	@Transactional
	public DispositionView dispose(UUID inspectionId, DispositionCommand command) {
		lockInspection(inspectionId);
		InspectionView inspection = requireInspection(inspectionId);
		access.requireTask(inspection.taskId());
		String decision = command.decision().trim().toUpperCase(Locale.ROOT);
		if (!DECISIONS.contains(decision)) {
			throw DomainException.badRequest("INVALID_QUALITY_DECISION", "处置方式必须是返工、报废或让步");
		}
		BigDecimal disposed = jdbc.queryForObject(
			"select coalesce(sum(quantity), 0) from quality_disposition where inspection_id = ?",
			BigDecimal.class,
			inspectionId
		);
		if (disposed.add(command.quantity()).compareTo(inspection.rejectedQuantity()) > 0) {
			throw DomainException.conflict("DISPOSITION_QUANTITY_EXCEEDED", "累计处置数不能超过不合格数");
		}

		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into quality_disposition (
				id, inspection_id, decision, quantity, reason, decided_by, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?)
			""",
			id,
			inspectionId,
			decision,
			command.quantity(),
			command.reason().trim(),
			normalize(command.decidedBy()),
			Timestamp.from(now)
		);
		return new DispositionView(
			id,
			inspectionId,
			decision,
			command.quantity(),
			command.reason().trim(),
			normalize(command.decidedBy()),
			now
		);
	}

	private void lockInspection(UUID inspectionId) {
		List<UUID> rows = jdbc.query(
			"select id from quality_inspection where id = ? for update",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			inspectionId
		);
		if (rows.isEmpty()) {
			throw DomainException.notFound("INSPECTION_NOT_FOUND", "质量检验记录不存在");
		}
	}

	@Transactional(readOnly = true)
	public List<InspectionView> listInspections() {
		return jdbc.query("""
			select q.*,
				(select count(*) from quality_disposition d where d.inspection_id = q.id) disposition_count,
				(select coalesce(sum(d.quantity), 0) from quality_disposition d where d.inspection_id = q.id) disposed_quantity
			from quality_inspection q
			order by q.occurred_at desc
			""", QualityApplication::mapInspection).stream().filter(inspection -> access.canReadTask(inspection.taskId())).toList();
	}

	@Transactional(readOnly = true)
	public List<DispositionView> dispositions(UUID inspectionId) {
		access.requireTask(requireInspection(inspectionId).taskId());
		return jdbc.query("""
			select id, inspection_id, decision, quantity, reason, decided_by, occurred_at
			from quality_disposition where inspection_id = ? order by occurred_at
			""", QualityApplication::mapDisposition, inspectionId);
	}

	private InspectionView findByOperationId(UUID operationId) {
		List<InspectionView> rows = jdbc.query("""
			select q.*,
				(select count(*) from quality_disposition d where d.inspection_id = q.id) disposition_count,
				(select coalesce(sum(d.quantity), 0) from quality_disposition d where d.inspection_id = q.id) disposed_quantity
			from quality_inspection q where q.operation_id = ?
			""", QualityApplication::mapInspection, operationId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private InspectionView requireInspection(UUID id) {
		List<InspectionView> rows = jdbc.query("""
			select q.*,
				(select count(*) from quality_disposition d where d.inspection_id = q.id) disposition_count,
				(select coalesce(sum(d.quantity), 0) from quality_disposition d where d.inspection_id = q.id) disposed_quantity
			from quality_inspection q where q.id = ?
			""", QualityApplication::mapInspection, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("INSPECTION_NOT_FOUND", "质量检验记录不存在");
		}
		return rows.getFirst();
	}

	private static InspectionView mapInspection(ResultSet rs, int rowNum) throws SQLException {
		return new InspectionView(
			rs.getObject("id", UUID.class),
			rs.getObject("operation_id", UUID.class),
			rs.getString("inspection_no"),
			rs.getObject("task_id", UUID.class),
			rs.getBigDecimal("inspected_quantity"),
			rs.getBigDecimal("accepted_quantity"),
			rs.getBigDecimal("rejected_quantity"),
			rs.getString("result"),
			rs.getString("defect_code"),
			rs.getString("inspector_code"),
			rs.getString("remark"),
			rs.getLong("disposition_count"),
			rs.getBigDecimal("disposed_quantity"),
			rs.getTimestamp("occurred_at").toInstant()
		);
	}

	private static DispositionView mapDisposition(ResultSet rs, int rowNum) throws SQLException {
		return new DispositionView(
			rs.getObject("id", UUID.class),
			rs.getObject("inspection_id", UUID.class),
			rs.getString("decision"),
			rs.getBigDecimal("quantity"),
			rs.getString("reason"),
			rs.getString("decided_by"),
			rs.getTimestamp("occurred_at").toInstant()
		);
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public record InspectionCommand(
		UUID operationId,
		UUID taskId,
		BigDecimal inspectedQuantity,
		BigDecimal acceptedQuantity,
		BigDecimal rejectedQuantity,
		String defectCode,
		String inspectorCode,
		String remark
	) {
	}

	public record DispositionCommand(String decision, BigDecimal quantity, String reason, String decidedBy) {
	}

	public record InspectionResult(InspectionView inspection, boolean duplicate) {
	}

	public record InspectionView(
		UUID id,
		UUID operationId,
		String inspectionNo,
		UUID taskId,
		BigDecimal inspectedQuantity,
		BigDecimal acceptedQuantity,
		BigDecimal rejectedQuantity,
		String result,
		String defectCode,
		String inspectorCode,
		String remark,
		long dispositionCount,
		BigDecimal disposedQuantity,
		Instant occurredAt
	) {
	}

	public record DispositionView(
		UUID id,
		UUID inspectionId,
		String decision,
		BigDecimal quantity,
		String reason,
		String decidedBy,
		Instant occurredAt
	) {
	}
}

@RestController
@RequestMapping("/api/quality")
class QualityController {

	private final QualityApplication quality;

	QualityController(QualityApplication quality) {
		this.quality = quality;
	}

	@GetMapping("/inspections")
	List<QualityApplication.InspectionView> list() {
		return quality.listInspections();
	}

	@PostMapping("/inspections")
	ResponseEntity<QualityApplication.InspectionResult> inspect(@Valid @RequestBody InspectionRequest request) {
		QualityApplication.InspectionResult result = quality.inspect(new QualityApplication.InspectionCommand(
			request.operationId(),
			request.taskId(),
			request.inspectedQuantity(),
			request.acceptedQuantity(),
			request.rejectedQuantity(),
			request.defectCode(),
			request.inspectorCode(),
			request.remark()
		));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@GetMapping("/inspections/{inspectionId}/dispositions")
	List<QualityApplication.DispositionView> dispositions(@PathVariable UUID inspectionId) {
		return quality.dispositions(inspectionId);
	}

	@PostMapping("/inspections/{inspectionId}/dispositions")
	@ResponseStatus(HttpStatus.CREATED)
	QualityApplication.DispositionView dispose(
		@PathVariable UUID inspectionId,
		@Valid @RequestBody DispositionRequest request
	) {
		return quality.dispose(inspectionId, new QualityApplication.DispositionCommand(
			request.decision(),
			request.quantity(),
			request.reason(),
			request.decidedBy()
		));
	}

	record InspectionRequest(
		@NotNull UUID operationId,
		@NotNull UUID taskId,
		@NotNull @DecimalMin("0.001") BigDecimal inspectedQuantity,
		@NotNull @DecimalMin("0.0") BigDecimal acceptedQuantity,
		@NotNull @DecimalMin("0.0") BigDecimal rejectedQuantity,
		@Size(max = 64) String defectCode,
		@NotBlank @Size(max = 64) String inspectorCode,
		@Size(max = 500) String remark
	) {
	}

	record DispositionRequest(
		@NotBlank String decision,
		@NotNull @DecimalMin("0.001") BigDecimal quantity,
		@NotBlank @Size(max = 500) String reason,
		@NotBlank @Size(max = 64) String decidedBy
	) {
	}
}
