package com.renyi.mes.common;

import java.util.List;
import java.util.UUID;

/**
 * Boundary used by outsourcing to advance the production tasks it is linked to.
 */
public interface OutsourcingProductionPort {

	List<ProductionTaskPort.TaskSnapshot> readyForDispatch(String operatorCode);

	ProductionTaskPort.TaskSnapshot getLinkedTask(UUID dispatchTaskId);

	ProductionTaskPort.TaskSnapshot startOperation(UUID dispatchTaskId, String operationCode, String operatorCode);

	ProductionTaskPort.TaskSnapshot completeOperation(UUID dispatchTaskId, String operationCode, String operatorCode);

	boolean isOperationCompleted(UUID dispatchTaskId, String operationCode);
}
