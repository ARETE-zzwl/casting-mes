package com.renyi.mes.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.renyi.mes.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class SimulationDataMaintenanceApplication {

	private static final String CONFIRMATION = "CLEAR_TEST_DATA";
	private static final String PRODUCTION_CONFIRMATION = "CLEAR_PRODUCTION_TEST_DATA";

	private final JdbcTemplate jdbc;

	public SimulationDataMaintenanceApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public CleanupResult clearOperationalTestData(String confirmation) {
		if (!CONFIRMATION.equals(confirmation)) {
			throw DomainException.badRequest("CLEANUP_CONFIRMATION_REQUIRED", "请使用确认口令 CLEAR_TEST_DATA 执行测试数据清理");
		}

		Map<String, Integer> deleted = new LinkedHashMap<>();
		for (String table : List.of(
			"furnace_batch_task", "furnace_batch",
			"partial_flow_release", "production_shortage_alert", "operation_audit_event", "mold_maintenance_record",
			"delivery_event", "delivery_order", "finished_goods_lot", "quality_disposition", "quality_inspection",
			"piecework_entry", "execution_report", "production_handoff_event", "post_treatment_decision", "labor_time_entry",
			"manual_report_sheet", "shell_building_record", "production_cart_transfer", "schedule_queue_item", "order_mold_selection", "planning_task",
			"planning_batch", "planning_work_order", "customer_order_line", "customer_order_header",
			"simulation_role_operation_log", "simulation_run", "document_print_audit", "inventory_movement",
			"inventory_balance", "mold_external_movement", "mold_movement", "mold_request", "outsourcing_milestone", "outsourcing_order",
			"resource_occupation", "notification_item", "integration_job", "workflow_action", "workflow_request",
			"asset_qr_label", "product_process_card_template", "inventory_export_job"
		)) {
			deleted.put(table, jdbc.update("delete from " + table));
		}
		deleted.put("customer_order_customer", jdbc.update("delete from customer_order_customer"));
		deleted.put("engineering_product", jdbc.update("delete from engineering_product"));
		return new CleanupResult(deleted, deleted.values().stream().mapToInt(Integer::intValue).sum());
	}

	@Transactional
	public CleanupResult clearProductionTestData(String confirmation) {
		if (!PRODUCTION_CONFIRMATION.equals(confirmation)) {
			throw DomainException.badRequest("PRODUCTION_CLEANUP_CONFIRMATION_REQUIRED", "请使用确认口令 CLEAR_PRODUCTION_TEST_DATA 清理生产测试记录");
		}
		Map<String, Integer> deleted = new LinkedHashMap<>();
		for (String table : List.of(
			"furnace_batch_task", "furnace_batch", "partial_flow_release", "production_shortage_alert", "operation_audit_event",
			"delivery_event", "delivery_order", "finished_goods_lot", "quality_disposition", "quality_inspection",
			"piecework_entry", "execution_report", "production_handoff_event", "post_treatment_decision", "labor_time_entry",
			"manual_report_sheet", "shell_building_record", "production_cart_transfer", "schedule_queue_item", "mold_movement", "mold_request", "order_mold_selection",
			"planning_task", "planning_batch", "planning_work_order", "customer_order_line", "customer_order_header",
			"simulation_role_operation_log", "simulation_run", "document_print_audit", "inventory_movement",
			"inventory_balance", "mold_external_movement", "outsourcing_milestone", "outsourcing_order",
			"resource_occupation", "notification_item", "integration_job", "inventory_export_job"
		)) {
			deleted.put(table, jdbc.update("delete from " + table));
		}
		int resetMolds = jdbc.update("""
			update resource_asset set status = 'AVAILABLE', mold_custody_status = 'IN_STOCK', updated_at = current_timestamp, version = version + 1
			where asset_type = 'MOLD' and mold_lock_reason is null
			""");
		deleted.put("mold_assets_reset", resetMolds);
		return new CleanupResult(deleted, deleted.values().stream().mapToInt(Integer::intValue).sum());
	}

	public record CleanupResult(Map<String, Integer> deletedByTable, int totalDeleted) {
	}
}

@RestController
@RequestMapping("/api/simulations/data")
class SimulationDataMaintenanceController {

	private final SimulationDataMaintenanceApplication maintenance;

	SimulationDataMaintenanceController(SimulationDataMaintenanceApplication maintenance) {
		this.maintenance = maintenance;
	}

	@PostMapping("/cleanup")
	SimulationDataMaintenanceApplication.CleanupResult cleanup(@RequestParam String confirmation) {
		return maintenance.clearOperationalTestData(confirmation);
	}

	@PostMapping("/cleanup-production")
	SimulationDataMaintenanceApplication.CleanupResult cleanupProduction(@RequestParam String confirmation) {
		return maintenance.clearProductionTestData(confirmation);
	}
}
