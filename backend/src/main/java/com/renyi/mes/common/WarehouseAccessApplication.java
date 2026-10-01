package com.renyi.mes.common;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Centralized warehouse scope rules for the three independent warehouse ledgers. */
@Service
public class WarehouseAccessApplication {

	private final JdbcTemplate jdbc;
	private final BusinessAccess access;

	public WarehouseAccessApplication(JdbcTemplate jdbc, BusinessAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public WarehouseType typeOf(String warehouseCode) {
		String code = normalize(warehouseCode);
		if (code.startsWith("MOLD")) return WarehouseType.MOLD;
		if (code.startsWith("FG")) return WarehouseType.FINISHED_GOODS;
		return WarehouseType.RAW_MATERIAL;
	}

	@Transactional(readOnly = true)
	public void requireInventoryView(String employeeCode) {
		String actor = normalize(employeeCode);
		if (hasGlobalView(actor) || hasAnyPermission(actor,
			"INVENTORY_MANAGE", "MOLD_WAREHOUSE_MANAGE", "RAW_MATERIAL_WAREHOUSE_MANAGE",
			"FINISHED_GOODS_WAREHOUSE_MANAGE")) return;
		throw forbidden();
	}

	@Transactional(readOnly = true)
	public void requireRawMaterialManage(String employeeCode, String warehouseCode) {
		if (typeOf(warehouseCode) == WarehouseType.MOLD) {
			throw DomainException.badRequest("MOLD_CUSTODY_LEDGER_REQUIRED", "模具必须通过模具领用与归还台账管理");
		}
		if (typeOf(warehouseCode) == WarehouseType.FINISHED_GOODS) {
			throw DomainException.badRequest("FINISHED_GOODS_LOT_LEDGER_REQUIRED", "成品必须通过成品批次与交付台账管理");
		}
		String actor = normalize(employeeCode);
		if (hasPermission(actor, "INVENTORY_MANAGE") || hasPermission(actor, "RAW_MATERIAL_WAREHOUSE_MANAGE")) return;
		throw forbidden();
	}

	@Transactional(readOnly = true)
	public boolean canViewRawMaterials(String employeeCode) {
		String actor = normalize(employeeCode);
		return hasGlobalView(actor) || hasPermission(actor, "INVENTORY_MANAGE")
			|| hasPermission(actor, "RAW_MATERIAL_WAREHOUSE_MANAGE");
	}

	@Transactional(readOnly = true)
	public void requireFulfillmentView(String employeeCode) {
		String actor = normalize(employeeCode);
		if (hasGlobalView(actor) || hasAnyPermission(actor,
			"FINISHED_GOODS_WAREHOUSE_MANAGE", "FULFILLMENT_MANAGE", "LOGISTICS_MANAGE")) return;
		throw forbidden();
	}

	@Transactional(readOnly = true)
	public void requireFinishedGoodsReceipt(String employeeCode, String warehouseCode) {
		if (typeOf(warehouseCode) != WarehouseType.FINISHED_GOODS) {
			throw DomainException.badRequest("FINISHED_GOODS_WAREHOUSE_REQUIRED", "成品入库必须登记至成品仓");
		}
		String actor = normalize(employeeCode);
		if (hasPermission(actor, "FINISHED_GOODS_WAREHOUSE_MANAGE")) return;
		throw forbidden();
	}

	@Transactional(readOnly = true)
	public void requireFulfillmentManage(String employeeCode) {
		String actor = normalize(employeeCode);
		if (hasAnyPermission(actor, "FINISHED_GOODS_WAREHOUSE_MANAGE", "FULFILLMENT_MANAGE", "LOGISTICS_MANAGE")) return;
		throw forbidden();
	}

	private boolean hasGlobalView(String employeeCode) {
		return hasRole(employeeCode, "SYSTEM_ADMIN") || hasRole(employeeCode, "GENERAL_MANAGER");
	}

	private boolean hasRole(String employeeCode, String roleCode) {
		if (access.secured()) return employeeCode.equals(access.actor()) && access.role(roleCode);
		Integer count = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code = ?
			""", Integer.class, employeeCode, roleCode);
		return count != null && count > 0;
	}

	private boolean hasAnyPermission(String employeeCode, String... permissions) {
		for (String permission : permissions) {
			if (hasPermission(employeeCode, permission)) return true;
		}
		return false;
	}

	private boolean hasPermission(String employeeCode, String permission) {
		if (access.secured()) return employeeCode.equals(access.actor()) && access.permission(permission);
		Integer count = jdbc.queryForObject("""
			select count(*)
			from organization_member_role member_role
			join access_role_permission role_permission on role_permission.role_code = member_role.role_code
			where member_role.employee_code = ? and role_permission.permission_code = ?
			""", Integer.class, employeeCode, permission);
		return count != null && count > 0;
	}

	private static DomainException forbidden() {
		return DomainException.forbidden("WAREHOUSE_SCOPE_FORBIDDEN", "当前账户无权操作或查看该仓库台账");
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	public enum WarehouseType {
		MOLD, RAW_MATERIAL, FINISHED_GOODS
	}
}
