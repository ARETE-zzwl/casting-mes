package com.renyi.mes.engineering;

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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
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
public class SopApplication {

	private final JdbcTemplate jdbc;

	public SopApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public SopView getPublished(String operationCode) {
		List<SopHeader> headers = jdbc.query("""
			select * from operation_sop
			where operation_code = ? and status = 'PUBLISHED'
			order by updated_at desc
			""", SopApplication::mapHeader, normalize(operationCode));
		if (headers.isEmpty()) {
			throw DomainException.notFound("SOP_NOT_FOUND", "该工序尚未发布SOP");
		}
		return load(headers.getFirst());
	}

	@Transactional(readOnly = true)
	public List<SopView> listPublished() {
		return jdbc.query("""
			select * from operation_sop where status = 'PUBLISHED' order by operation_name
			""", SopApplication::mapHeader).stream().map(this::load).toList();
	}

	@Transactional
	public SopView publish(PublishCommand command) {
		String operationCode = normalize(command.operationCode());
		String version = normalize(command.version());
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			update operation_sop set status = 'SUPERSEDED'
			where operation_code = ? and status = 'PUBLISHED'
			""", operationCode);
		try {
			jdbc.update("""
				insert into operation_sop (
					id, operation_code, operation_name, version, safety_notice,
					preparation_note, status, updated_by, updated_at
				) values (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?)
				""", id, operationCode, command.operationName().trim(), version,
				command.safetyNotice().trim(), command.preparationNote().trim(),
				normalize(command.updatedBy()), Timestamp.from(now));
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("SOP_VERSION_EXISTS", "该工序的SOP版本已存在");
		}
		for (int index = 0; index < command.steps().size(); index++) {
			StepCommand step = command.steps().get(index);
			jdbc.update("""
				insert into operation_sop_step (id, sop_id, step_no, title, instruction)
				values (?, ?, ?, ?, ?)
				""", UUID.randomUUID(), id, index + 1, step.title().trim(), step.instruction().trim());
		}
		for (int index = 0; index < command.qualityPoints().size(); index++) {
			jdbc.update("""
				insert into operation_sop_quality_point (id, sop_id, point_no, content)
				values (?, ?, ?, ?)
				""", UUID.randomUUID(), id, index + 1, command.qualityPoints().get(index).trim());
		}
		for (int index = 0; index < command.keyParameters().size(); index++) {
			KeyParameterCommand parameter = command.keyParameters().get(index);
			jdbc.update("""
				insert into operation_sop_key_parameter (id, sop_id, parameter_no, parameter_name, parameter_value)
				values (?, ?, ?, ?, ?)
				""", UUID.randomUUID(), id, index + 1, parameter.name().trim(), parameter.value().trim());
		}
		return getPublished(operationCode);
	}

	private SopView load(SopHeader header) {
		List<StepView> steps = jdbc.query("""
			select step_no, title, instruction from operation_sop_step
			where sop_id = ? order by step_no
			""", (rs, rowNum) -> new StepView(
				rs.getInt("step_no"), rs.getString("title"), rs.getString("instruction")), header.id());
		List<String> qualityPoints = jdbc.query("""
			select content from operation_sop_quality_point
			where sop_id = ? order by point_no
			""", (rs, rowNum) -> rs.getString("content"), header.id());
		List<KeyParameterView> keyParameters = jdbc.query("""
			select parameter_name, parameter_value from operation_sop_key_parameter
			where sop_id = ? order by parameter_no
			""", (rs, rowNum) -> new KeyParameterView(
				rs.getString("parameter_name"), rs.getString("parameter_value")), header.id());
		return new SopView(header.id(), header.operationCode(), header.operationName(),
			header.version(), header.safetyNotice(), header.preparationNote(), header.status(),
			header.updatedBy(), header.updatedAt(), steps, qualityPoints, keyParameters);
	}

	private static SopHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
		return new SopHeader(rs.getObject("id", UUID.class), rs.getString("operation_code"),
			rs.getString("operation_name"), rs.getString("version"), rs.getString("safety_notice"),
			rs.getString("preparation_note"), rs.getString("status"), rs.getString("updated_by"),
			rs.getTimestamp("updated_at").toInstant());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private record SopHeader(UUID id, String operationCode, String operationName, String version,
			String safetyNotice, String preparationNote, String status, String updatedBy,
			Instant updatedAt) {
	}

	public record PublishCommand(String operationCode, String operationName, String version,
			String safetyNotice, String preparationNote, List<StepCommand> steps,
			List<String> qualityPoints, List<KeyParameterCommand> keyParameters, String updatedBy) {
	}

	public record StepCommand(String title, String instruction) {
	}

	public record KeyParameterCommand(String name, String value) {
	}

	public record SopView(UUID id, String operationCode, String operationName, String version,
			String safetyNotice, String preparationNote, String status, String updatedBy,
			Instant updatedAt, List<StepView> steps, List<String> qualityPoints,
			List<KeyParameterView> keyParameters) {
	}

	public record StepView(int stepNo, String title, String instruction) {
	}

	public record KeyParameterView(String name, String value) {
	}
}

@RestController
@RequestMapping("/api/sops")
class SopController {

	private final SopApplication sops;

	SopController(SopApplication sops) {
		this.sops = sops;
	}

	@GetMapping
	List<SopApplication.SopView> list() {
		return sops.listPublished();
	}

	@GetMapping("/{operationCode}")
	SopApplication.SopView get(@PathVariable String operationCode) {
		return sops.getPublished(operationCode);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SopApplication.SopView publish(@Valid @RequestBody PublishRequest request) {
		return sops.publish(new SopApplication.PublishCommand(
			request.operationCode(), request.operationName(), request.version(),
			request.safetyNotice(), request.preparationNote(),
			request.steps().stream()
				.map(step -> new SopApplication.StepCommand(step.title(), step.instruction()))
				.toList(),
			request.qualityPoints(), request.keyParameters() == null ? List.of() : request.keyParameters().stream()
				.map(parameter -> new SopApplication.KeyParameterCommand(parameter.name(), parameter.value()))
				.toList(), request.updatedBy()));
	}

	record PublishRequest(@NotBlank @Size(max = 64) String operationCode,
			@NotBlank @Size(max = 120) String operationName,
			@NotBlank @Size(max = 32) String version,
			@NotBlank @Size(max = 1000) String safetyNotice,
			@NotBlank @Size(max = 1000) String preparationNote,
			@NotEmpty List<@Valid StepRequest> steps,
			@NotEmpty List<@NotBlank @Size(max = 500) String> qualityPoints,
			@Size(max = 5) List<@Valid KeyParameterRequest> keyParameters,
			@NotBlank @Size(max = 64) String updatedBy) {
	}

	record StepRequest(@NotBlank @Size(max = 120) String title,
			@NotBlank @Size(max = 1000) String instruction) {
	}

	record KeyParameterRequest(@NotBlank @Size(max = 80) String name,
			@NotBlank @Size(max = 500) String value) {
	}
}
