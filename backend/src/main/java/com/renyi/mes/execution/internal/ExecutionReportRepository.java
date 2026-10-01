package com.renyi.mes.execution.internal;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionReportRepository extends JpaRepository<ExecutionReportEntity, UUID> {

	Optional<ExecutionReportEntity> findByOperationId(UUID operationId);

	List<ExecutionReportEntity> findByTaskIdOrderByOccurredAt(UUID taskId);

	List<ExecutionReportEntity> findByTaskIdAndOccurredAtLessThanEqualOrderByOccurredAt(UUID taskId, Instant occurredAt);
}
