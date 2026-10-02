package com.renyi.mes.resource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
public class AssetQrApplication {

	private static final List<String> SUPPORTED_TYPES = List.of("MOLD", "CARRIER");
	private final JdbcTemplate jdbc;
	private final BusinessAccess access;

	public AssetQrApplication(JdbcTemplate jdbc, BusinessAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public List<LabelView> list() {
		return jdbc.query(select() + " order by q.created_at desc", AssetQrApplication::map);
	}

	@Transactional
	public List<LabelView> generate(GenerateCommand command) {
		String type = assetType(command.intendedAssetType());
		Instant now = Instant.now();
		return java.util.stream.IntStream.range(0, command.count()).mapToObj(index -> {
			UUID id = UUID.randomUUID();
			String token = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
			jdbc.update("""
				insert into asset_qr_label (id, label_no, qr_token, intended_asset_type, status, created_by, created_at)
				values (?, ?, ?, ?, 'UNBOUND', ?, ?)
				""", id, labelNo(token), token, type, normalize(command.createdBy()), Timestamp.from(now));
			return require(id, false);
		}).toList();
	}

	@Transactional
	public LabelView issueForAsset(UUID assetId, PrintCommand command) {
		Asset asset = requireAsset(assetId, true);
		String type = assetType(asset.assetType());
		LabelView label = jdbc.queryForList("select id from asset_qr_label where asset_id = ?", UUID.class, assetId)
			.stream().findFirst().map(id -> require(id, true)).orElseGet(() -> createBoundLabel(asset, type, command.actorCode()));
		return recordPrint(label.id(), command.actorCode());
	}

	@Transactional
	public LabelView reprint(UUID labelId, PrintCommand command) {
		require(labelId, true);
		return recordPrint(labelId, command.actorCode());
	}

	@Transactional
	public LabelView bind(BindCommand command) {
		String scanned = scannedLabelValue(command.scannedValue());
		// Use the same asset-then-label lock order as issueForAsset.
		Asset asset = requireAsset(command.assetId(), true);
		if ("MOLD".equals(asset.assetType())) access.requirePermission("MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE");
		LabelView label = jdbc.queryForList("select id from asset_qr_label where upper(qr_token) = ? or upper(label_no) = ?",
			UUID.class, scanned, scanned).stream().findFirst().map(id -> require(id, true))
			.orElseThrow(() -> DomainException.notFound("ASSET_QR_NOT_FOUND", "未找到该资产二维码"));
		if (!label.intendedAssetType().equals(asset.assetType())) {
			throw DomainException.conflict("ASSET_QR_TYPE_MISMATCH", "二维码类型与待绑定资产类型不一致");
		}
		if (label.assetId() != null && !label.assetId().equals(asset.id())) {
			throw DomainException.conflict("ASSET_QR_ALREADY_BOUND", "二维码已绑定到其他资产，遗失时请执行补打而非重新绑定");
		}
		LabelView existing = jdbc.query(select() + " where q.asset_id = ?", AssetQrApplication::map, asset.id())
			.stream().findFirst().orElse(null);
		if (existing != null && !existing.id().equals(label.id())) {
			throw DomainException.conflict("ASSET_QR_ASSET_ALREADY_BOUND", "该资产已有二维码，请对原二维码执行补打");
		}
		if (label.assetId() == null) {
			Instant now = Instant.now();
			jdbc.update("""
				update asset_qr_label set asset_id = ?, status = 'BOUND', bound_by = ?, bound_at = ? where id = ?
				""", asset.id(), normalize(command.boundBy()), Timestamp.from(now), label.id());
		}
		return require(label.id(), false);
	}

	@Transactional
	public EzcadVariableFile exportMoldVariableData(EzcadVariableExportCommand command) {
		List<LabelView> labels = recordMoldPreproductionExport(command.labelIds(), command.actorCode(), "EZCAD");
		StringBuilder content = new StringBuilder();
		for (LabelView label : labels) {
			content.append("MES:ASSET_QR:").append(label.qrToken()).append("\r\n");
		}
		return new EzcadVariableFile(
			"mes-mold-qr-ezcad-" + LocalDate.now().toString().replace("-", "") + ".txt",
			content.toString().getBytes(StandardCharsets.UTF_8));
	}

	@Transactional
	public List<LabelView> recordMoldDxfExport(DxfExportCommand command) {
		return recordMoldPreproductionExport(command.labelIds(), command.actorCode(), "DXF");
	}

	@Transactional
	public List<LabelView> recordMoldPngExport(PngExportCommand command) {
		return recordMoldPreproductionExport(command.labelIds(), command.actorCode(), "PNG");
	}

	private List<LabelView> recordMoldPreproductionExport(List<UUID> labelIds, String actorCode, String format) {
		if (labelIds == null || labelIds.isEmpty()) {
			throw DomainException.badRequest("ASSET_QR_EXPORT_EMPTY", "请至少选择一张待生产模具二维码");
		}
		List<LabelView> exported = new ArrayList<>();
		for (UUID labelId : new LinkedHashSet<>(labelIds)) {
			LabelView label = require(labelId, true);
			if (!"MOLD".equals(label.intendedAssetType())) {
				throw DomainException.conflict("ASSET_QR_" + format + "_TYPE_INVALID", format + " 输出仅支持模具二维码");
			}
			if (!"UNBOUND".equals(label.status())) {
				throw DomainException.conflict("ASSET_QR_" + format + "_BOUND", "已绑定资产的二维码请在资产台账中补打，不可作为预生产标签导出");
			}
			exported.add(recordPrint(label.id(), actorCode));
		}
		return exported;
	}

	private LabelView createBoundLabel(Asset asset, String type, String actorCode) {
		UUID id = UUID.randomUUID();
		String token = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
		Instant now = Instant.now();
		jdbc.update("""
			insert into asset_qr_label (id, label_no, qr_token, intended_asset_type, asset_id, status, bound_by, bound_at, created_by, created_at)
			values (?, ?, ?, ?, ?, 'BOUND', ?, ?, ?, ?)
			""", id, labelNo(token), token, type, asset.id(), normalize(actorCode), Timestamp.from(now), normalize(actorCode), Timestamp.from(now));
		return require(id, false);
	}

	private LabelView recordPrint(UUID labelId, String actorCode) {
		Instant now = Instant.now();
		jdbc.update("""
			update asset_qr_label set print_count = print_count + 1, last_printed_by = ?, last_printed_at = ? where id = ?
			""", normalize(actorCode), Timestamp.from(now), labelId);
		return require(labelId, false);
	}

	private LabelView require(UUID id, boolean lock) {
		// PostgreSQL cannot lock the nullable side of the display query's outer join.
		if (lock) jdbc.queryForList("select id from asset_qr_label where id = ? for update", UUID.class, id);
		return jdbc.query(select() + " where q.id = ?", AssetQrApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("ASSET_QR_NOT_FOUND", "资产二维码不存在"));
	}

	private Asset requireAsset(UUID id, boolean lock) {
		return jdbc.query("select id, asset_code, asset_name, asset_type from resource_asset where id = ?" + (lock ? " for update" : ""),
			(rs, row) -> new Asset(rs.getObject("id", UUID.class), rs.getString("asset_code"), rs.getString("asset_name"), rs.getString("asset_type")), id)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("RESOURCE_NOT_FOUND", "待绑定资产不存在"));
	}

	private static String select() {
		return """
			select q.*, a.asset_code, a.asset_name, a.asset_type
			from asset_qr_label q
			left join resource_asset a on a.id = q.asset_id
			""";
	}

	private static LabelView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		Timestamp lastPrintedAt = rs.getTimestamp("last_printed_at");
		Timestamp boundAt = rs.getTimestamp("bound_at");
		return new LabelView(rs.getObject("id", UUID.class), rs.getString("label_no"), rs.getString("qr_token"),
			rs.getString("intended_asset_type"), rs.getObject("asset_id", UUID.class), rs.getString("asset_code"),
			rs.getString("asset_name"), rs.getString("status"), rs.getInt("print_count"), rs.getString("last_printed_by"),
			lastPrintedAt == null ? null : lastPrintedAt.toInstant(), rs.getString("bound_by"),
			boundAt == null ? null : boundAt.toInstant(), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
	}

	private static String scannedLabelValue(String value) {
		String scanned = value == null ? "" : value.trim();
		if (scanned.regionMatches(true, 0, "MES:ASSET_QR:", 0, "MES:ASSET_QR:".length())) {
			scanned = scanned.substring("MES:ASSET_QR:".length()).trim();
		}
		if (scanned.isBlank()) throw DomainException.badRequest("ASSET_QR_FORMAT_INVALID", "请输入或扫描资产二维码标签");
		return scanned.toUpperCase(Locale.ROOT);
	}

	private static String assetType(String value) {
		String type = normalize(value);
		if (!SUPPORTED_TYPES.contains(type)) throw DomainException.badRequest("ASSET_QR_TYPE_INVALID", "资产二维码仅支持模具或周转车");
		return type;
	}

	private static String labelNo(String token) {
		return "QRL-" + LocalDate.now().toString().replace("-", "") + "-" + token.substring(0, 8);
	}

	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }

	private record Asset(UUID id, String code, String name, String assetType) { }

	public record GenerateCommand(String intendedAssetType, int count, String createdBy) { }
	public record PrintCommand(String actorCode) { }
	public record BindCommand(String scannedValue, UUID assetId, String boundBy) { }
	public record EzcadVariableExportCommand(List<UUID> labelIds, String actorCode) { }
	public record DxfExportCommand(List<UUID> labelIds, String actorCode) { }
	public record PngExportCommand(List<UUID> labelIds, String actorCode) { }
	public record EzcadVariableFile(String filename, byte[] content) { }
	public record LabelView(UUID id, String labelNo, String qrToken, String intendedAssetType, UUID assetId,
			String assetCode, String assetName, String status, int printCount, String lastPrintedBy, Instant lastPrintedAt,
			String boundBy, Instant boundAt, String createdBy, Instant createdAt) { }
}

@RestController
@RequestMapping("/api/asset-qr-codes")
class AssetQrController {
	private final AssetQrApplication labels;
	AssetQrController(AssetQrApplication labels) { this.labels = labels; }

	@GetMapping
	List<AssetQrApplication.LabelView> list() { return labels.list(); }

	@PostMapping("/batch")
	@ResponseStatus(HttpStatus.CREATED)
	List<AssetQrApplication.LabelView> generate(@Valid @RequestBody GenerateRequest request) {
		return labels.generate(new AssetQrApplication.GenerateCommand(request.intendedAssetType(), request.count(), request.createdBy()));
	}

	@PostMapping("/assets/{assetId}/issue")
	AssetQrApplication.LabelView issue(@PathVariable UUID assetId, @Valid @RequestBody PrintRequest request) {
		return labels.issueForAsset(assetId, new AssetQrApplication.PrintCommand(request.actorCode()));
	}

	@PostMapping("/{labelId}/reprint")
	AssetQrApplication.LabelView reprint(@PathVariable UUID labelId, @Valid @RequestBody PrintRequest request) {
		return labels.reprint(labelId, new AssetQrApplication.PrintCommand(request.actorCode()));
	}

	@PostMapping("/bind")
	AssetQrApplication.LabelView bind(@Valid @RequestBody BindRequest request) {
		return labels.bind(new AssetQrApplication.BindCommand(request.scannedValue(), request.assetId(), request.boundBy()));
	}

	@GetMapping(value = "/exports/ezcad-variable-data", produces = MediaType.TEXT_PLAIN_VALUE)
	ResponseEntity<byte[]> exportEzcadVariableData(@RequestParam List<UUID> labelIds,
			@RequestParam @NotBlank @Size(max = 64) String actorCode) {
		AssetQrApplication.EzcadVariableFile file = labels.exportMoldVariableData(
			new AssetQrApplication.EzcadVariableExportCommand(labelIds, actorCode));
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
			.contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
			.body(file.content());
	}

	@PostMapping("/exports/dxf")
	List<AssetQrApplication.LabelView> recordDxfExport(@Valid @RequestBody DxfExportRequest request) {
		return labels.recordMoldDxfExport(new AssetQrApplication.DxfExportCommand(request.labelIds(), request.actorCode()));
	}

	@PostMapping("/exports/png")
	List<AssetQrApplication.LabelView> recordPngExport(@Valid @RequestBody PngExportRequest request) {
		return labels.recordMoldPngExport(new AssetQrApplication.PngExportCommand(request.labelIds(), request.actorCode()));
	}

	record GenerateRequest(@NotBlank @Size(max = 24) String intendedAssetType, @NotNull @Min(1) @Max(500) Integer count,
			@NotBlank @Size(max = 64) String createdBy) { }
	record PrintRequest(@NotBlank @Size(max = 64) String actorCode) { }
	record BindRequest(@NotBlank @Size(max = 128) String scannedValue, @NotNull UUID assetId,
			@NotBlank @Size(max = 64) String boundBy) { }
	record DxfExportRequest(@NotNull @Size(min = 1, max = 6) List<UUID> labelIds,
			@NotBlank @Size(max = 64) String actorCode) { }
	record PngExportRequest(@NotNull @Size(min = 1, max = 6) List<UUID> labelIds,
			@NotBlank @Size(max = 64) String actorCode) { }
}
