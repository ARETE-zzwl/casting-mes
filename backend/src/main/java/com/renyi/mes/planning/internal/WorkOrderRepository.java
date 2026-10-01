package com.renyi.mes.planning.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, UUID> {

	List<WorkOrderEntity> findByOrderIdOrderByCreatedAt(UUID orderId);

	List<WorkOrderEntity> findAllByOrderByCreatedAtDesc();
}
