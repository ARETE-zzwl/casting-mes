package com.renyi.mes.execution;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.stereotype.Service
public class ReportFormProfileApplication {

	private final JdbcTemplate jdbc;

	public ReportFormProfileApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<ReportFormProfileView> list() {
		return jdbc.query("""
			select operation_code, show_photo, require_photo, show_device, require_device,
			       show_workstation, require_workstation, updated_by, updated_at
			from operation_report_form_profile order by operation_code
			""", (rs, row) -> map(rs.getString("operation_code"), rs.getBoolean("show_photo"),
			rs.getBoolean("require_photo"), rs.getBoolean("show_device"), rs.getBoolean("require_device"),
			rs.getBoolean("show_workstation"), rs.getBoolean("require_workstation"), rs.getString("updated_by"),
			rs.getTimestamp("updated_at").toInstant()));
	}

	public ReportFormProfileView forOperation(String operationCode) {
		String operation = normalize(operationCode);
		return jdbc.query("""
			select operation_code, show_photo, require_photo, show_device, require_device,
			       show_workstation, require_workstation, updated_by, updated_at
			from operation_report_form_profile where operation_code = ?
			""", (rs, row) -> map(rs.getString("operation_code"), rs.getBoolean("show_photo"),
			rs.getBoolean("require_photo"), rs.getBoolean("show_device"), rs.getBoolean("require_device"),
			rs.getBoolean("show_workstation"), rs.getBoolean("require_workstation"), rs.getString("updated_by"),
			rs.getTimestamp("updated_at").toInstant()), operation).stream().findFirst()
			.orElse(new ReportFormProfileView(operation, true, false, false, false, false, false, "SYSTEM", null));
	}

	@Transactional
	public ReportFormProfileView save(ReportFormProfileCommand command) {
		String operation = normalize(command.operationCode());
		if (command.requirePhoto() && !command.showPhoto()
			|| command.requireDevice() && !command.showDevice()
			|| command.requireWorkstation() && !command.showWorkstation()) {
			throw DomainException.badRequest("REPORT_FORM_REQUIRED_HIDDEN", "必填字段必须同时显示");
		}
		Instant now = Instant.now();
		int updated = jdbc.update("""
			update operation_report_form_profile set show_photo = ?, require_photo = ?, show_device = ?,
				require_device = ?, show_workstation = ?, require_workstation = ?, updated_by = ?, updated_at = ?
			where operation_code = ?
			""", command.showPhoto(), command.requirePhoto(), command.showDevice(), command.requireDevice(),
			command.showWorkstation(), command.requireWorkstation(), normalize(command.updatedBy()), now, operation);
		if (updated == 0) {
			jdbc.update("""
				insert into operation_report_form_profile (operation_code, show_photo, require_photo, show_device,
					require_device, show_workstation, require_workstation, updated_by, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", operation, command.showPhoto(), command.requirePhoto(), command.showDevice(), command.requireDevice(),
				command.showWorkstation(), command.requireWorkstation(), normalize(command.updatedBy()), now);
		}
		return forOperation(operation);
	}

	private static ReportFormProfileView map(String operationCode, boolean showPhoto, boolean requirePhoto,
			boolean showDevice, boolean requireDevice, boolean showWorkstation, boolean requireWorkstation,
			String updatedBy, Instant updatedAt) {
		return new ReportFormProfileView(operationCode, showPhoto, requirePhoto, showDevice, requireDevice,
			showWorkstation, requireWorkstation, updatedBy, updatedAt);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	public record ReportFormProfileCommand(String operationCode, boolean showPhoto, boolean requirePhoto,
			boolean showDevice, boolean requireDevice, boolean showWorkstation, boolean requireWorkstation,
			String updatedBy) { }

	public record ReportFormProfileView(String operationCode, boolean showPhoto, boolean requirePhoto,
			boolean showDevice, boolean requireDevice, boolean showWorkstation, boolean requireWorkstation,
			String updatedBy, Instant updatedAt) { }
}

@RestController
@RequestMapping("/api/report-form-profiles")
class ReportFormProfileController {
	private final ReportFormProfileApplication profiles;

	ReportFormProfileController(ReportFormProfileApplication profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	List<ReportFormProfileApplication.ReportFormProfileView> list() {
		return profiles.list();
	}

	@GetMapping("/{operationCode}")
	ReportFormProfileApplication.ReportFormProfileView get(@PathVariable String operationCode) {
		return profiles.forOperation(operationCode);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ReportFormProfileApplication.ReportFormProfileView save(@Valid @RequestBody ReportFormProfileRequest request) {
		return profiles.save(new ReportFormProfileApplication.ReportFormProfileCommand(request.operationCode(),
			request.showPhoto(), request.requirePhoto(), request.showDevice(), request.requireDevice(),
			request.showWorkstation(), request.requireWorkstation(), request.updatedBy()));
	}

	record ReportFormProfileRequest(@NotBlank @Size(max = 64) String operationCode, boolean showPhoto,
			boolean requirePhoto, boolean showDevice, boolean requireDevice, boolean showWorkstation,
			boolean requireWorkstation, @NotBlank @Size(max = 64) String updatedBy) { }
}
