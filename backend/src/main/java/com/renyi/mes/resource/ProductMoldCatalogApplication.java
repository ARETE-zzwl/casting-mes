package com.renyi.mes.resource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class ProductMoldCatalogApplication {

	private final JdbcTemplate jdbc;

	public ProductMoldCatalogApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<RelationView> list(UUID productId) {
		String filter = productId == null ? "" : " where r.product_id = ?";
		return jdbc.query(select() + filter + " order by p.code, a.asset_code", ProductMoldCatalogApplication::map, productId == null ? new Object[] {} : new Object[] {productId});
	}

	@Transactional
	public RelationView bind(BindCommand command) {
		requireProduct(command.productId());
		String type = jdbc.query("select asset_type from resource_asset where id = ?", (rs, row) -> rs.getString(1), command.moldAssetId())
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MOLD_NOT_FOUND", "模具不存在"));
		if (!"MOLD".equals(type)) throw DomainException.badRequest("PRODUCT_MOLD_TYPE_INVALID", "仅可绑定模具资产");
		Integer exists = jdbc.queryForObject("select count(*) from product_mold_relation where product_id = ? and mold_asset_id = ?", Integer.class,
			command.productId(), command.moldAssetId());
		if (exists != null && exists > 0) throw DomainException.conflict("PRODUCT_MOLD_RELATION_EXISTS", "该产品已绑定此模具");
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into product_mold_relation (id, product_id, mold_asset_id, note, created_by, created_at)
			values (?, ?, ?, ?, ?, ?)
			""", id, command.productId(), command.moldAssetId(), trim(command.note()), normalize(command.operatorCode()), Timestamp.from(Instant.now()));
		return require(id);
	}

	/** Records a proven order usage without replacing an existing manual relation. */
	@Transactional
	public void recordOrderUsage(UUID productId, UUID moldAssetId, String operatorCode) {
		requireProduct(productId);
		String type = jdbc.query("select asset_type from resource_asset where id = ?", (rs, row) -> rs.getString(1), moldAssetId)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MOLD_NOT_FOUND", "Mold asset was not found"));
		if (!"MOLD".equals(type)) throw DomainException.badRequest("PRODUCT_MOLD_TYPE_INVALID", "Only mold assets can be related to a product");
		Integer exists = jdbc.queryForObject("select count(*) from product_mold_relation where product_id = ? and mold_asset_id = ?", Integer.class,
			productId, moldAssetId);
		if (exists != null && exists > 0) return;
		jdbc.update("""
			insert into product_mold_relation (id, product_id, mold_asset_id, note, created_by, created_at)
			values (?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), productId, moldAssetId, "由订单选模自动沉淀", normalize(operatorCode), Timestamp.from(Instant.now()));
	}

	@Transactional
	public void unbind(UUID relationId) {
		if (jdbc.update("delete from product_mold_relation where id = ?", relationId) == 0) {
			throw DomainException.notFound("PRODUCT_MOLD_RELATION_NOT_FOUND", "产品模具关系不存在");
		}
	}

	private RelationView require(UUID id) {
		return jdbc.query(select() + " where r.id = ?", ProductMoldCatalogApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("PRODUCT_MOLD_RELATION_NOT_FOUND", "产品模具关系不存在"));
	}

	private void requireProduct(UUID productId) {
		Integer count = jdbc.queryForObject("select count(*) from engineering_product where id = ?", Integer.class, productId);
		if (count == null || count == 0) throw DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在");
	}

	private static String select() {
		return """
			select r.id, r.product_id, p.code as product_code, p.name as product_name, r.mold_asset_id,
				a.asset_code, a.asset_name, a.status as mold_status, a.location_code, a.ownership_type, a.owner_name,
				a.mold_image_url, r.note, r.created_by, r.created_at
			from product_mold_relation r
			join engineering_product p on p.id = r.product_id
			join resource_asset a on a.id = r.mold_asset_id
			""";
	}

	private static RelationView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new RelationView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("product_code"),
			rs.getString("product_name"), rs.getObject("mold_asset_id", UUID.class), rs.getString("asset_code"),
			rs.getString("asset_name"), rs.getString("mold_status"), rs.getString("location_code"), rs.getString("ownership_type"),
			rs.getString("owner_name"), rs.getString("mold_image_url"), rs.getString("note"), rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant());
	}

	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
	private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

	public record BindCommand(UUID productId, UUID moldAssetId, String note, String operatorCode) { }
	public record RelationView(UUID id, UUID productId, String productCode, String productName, UUID moldAssetId,
		String moldAssetCode, String moldAssetName, String moldStatus, String locationCode, String ownershipType,
		String ownerName, String moldImageUrl, String note, String createdBy, Instant createdAt) { }
}

@RestController
@RequestMapping("/api/product-molds")
class ProductMoldCatalogController {
	private final ProductMoldCatalogApplication catalog;

	ProductMoldCatalogController(ProductMoldCatalogApplication catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<ProductMoldCatalogApplication.RelationView> list(@RequestParam(required = false) UUID productId) {
		return catalog.list(productId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProductMoldCatalogApplication.RelationView bind(@Valid @RequestBody BindBody body) {
		return catalog.bind(new ProductMoldCatalogApplication.BindCommand(body.productId(), body.moldAssetId(), body.note(), body.operatorCode()));
	}

	@PostMapping("/{relationId}/unbind")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unbind(@PathVariable UUID relationId) {
		catalog.unbind(relationId);
	}

	record BindBody(@NotNull UUID productId, @NotNull UUID moldAssetId, @Size(max = 500) String note,
		@NotBlank @Size(max = 64) String operatorCode) { }
}
