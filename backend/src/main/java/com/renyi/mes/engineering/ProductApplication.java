package com.renyi.mes.engineering;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.engineering.internal.ProductEntity;
import com.renyi.mes.engineering.internal.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductApplication {

	private final ProductRepository products;

	public ProductApplication(ProductRepository products) {
		this.products = products;
	}

	@Transactional
	public ProductView create(CreateProductCommand command) {
		String code = command.code() == null || command.code().isBlank() ? generatedCode("PRD") : normalizeCode(command.code());
		if (products.existsByCodeIgnoreCase(code)) {
			throw DomainException.conflict("PRODUCT_CODE_EXISTS", "产品编码已存在");
		}

		ProductEntity product = new ProductEntity(
			UUID.randomUUID(),
			code,
			command.name().trim(),
			command.routeType(),
			command.routeVersion().trim(),
			trimToNull(command.modelImageUrl()),
			trimToNull(command.specification()),
			trimToNull(command.material()),
			null,
			true,
			Instant.now()
		);
		return toView(products.save(product));
	}

	@Transactional(readOnly = true)
	public List<ProductView> list() {
		return products.findAllByOrderByCreatedAtDesc().stream()
			.map(ProductApplication::toView)
			.toList();
	}

	@Transactional(readOnly = true)
	public ProductView get(UUID id) {
		return products.findById(id)
			.map(ProductApplication::toView)
			.orElseThrow(() -> DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在"));
	}

	@Transactional
	public ProductView resolveMaterialVariant(UUID productId, String requestedMaterial) {
		ProductEntity selected = products.findById(productId)
			.orElseThrow(() -> DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在"));
		String material = trimToNull(requestedMaterial);
		if (material == null || sameMaterial(selected.material(), material)) return toView(selected);
		ProductEntity root = selected.materialVariantOf() == null ? selected : products.findById(selected.materialVariantOf())
			.orElseThrow(() -> DomainException.notFound("PRODUCT_VARIANT_ROOT_NOT_FOUND", "产品材质族根不存在"));
		if (sameMaterial(root.material(), material)) return toView(root);
		return products.findByMaterialVariantOfAndMaterialIgnoreCase(root.id(), material).stream()
			.findFirst().map(ProductApplication::toView).orElseGet(() -> toView(products.save(new ProductEntity(
			UUID.randomUUID(), generatedCode("PRD"), root.name(), root.routeType(), root.routeVersion(), root.modelImageUrl(),
			root.specification(), material, root.id(), true, Instant.now()
		))));
	}

	@Transactional
	public ProductView updateModelImage(UUID id, String modelImageUrl) {
		ProductEntity product = products.findById(id)
			.orElseThrow(() -> DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在"));
		product.setModelImageUrl(trimToNull(modelImageUrl));
		return toView(product);
	}

	@Transactional
	public ProductView update(UUID id, UpdateProductCommand command) {
		ProductEntity product = products.findById(id)
			.orElseThrow(() -> DomainException.notFound("PRODUCT_NOT_FOUND", "浜у搧涓嶅瓨鍦?"));
		product.updateDetails(
			command.name().trim(),
			command.routeType(),
			command.routeVersion().trim(),
			trimToNull(command.specification()),
			trimToNull(command.material())
		);
		return toView(product);
	}

	private static String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private static String generatedCode(String prefix) {
		return prefix + "-" + LocalDate.now().toString().replace("-", "") + "-"
			+ UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
	}

	private static boolean sameMaterial(String left, String right) {
		return left != null && left.equalsIgnoreCase(right);
	}

	private static ProductView toView(ProductEntity product) {
		return new ProductView(
			product.id(),
			product.code(),
			product.name(),
			product.routeType(),
			product.routeVersion(),
			product.modelImageUrl(),
			product.specification(),
			product.material(),
			product.active(),
			product.createdAt()
		);
	}

	public record CreateProductCommand(
		String code,
		String name,
		RouteType routeType,
		String routeVersion,
		String modelImageUrl,
		String specification,
		String material
	) {
		public CreateProductCommand(String code, String name, RouteType routeType, String routeVersion, String modelImageUrl) {
			this(code, name, routeType, routeVersion, modelImageUrl, null, null);
		}
	}

	public record UpdateProductCommand(
		String name,
		RouteType routeType,
		String routeVersion,
		String specification,
		String material
	) {
	}

	public record ProductView(
		UUID id,
		String code,
		String name,
		RouteType routeType,
		String routeVersion,
		String modelImageUrl,
		String specification,
		String material,
		boolean active,
		Instant createdAt
	) {
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
