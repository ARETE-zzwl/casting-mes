package com.renyi.mes.customerorder.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderLineRepository extends JpaRepository<CustomerOrderLineEntity, UUID> {

	List<CustomerOrderLineEntity> findByOrderIdOrderByLineNo(UUID orderId);

	List<CustomerOrderLineEntity> findAllByOrderIdIn(Collection<UUID> orderIds);
}
