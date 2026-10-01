package com.renyi.mes.engineering.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

	boolean existsByCodeIgnoreCase(String code);
	List<ProductEntity> findByMaterialVariantOfAndMaterialIgnoreCase(UUID materialVariantOf, String material);

	List<ProductEntity> findAllByOrderByCreatedAtDesc();
}
