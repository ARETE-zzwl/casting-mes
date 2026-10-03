package com.renyi.mes.planning.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatchEntity, UUID> {

	List<ProductionBatchEntity> findByWorkOrderIdOrderByCreatedAt(UUID workOrderId);

	List<ProductionBatchEntity> findByWorkOrderIdInOrderByCreatedAtAscIdAsc(List<UUID> workOrderIds);

	Optional<ProductionBatchEntity> findFirstByBatchNoIgnoreCase(String batchNo);
}
