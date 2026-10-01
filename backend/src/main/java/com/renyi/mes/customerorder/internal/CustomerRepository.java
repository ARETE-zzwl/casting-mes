package com.renyi.mes.customerorder.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

	boolean existsByCodeIgnoreCase(String code);

	List<CustomerEntity> findAllByOrderByCreatedAtDesc();
}
