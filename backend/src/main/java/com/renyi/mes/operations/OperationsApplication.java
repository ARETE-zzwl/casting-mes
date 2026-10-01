package com.renyi.mes.operations;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
public class OperationsApplication {

	private final JdbcTemplate jdbc;

	public OperationsApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Overview overview() {
		return new Overview(
			count("select count(*) from integration_job where status = 'FAILED'"),
			count("select count(*) from notification_item where status = 'UNREAD'"),
			count("select count(*) from resource_asset where status = 'OCCUPIED'"),
			count("select count(*) from resource_asset where status = 'EXHAUSTED'"),
			count("select count(*) from workflow_request where status = 'PENDING'"),
			count("select count(*) from configuration_package where status = 'PUBLISHED'"),
			Instant.now()
		);
	}

	private long count(String sql) {
		Long value = jdbc.queryForObject(sql, Long.class);
		return value == null ? 0 : value;
	}

	public record Overview(long failedIntegrationJobs, long unreadNotifications,
			long occupiedResources, long exhaustedResources, long pendingWorkflows,
			long publishedConfigurations, Instant generatedAt) {
	}
}

@RestController
@RequestMapping("/api/operations")
class OperationsController {

	private final OperationsApplication operations;

	OperationsController(OperationsApplication operations) {
		this.operations = operations;
	}

	@GetMapping("/overview")
	OperationsApplication.Overview overview() {
		return operations.overview();
	}
}
