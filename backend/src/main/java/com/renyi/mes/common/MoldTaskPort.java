package com.renyi.mes.common;

import java.util.UUID;

/**
 * Mould request boundary used by the wax-dispatch workflow.
 */
public interface MoldTaskPort {

	MoldRequestSnapshot findTaskMold(UUID taskId);

	MoldRequestSnapshot requestTaskMold(UUID taskId, UUID moldAssetId, String supervisorCode, String workerCode);

	void issueTaskMold(UUID requestId, UUID taskId, String warehouseCode, String warehouseOperatorCode, String workerCode);

	void returnTaskMold(UUID requestId, String supervisorCode);

	record MoldRequestSnapshot(UUID id, String status, UUID waxTaskId) { }
}
