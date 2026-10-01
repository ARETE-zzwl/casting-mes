package com.renyi.mes.customerorder.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrderEntity, UUID> {

	boolean existsByOrderNoIgnoreCase(String orderNo);

	List<CustomerOrderEntity> findAllByOrderByCreatedAtDesc();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select customerOrder from CustomerOrderEntity customerOrder where customerOrder.id = :orderId")
	Optional<CustomerOrderEntity> findLockedById(@Param("orderId") UUID orderId);
}
