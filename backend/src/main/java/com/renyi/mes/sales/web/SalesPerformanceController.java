package com.renyi.mes.sales.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.sales.SalesPerformanceApplication;
import com.renyi.mes.sales.SalesPerformanceApplication.SalesPerformanceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales")
class SalesPerformanceController {

	private final SalesPerformanceApplication performance;
	private final JdbcTemplate jdbc;
	private final BusinessAccess access;

	SalesPerformanceController(SalesPerformanceApplication performance, JdbcTemplate jdbc, BusinessAccess access) {
		this.performance = performance;
		this.jdbc = jdbc;
		this.access = access;
	}

	@GetMapping("/performance")
	SalesPerformanceView getPerformance(@RequestParam String viewerCode, @RequestParam String period,
			@RequestParam(required = false) String salesOwner) {
		String normalizedViewer = viewerCode.trim().toUpperCase();
		Integer permitted = jdbc.queryForObject("""
			select count(*) from organization_member_role member_role
			join access_role_permission role_permission on role_permission.role_code = member_role.role_code
			where member_role.employee_code = ? and role_permission.permission_code = 'SALES_PERFORMANCE_VIEW'
			""", Integer.class, normalizedViewer);
		if (permitted == null || permitted == 0) {
			throw DomainException.forbidden("SALES_PERFORMANCE_FORBIDDEN", "当前账号没有查看销售业绩的权限");
		}
		YearMonth requestedPeriod;
		try {
			requestedPeriod = YearMonth.parse(period);
		} catch (DateTimeParseException exception) {
			throw DomainException.badRequest("SALES_PERIOD_INVALID", "统计月份格式应为 YYYY-MM");
		}
		Integer globalViewer = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('CUSTOMER_MANAGER', 'GENERAL_MANAGER', 'SYSTEM_ADMIN')
			""", Integer.class, normalizedViewer);
		if (access.secured() ? access.role("CUSTOMER_MANAGER", "GENERAL_MANAGER", "SYSTEM_ADMIN") : globalViewer != null && globalViewer > 0) {
			return performance.performance(requestedPeriod, blankToNull(salesOwner), false);
		}
		String owner = jdbc.queryForObject("select name from organization_member where employee_code = ? and active = true",
			String.class, normalizedViewer);
		if (owner == null) throw DomainException.notFound("SALES_OWNER_NOT_FOUND", "未找到当前销售人员");
		return performance.performance(requestedPeriod, owner, true);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
