package com.renyi.mes.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.OperationalAuditPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationalAuditApplication implements OperationalAuditPort {

	private final JdbcTemplate jdbc;

	public OperationalAuditApplication(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public void record(String eventType, String entityType, UUID entityId, UUID operationId, String operatorCode,
			String deviceCode, String workstationCode, String detail) {
		jdbc.update("insert into operation_audit_event (id, event_type, entity_type, entity_id, operation_id, operator_code, device_code, workstation_code, occurred_at, detail) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), eventType, entityType, entityId, operationId, normalize(operatorCode), optional(deviceCode), optional(workstationCode), Timestamp.from(Instant.now()), optional(detail));
	}

	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
	private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
