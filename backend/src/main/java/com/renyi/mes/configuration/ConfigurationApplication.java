package com.renyi.mes.configuration;

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
public class ConfigurationApplication {

	private static final List<String> TYPES = List.of("ROUTE", "FORM", "FORMULA", "LABEL", "POLICY");

	private final JdbcTemplate jdbc;

	public ConfigurationApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public PackageView create(CreateCommand command) {
		String type = normalize(command.configType());
		if (!TYPES.contains(type)) {
			throw DomainException.badRequest("CONFIGURATION_TYPE_INVALID", "未知的配置包类型");
		}
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into configuration_package (
				id, package_no, config_type, name, version, content_text, status, created_by, created_at
			) values (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
			""",
			id,
			identifier("CFG"),
			type,
			command.name().trim(),
			normalize(command.version()),
			command.content().trim(),
			normalize(command.createdBy()),
			Timestamp.from(now)
		);
		return requirePackage(id);
	}

	@Transactional
	public PackageView publish(UUID packageId, String operatorCode) {
		PackageView config = requirePackageForUpdate(packageId);
		if (!config.status().equals("DRAFT")) {
			throw DomainException.conflict("CONFIGURATION_PUBLISH_INVALID", "只有草稿配置可以发布");
		}
		String operator = normalize(operatorCode);
		Instant now = Instant.now();
		jdbc.update("""
			update configuration_package
			set status = 'ROLLED_BACK', rolled_back_by = ?, rolled_back_at = ?
			where config_type = ? and name = ? and status = 'PUBLISHED'
			""",
			operator,
			Timestamp.from(now),
			config.configType(),
			config.name()
		);
		jdbc.update("""
			update configuration_package
			set status = 'PUBLISHED', published_by = ?, published_at = ?
			where id = ?
			""", operator, Timestamp.from(now), packageId);
		return requirePackage(packageId);
	}

	@Transactional
	public PackageView rollback(UUID packageId, String operatorCode) {
		PackageView config = requirePackageForUpdate(packageId);
		if (!config.status().equals("PUBLISHED")) {
			throw DomainException.conflict("CONFIGURATION_ROLLBACK_INVALID", "只有已发布配置可以回退");
		}
		jdbc.update("""
			update configuration_package
			set status = 'ROLLED_BACK', rolled_back_by = ?, rolled_back_at = ?
			where id = ?
			""", normalize(operatorCode), Timestamp.from(Instant.now()), packageId);
		return requirePackage(packageId);
	}

	@Transactional(readOnly = true)
	public List<PackageView> list(String configType) {
		if (configType == null || configType.isBlank()) {
			return jdbc.query("""
				select * from configuration_package order by created_at desc
				""", ConfigurationApplication::mapPackage);
		}
		return jdbc.query("""
			select * from configuration_package where config_type = ? order by created_at desc
			""", ConfigurationApplication::mapPackage, normalize(configType));
	}

	private PackageView requirePackage(UUID id) {
		List<PackageView> rows = jdbc.query("""
			select * from configuration_package where id = ?
			""", ConfigurationApplication::mapPackage, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("CONFIGURATION_NOT_FOUND", "配置包不存在");
		}
		return rows.getFirst();
	}

	private PackageView requirePackageForUpdate(UUID id) {
		List<PackageView> rows = jdbc.query("""
			select * from configuration_package where id = ? for update
			""", ConfigurationApplication::mapPackage, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("CONFIGURATION_NOT_FOUND", "配置包不存在");
		}
		return rows.getFirst();
	}

	private static PackageView mapPackage(ResultSet rs, int rowNum) throws SQLException {
		Timestamp publishedAt = rs.getTimestamp("published_at");
		Timestamp rolledBackAt = rs.getTimestamp("rolled_back_at");
		return new PackageView(
			rs.getObject("id", UUID.class),
			rs.getString("package_no"),
			rs.getString("config_type"),
			rs.getString("name"),
			rs.getString("version"),
			rs.getString("content_text"),
			rs.getString("status"),
			rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(),
			rs.getString("published_by"),
			publishedAt == null ? null : publishedAt.toInstant(),
			rs.getString("rolled_back_by"),
			rolledBackAt == null ? null : rolledBackAt.toInstant()
		);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record CreateCommand(
		String configType,
		String name,
		String version,
		String content,
		String createdBy
	) {
	}

	public record PackageView(
		UUID id,
		String packageNo,
		String configType,
		String name,
		String version,
		String content,
		String status,
		String createdBy,
		Instant createdAt,
		String publishedBy,
		Instant publishedAt,
		String rolledBackBy,
		Instant rolledBackAt
	) {
	}
}

@RestController
@RequestMapping("/api/configurations")
class ConfigurationController {

	private final ConfigurationApplication configurations;

	ConfigurationController(ConfigurationApplication configurations) {
		this.configurations = configurations;
	}

	@GetMapping
	List<ConfigurationApplication.PackageView> list(
		@RequestParam(required = false) String configType
	) {
		return configurations.list(configType);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ConfigurationApplication.PackageView create(@Valid @RequestBody CreateRequest request) {
		return configurations.create(new ConfigurationApplication.CreateCommand(
			request.configType(),
			request.name(),
			request.version(),
			request.content(),
			request.createdBy()
		));
	}

	@PostMapping("/{packageId}/publication")
	ConfigurationApplication.PackageView publish(
		@PathVariable UUID packageId,
		@Valid @RequestBody OperatorRequest request
	) {
		return configurations.publish(packageId, request.operatorCode());
	}

	@PostMapping("/{packageId}/rollback")
	ConfigurationApplication.PackageView rollback(
		@PathVariable UUID packageId,
		@Valid @RequestBody OperatorRequest request
	) {
		return configurations.rollback(packageId, request.operatorCode());
	}

	record CreateRequest(
		@NotBlank String configType,
		@NotBlank @Size(max = 160) String name,
		@NotBlank @Size(max = 32) String version,
		@NotBlank @Size(max = 4000) String content,
		@NotBlank @Size(max = 64) String createdBy
	) {
	}

	record OperatorRequest(@NotBlank @Size(max = 64) String operatorCode) {
	}
}
