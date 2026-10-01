package com.renyi.mes.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.renyi.mes.common.BusinessAccess;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
public class ReportingApplication {

	private final JdbcTemplate jdbc;
	private final BusinessAccess access;

	public ReportingApplication(JdbcTemplate jdbc, BusinessAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public DashboardView dashboard() {
		long orders = count("select count(*) from customer_order_header");
		long draftOrders = count("select count(*) from customer_order_header where status = 'DRAFT'");
		long workOrders = count("select count(*) from planning_work_order");
		long activeTasks = count("select count(*) from planning_task where status = 'IN_PROGRESS'");
		long readyTasks = count("select count(*) from planning_task where status = 'READY'");
		long rejectedInspections = count("select count(*) from quality_inspection where result = 'REJECTED'");
		long openOutsourcing = count("""
			select count(*) from outsourcing_order where status not in ('CLOSED', 'CANCELLED')
			""");
		long openProductionShortages = count("select count(*) from production_shortage_alert where status = 'OPEN'");
		boolean payrollVisible = access.role("SYSTEM_ADMIN", "GENERAL_MANAGER", "PRODUCTION_MANAGER", "FINANCE_REVIEWER");
		BigDecimal confirmedAmount = payrollVisible ? decimal("""
			select coalesce(sum(amount), 0) from piecework_entry where status = 'CONFIRMED'
			""") : null;

		List<StatusCount> taskStatuses = jdbc.query("""
			select status, count(*) item_count from planning_task group by status order by status
			""", (rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("item_count")));

		Map<LocalDate, DailyOutput> dailyMap = new LinkedHashMap<>();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		for (int offset = 6; offset >= 0; offset--) {
			LocalDate day = today.minusDays(offset);
			dailyMap.put(day, new DailyOutput(day, BigDecimal.ZERO, BigDecimal.ZERO));
		}
		jdbc.query("""
			select occurred_at, good_quantity, scrap_quantity
			from execution_report where occurred_at >= ? order by occurred_at
			""",
			rs -> {
				LocalDate day = rs.getTimestamp("occurred_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate();
				DailyOutput current = dailyMap.get(day);
				if (current != null) {
					dailyMap.put(day, new DailyOutput(
						day,
						current.goodQuantity().add(rs.getBigDecimal("good_quantity")),
						current.scrapQuantity().add(rs.getBigDecimal("scrap_quantity"))
					));
				}
			},
			Timestamp.from(Instant.now().minus(6, ChronoUnit.DAYS))
		);

		QualitySummary quality = jdbc.queryForObject("""
			select coalesce(sum(accepted_quantity), 0) accepted,
				coalesce(sum(rejected_quantity), 0) rejected
			from quality_inspection
			""", (rs, rowNum) -> new QualitySummary(
			rs.getBigDecimal("accepted"),
			rs.getBigDecimal("rejected")
		));

		List<NamedValue> warehouseSkus = jdbc.query("""
			select warehouse_code item_name, count(*) item_value
			from inventory_balance group by warehouse_code order by warehouse_code
			""", (rs, rowNum) -> new NamedValue(rs.getString("item_name"), rs.getBigDecimal("item_value")));

		List<NamedValue> outsourcingStatuses = jdbc.query("""
			select status item_name, count(*) item_value
			from outsourcing_order group by status order by status
			""", (rs, rowNum) -> new NamedValue(rs.getString("item_name"), rs.getBigDecimal("item_value")));

		List<WorkerAmount> workerAmounts = payrollVisible ? jdbc.query("""
			select worker_code, coalesce(sum(amount), 0) total_amount
			from piecework_entry where status = 'CONFIRMED'
			group by worker_code order by total_amount desc
			""", (rs, rowNum) -> new WorkerAmount(
			rs.getString("worker_code"),
			rs.getBigDecimal("total_amount")
		)) : List.of();

		List<RiskItem> risks = new ArrayList<>();
		if (draftOrders > 0) {
			risks.add(new RiskItem("WARNING", "订单等待审批", draftOrders, "/orders"));
		}
		if (readyTasks > 0) {
			risks.add(new RiskItem("INFO", "任务等待派工", readyTasks, "/tasks"));
		}
		if (rejectedInspections > 0) {
			risks.add(new RiskItem("CRITICAL", "质量异常待处置", rejectedInspections, "/quality"));
		}
		if (openOutsourcing > 0) {
			risks.add(new RiskItem("WARNING", "外协订单执行中", openOutsourcing, "/outsourcing"));
		}

		if (openProductionShortages > 0) {
			risks.add(new RiskItem("CRITICAL", "订单生产缺口预警", openProductionShortages, "/production-alerts"));
		}

		return new DashboardView(
			new Overview(
				orders,
				draftOrders,
				workOrders,
				activeTasks,
				readyTasks,
				rejectedInspections,
				openOutsourcing,
				confirmedAmount
			),
			taskStatuses,
			List.copyOf(dailyMap.values()),
			quality,
			warehouseSkus,
			outsourcingStatuses,
			workerAmounts,
			risks,
			Instant.now()
		);
	}

	private long count(String sql) {
		Long value = jdbc.queryForObject(sql, Long.class);
		return value == null ? 0 : value;
	}

	private BigDecimal decimal(String sql) {
		BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
		return value == null ? BigDecimal.ZERO : value;
	}

	public record Overview(
		long orders,
		long draftOrders,
		long workOrders,
		long activeTasks,
		long readyTasks,
		long rejectedInspections,
		long openOutsourcing,
		BigDecimal confirmedPieceworkAmount
	) {
	}

	public record StatusCount(String status, long count) {
	}

	public record DailyOutput(LocalDate date, BigDecimal goodQuantity, BigDecimal scrapQuantity) {
	}

	public record QualitySummary(BigDecimal acceptedQuantity, BigDecimal rejectedQuantity) {
	}

	public record NamedValue(String name, BigDecimal value) {
	}

	public record WorkerAmount(String workerCode, BigDecimal amount) {
	}

	public record RiskItem(String severity, String title, long count, String link) {
	}

	public record DashboardView(
		Overview overview,
		List<StatusCount> taskStatuses,
		List<DailyOutput> dailyOutput,
		QualitySummary quality,
		List<NamedValue> warehouseSkus,
		List<NamedValue> outsourcingStatuses,
		List<WorkerAmount> workerAmounts,
		List<RiskItem> risks,
		Instant generatedAt
	) {
	}
}

@RestController
@RequestMapping("/api/reporting")
class ReportingController {

	private final ReportingApplication reporting;

	ReportingController(ReportingApplication reporting) {
		this.reporting = reporting;
	}

	@GetMapping("/dashboard")
	ReportingApplication.DashboardView dashboard() {
		return reporting.dashboard();
	}
}
