package com.renyi.mes.common;

import java.util.UUID;

/**
 * Appends an immutable operational audit record.
 */
public interface OperationalAuditPort {

	void record(String actionType, String entityType, UUID entityId, UUID operationId,
		String operatorCode, String deviceCode, String workstationCode, String detail);
}
