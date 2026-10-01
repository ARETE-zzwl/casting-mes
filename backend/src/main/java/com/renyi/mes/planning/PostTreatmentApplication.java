package com.renyi.mes.planning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.outsourcing.OutsourcingApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class PostTreatmentApplication {
	private static final List<String> PROCESS_CODES = List.of(
		"SAND_BLASTING", "GRINDING", "POLISHING", "HEAT_TREATMENT", "PICKLING", "MACHINING", "PRESSURE_TEST", "OTHER"
	);
	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final ProductionLineScopeApplication lineScopes;
	private final OutsourcingApplication outsourcing;

	public PostTreatmentApplication(JdbcTemplate jdbc, PlanningApplication planning, ProductionLineScopeApplication lineScopes, OutsourcingApplication outsourcing) {
		this.jdbc = jdbc; this.planning = planning; this.lineScopes = lineScopes; this.outsourcing = outsourcing;
	}

	@Transactional
	public DecisionView decide(DecisionCommand command) {
		PlanningApplication.TaskView task = planning.getTask(command.sourceTaskId());
		if (!"SEMI_FINISHED_COUNT".equals(task.operationCode()) || task.status() == TaskStatus.BLOCKED) {
			throw DomainException.conflict("POST_TREATMENT_STAGE_INVALID", "请在半成品清点可执行后确定后处理去向");
		}
		if (!lineScopes.canDispatch(command.decidedBy(), task.routeType(), task.operationCode())) {
			throw DomainException.forbidden("POST_TREATMENT_SCOPE_FORBIDDEN", "当前主管无权决定该批次后处理去向");
		}
		String destination = normalizeDestination(command.destination());
		List<String> processes = normalizeProcesses(command.processCodes());
		if (processes.isEmpty() && blank(command.processSummary()) != null) processes = List.of("OTHER");
		if (("IN_HOUSE".equals(destination) || "OUTSOURCE".equals(destination)) && processes.isEmpty()) {
			throw DomainException.badRequest("POST_TREATMENT_PROCESS_REQUIRED", "本厂或外送后处理至少选择一道工艺");
		}
		if ("OUTSOURCE".equals(destination) && command.supplierId() == null) {
			throw DomainException.badRequest("POST_TREATMENT_SUPPLIER_REQUIRED", "外送后处理必须选择供应商");
		}
		Integer existing = jdbc.queryForObject("select count(*) from post_treatment_decision where batch_id = ?", Integer.class, task.batchId());
		if (existing != null && existing > 0) throw DomainException.conflict("POST_TREATMENT_ALREADY_DECIDED", "该批次已确定后处理去向，需由主管走变更流程");
		UUID outsourcingOrderId = "OUTSOURCE".equals(destination)
			? outsourcing.createOrder(new OutsourcingApplication.OrderCommand("OSF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
				command.supplierId(), task.taskNo(), "后处理 / " + task.taskNo(), task.plannedQuantity(), "PCS", null, blank(command.note()))).id()
			: null;
		String warehouseCode = isFinishedWarehouse(destination) ? defaultWarehouse(command.warehouseCode()) : null;
		UUID id = UUID.randomUUID(); Instant now = Instant.now();
		jdbc.update("""
			insert into post_treatment_decision (id, batch_id, source_task_id, destination, process_summary, process_codes, supplier_id, outsourcing_order_id, warehouse_code, note, decided_by, decided_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", id, task.batchId(), task.id(), destination, blank(command.processSummary()), String.join("|", processes), command.supplierId(), outsourcingOrderId,
			warehouseCode, blank(command.note()), normalize(command.decidedBy()), Timestamp.from(now));
		if ("IN_HOUSE".equals(destination)) {
			jdbc.update("""
				update planning_task set compensation_mode = ?
				where batch_id = ? and operation_code = 'OPTIONAL_FINISHING' and status = 'BLOCKED'
				""", postTreatmentCompensationMode(processes), task.batchId());
		}
		return get(id);
	}

	@Transactional(readOnly = true)
	public List<DecisionView> list(String supervisorCode) {
		return jdbc.query("select * from post_treatment_decision order by decided_at desc", (rs, row) -> map(rs)).stream()
			.filter(decision -> lineScopes.canDispatch(supervisorCode, planning.getTask(decision.sourceTaskId()).routeType(), "SEMI_FINISHED_COUNT"))
			.toList();
	}

	private DecisionView get(UUID id) { return jdbc.query("select * from post_treatment_decision where id = ?", (rs, row) -> map(rs), id).getFirst(); }
	private static DecisionView map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new DecisionView(rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getObject("source_task_id", UUID.class),
			rs.getString("destination"), rs.getString("process_summary"), splitCodes(rs.getString("process_codes")), rs.getObject("supplier_id", UUID.class),
			rs.getObject("outsourcing_order_id", UUID.class), rs.getString("warehouse_code"), rs.getString("note"), rs.getString("decided_by"), rs.getTimestamp("decided_at").toInstant());
	}
	private static List<String> normalizeProcesses(List<String> values) {
		if (values == null) return List.of();
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.isBlank()) continue;
			String code = normalize(value);
			if (!PROCESS_CODES.contains(code)) throw DomainException.badRequest("POST_TREATMENT_PROCESS_INVALID", "后处理工艺无效");
			normalized.add(code);
		}
		return List.copyOf(normalized);
	}
	private static List<String> splitCodes(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.split("\\|")); }
	private static String postTreatmentCompensationMode(List<String> processes) {
		return !processes.isEmpty() && processes.stream().allMatch(code -> List.of("SAND_BLASTING", "GRINDING", "POLISHING", "PICKLING").contains(code))
			? "PIECE_KG" : "HOURLY";
	}
	private static boolean isFinishedWarehouse(String destination) { return "DIRECT_FINISHED".equals(destination) || "FINISHED_GOODS_STORAGE".equals(destination); }
	private static String defaultWarehouse(String value) { return value == null || value.isBlank() ? "FG-01" : normalize(value); }
	private static String normalizeDestination(String value) { String normalized = normalize(value); if (!List.of("IN_HOUSE", "OUTSOURCE", "DIRECT_FINISHED", "FINISHED_GOODS_STORAGE").contains(normalized)) throw DomainException.badRequest("POST_TREATMENT_DESTINATION_INVALID", "后处理去向无效"); return normalized; }
	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
	private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

	public record DecisionCommand(UUID sourceTaskId, String destination, List<String> processCodes, String processSummary, UUID supplierId, String warehouseCode, String note, String decidedBy) { }
	public record DecisionView(UUID id, UUID batchId, UUID sourceTaskId, String destination, String processSummary, List<String> processCodes, UUID supplierId, UUID outsourcingOrderId, String warehouseCode, String note, String decidedBy, Instant decidedAt) { }
}

@RestController
@RequestMapping("/api/post-treatment")
class PostTreatmentController {
	private final PostTreatmentApplication postTreatment;
	PostTreatmentController(PostTreatmentApplication postTreatment) { this.postTreatment = postTreatment; }

	@GetMapping("/decisions") List<PostTreatmentApplication.DecisionView> list(@RequestParam String supervisorCode) { return postTreatment.list(supervisorCode); }
	@PostMapping("/decisions") @ResponseStatus(HttpStatus.CREATED) PostTreatmentApplication.DecisionView decide(@Valid @RequestBody DecisionRequest request) {
		return postTreatment.decide(new PostTreatmentApplication.DecisionCommand(request.sourceTaskId(), request.destination(), request.processCodes(), request.processSummary(), request.supplierId(), request.warehouseCode(), request.note(), request.decidedBy()));
	}

	record DecisionRequest(@NotNull UUID sourceTaskId, @NotBlank @Size(max=32) String destination, @Size(max=12) List<String> processCodes, @Size(max=1000) String processSummary, UUID supplierId, @Size(max=64) String warehouseCode, @Size(max=500) String note, @NotBlank @Size(max=64) String decidedBy) { }
}
