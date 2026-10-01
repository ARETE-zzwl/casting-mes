package com.renyi.mes.resource;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class CustomerProductMoldCatalogApplication {

	private final JdbcTemplate jdbc;

	public CustomerProductMoldCatalogApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<RelationView> list(UUID customerId, UUID productId) {
		StringBuilder filter = new StringBuilder(" where 1 = 1");
		java.util.ArrayList<Object> params = new java.util.ArrayList<>();
		if (customerId != null) { filter.append(" and r.customer_id = ?"); params.add(customerId); }
		if (productId != null) { filter.append(" and r.product_id = ?"); params.add(productId); }
		return jdbc.query(select() + filter + " order by r.last_used_at desc, a.asset_code", CustomerProductMoldCatalogApplication::map, params.toArray());
	}

	/**
	 * Derives reusable customer-product knowledge directly from confirmed order facts.
	 * No secondary history table is needed, so an order is visible here as soon as it is created.
	 */
	@Transactional(readOnly = true)
	public List<OrderHistoryView> history(UUID customerId, UUID productId) {
		StringBuilder filter = new StringBuilder(" where 1 = 1");
		java.util.ArrayList<Object> params = new java.util.ArrayList<>();
		if (customerId != null) { filter.append(" and o.customer_id = ?"); params.add(customerId); }
		if (productId != null) { filter.append(" and l.product_id = ?"); params.add(productId); }
		return jdbc.query("""
			select o.customer_id, o.customer_code, o.customer_name, l.product_id, l.product_code, l.product_name,
				l.route_type, count(distinct o.id) as order_count, count(distinct r.mold_asset_id) as mold_count,
				max(o.created_at) as last_order_at, max(r.last_used_at) as last_mold_used_at
			from customer_order_header o
			join customer_order_line l on l.order_id = o.id
			left join customer_product_mold_relation r on r.customer_id = o.customer_id and r.product_id = l.product_id
			""" + filter + """
			 group by o.customer_id, o.customer_code, o.customer_name, l.product_id, l.product_code, l.product_name, l.route_type
			 order by last_order_at desc, o.customer_name, l.product_name
			""", (rs, row) -> new OrderHistoryView(
			rs.getObject("customer_id", UUID.class), rs.getString("customer_code"), rs.getString("customer_name"),
			rs.getObject("product_id", UUID.class), rs.getString("product_code"), rs.getString("product_name"),
			rs.getString("route_type"), rs.getLong("order_count"), rs.getLong("mold_count"),
			rs.getTimestamp("last_order_at").toInstant(),
			rs.getTimestamp("last_mold_used_at") == null ? null : rs.getTimestamp("last_mold_used_at").toInstant()), params.toArray());
	}

	@Transactional
	public RelationView bind(BindCommand command) {
		Integer customerCount = jdbc.queryForObject("select count(*) from customer_order_customer where id = ?", Integer.class, command.customerId());
		if (customerCount == null || customerCount == 0) throw DomainException.notFound("CUSTOMER_NOT_FOUND", "客户不存在");
		Integer productCount = jdbc.queryForObject("select count(*) from engineering_product where id = ?", Integer.class, command.productId());
		if (productCount == null || productCount == 0) throw DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在");
		String assetType = jdbc.query("select asset_type from resource_asset where id = ?", (rs, row) -> rs.getString(1), command.moldAssetId())
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MOLD_NOT_FOUND", "模具不存在"));
		if (!"MOLD".equals(assetType)) throw DomainException.badRequest("CUSTOMER_PRODUCT_MOLD_TYPE_INVALID", "仅可绑定模具资产");

		Instant now = Instant.now();
		String operatorCode = command.operatorCode().trim().toUpperCase(Locale.ROOT);
		int updated = jdbc.update("""
			update customer_product_mold_relation set created_by = ?, last_used_at = ?
			where customer_id = ? and product_id = ? and mold_asset_id = ?
			""", operatorCode, java.sql.Timestamp.from(now), command.customerId(), command.productId(), command.moldAssetId());
		if (updated == 0) {
			try {
				jdbc.update("""
					insert into customer_product_mold_relation (id, customer_id, product_id, mold_asset_id, created_by, created_at, last_used_at)
					values (?, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), command.customerId(), command.productId(), command.moldAssetId(), operatorCode,
					java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
			} catch (DuplicateKeyException ignored) {
				jdbc.update("""
					update customer_product_mold_relation set created_by = ?, last_used_at = ?
					where customer_id = ? and product_id = ? and mold_asset_id = ?
					""", operatorCode, java.sql.Timestamp.from(now), command.customerId(), command.productId(), command.moldAssetId());
			}
		}
		return list(command.customerId(), command.productId()).stream()
			.filter(relation -> relation.moldAssetId().equals(command.moldAssetId())).findFirst()
			.orElseThrow(() -> DomainException.conflict("CUSTOMER_PRODUCT_MOLD_BIND_FAILED", "客户产品模具绑定保存失败"));
	}

	@Transactional
	public void unbind(UUID relationId) {
		if (jdbc.update("delete from customer_product_mold_relation where id = ?", relationId) == 0) {
			throw DomainException.notFound("CUSTOMER_PRODUCT_MOLD_RELATION_NOT_FOUND", "客户产品模具关系不存在");
		}
	}

	private static String select() {
		return """
			select r.id, r.customer_id, c.code as customer_code, c.name as customer_name,
				r.product_id, p.code as product_code, p.name as product_name, r.mold_asset_id,
				a.asset_code, a.asset_name, a.status as mold_status, a.mold_custody_status,
				a.location_code, a.ownership_type, a.owner_name, a.mold_image_url,
				r.created_by, r.created_at, r.last_used_at
			from customer_product_mold_relation r
			join customer_order_customer c on c.id = r.customer_id
			join engineering_product p on p.id = r.product_id
			join resource_asset a on a.id = r.mold_asset_id
			""";
	}

	private static RelationView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new RelationView(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
			rs.getString("customer_code"), rs.getString("customer_name"), rs.getObject("product_id", UUID.class),
			rs.getString("product_code"), rs.getString("product_name"), rs.getObject("mold_asset_id", UUID.class),
			rs.getString("asset_code"), rs.getString("asset_name"), rs.getString("mold_status"),
			rs.getString("mold_custody_status"), rs.getString("location_code"), rs.getString("ownership_type"),
			rs.getString("owner_name"), rs.getString("mold_image_url"), rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_used_at").toInstant());
	}

	public record RelationView(UUID id, UUID customerId, String customerCode, String customerName, UUID productId,
		String productCode, String productName, UUID moldAssetId, String moldAssetCode, String moldAssetName,
		String moldStatus, String moldCustodyStatus, String locationCode, String ownershipType, String ownerName,
		String moldImageUrl, String createdBy, Instant createdAt, Instant lastUsedAt) { }

	public record OrderHistoryView(UUID customerId, String customerCode, String customerName, UUID productId,
		String productCode, String productName, String routeType, long orderCount, long moldCount,
		Instant lastOrderAt, Instant lastMoldUsedAt) { }

	public record BindCommand(UUID customerId, UUID productId, UUID moldAssetId, String operatorCode) { }
}

@RestController
@RequestMapping("/api/customer-product-molds")
class CustomerProductMoldCatalogController {
	private final CustomerProductMoldCatalogApplication catalog;

	CustomerProductMoldCatalogController(CustomerProductMoldCatalogApplication catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<CustomerProductMoldCatalogApplication.RelationView> list(@RequestParam(required = false) UUID customerId,
			@RequestParam(required = false) UUID productId) {
		return catalog.list(customerId, productId);
	}

	@GetMapping("/history")
	List<CustomerProductMoldCatalogApplication.OrderHistoryView> history(@RequestParam(required = false) UUID customerId,
			@RequestParam(required = false) UUID productId) {
		return catalog.history(customerId, productId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerProductMoldCatalogApplication.RelationView bind(@Valid @RequestBody BindBody body) {
		return catalog.bind(new CustomerProductMoldCatalogApplication.BindCommand(body.customerId(), body.productId(),
			body.moldAssetId(), body.operatorCode()));
	}

	@PostMapping("/{relationId}/unbind")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unbind(@PathVariable UUID relationId) {
		catalog.unbind(relationId);
	}

	record BindBody(@NotNull UUID customerId, @NotNull UUID productId, @NotNull UUID moldAssetId,
		@NotBlank @Size(max = 64) String operatorCode) { }
}
