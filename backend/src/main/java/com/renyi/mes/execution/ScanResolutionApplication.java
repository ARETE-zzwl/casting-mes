package com.renyi.mes.execution;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import com.renyi.mes.resource.ResourceApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class ScanResolutionApplication {
	private final PlanningApplication planning;
	private final ResourceApplication resources;
	private final BusinessAccess access;

	public ScanResolutionApplication(PlanningApplication planning, ResourceApplication resources, BusinessAccess access) {
		this.planning = planning;
		this.resources = resources;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public Resolution resolve(String scannedValue) {
		String value = require(scannedValue);
		String normalized = value.toUpperCase(Locale.ROOT);
		if (normalized.startsWith("MES:TASK:")) return task(resolveUuid(value.substring("MES:TASK:".length()), "TASK_SCAN_INVALID"));
		if (normalized.startsWith("MES:BATCH:")) return batch(value.substring("MES:BATCH:".length()));

		UUID id = tryUuid(value);
		if (id != null) {
			var task = planning.findTaskForScan(id);
			if (task.isPresent()) return task(task.get());
			List<TaskView> tasks = planning.findBatchTasksForScan(id);
			if (!tasks.isEmpty()) return batch(tasks);
		}
		var task = planning.findTaskForScan(normalized);
		if (task.isPresent()) return task(task.get());
		List<TaskView> batchTasks = planning.findBatchTasksForScan(normalized);
		if (!batchTasks.isEmpty()) return batch(batchTasks);
		return resources.findAssetForScan(value).map(asset -> new Resolution("asset", null, asset, null, null, List.of()))
			.orElseThrow(() -> DomainException.notFound("SCAN_VALUE_NOT_FOUND", "未识别扫码内容，请核对任务、批次或资产二维码"));
	}

	private Resolution task(UUID taskId) {
		return planning.findTaskForScan(taskId).map(this::task)
			.orElseThrow(() -> DomainException.notFound("SCAN_TASK_NOT_FOUND", "未找到生产任务"));
	}

	private Resolution task(TaskView task) {
		if (!visible(task)) throw DomainException.notFound("SCAN_TASK_NOT_FOUND", "未找到授权范围内的生产任务");
		return new Resolution("task", task, null, null, null, List.of());
	}
	private boolean visible(TaskView task) {
		return access.canReadTask(task.id()) || access.permission("TASK_SELF_CLAIM") && planning.claimableTasks(access.actor()).stream().anyMatch(candidate -> candidate.id().equals(task.id()));
	}

	private Resolution batch(String rawValue) {
		UUID id = tryUuid(rawValue.trim());
		List<TaskView> tasks = id == null ? planning.findBatchTasksForScan(rawValue.trim()) : planning.findBatchTasksForScan(id);
		if (tasks.isEmpty()) throw DomainException.notFound("SCAN_BATCH_NOT_FOUND", "未找到生产批次");
		return batch(tasks);
	}

	private Resolution batch(List<TaskView> tasks) {
		tasks = tasks.stream().filter(this::visible).toList();
		if (tasks.isEmpty()) throw DomainException.notFound("SCAN_BATCH_NOT_FOUND", "未找到授权范围内的生产批次");
		TaskView first = tasks.getFirst();
		return new Resolution("batch", null, null, first.batchId(), first.batchNo(), tasks);
	}

	private static String require(String value) {
		if (value == null || value.isBlank()) throw DomainException.badRequest("SCAN_VALUE_REQUIRED", "请扫描或输入编码");
		return value.trim();
	}
	private static UUID resolveUuid(String value, String code) {
		UUID id = tryUuid(value.trim());
		if (id == null) throw DomainException.badRequest(code, "任务二维码格式无效");
		return id;
	}
	private static UUID tryUuid(String value) { try { return UUID.fromString(value.trim()); } catch (IllegalArgumentException error) { return null; } }

	public record Resolution(String kind, TaskView task, ResourceApplication.AssetView asset, UUID batchId, String batchNo, List<TaskView> tasks) { }
}

@RestController
@RequestMapping("/api/scans")
class ScanResolutionController {
	private final ScanResolutionApplication scans;
	ScanResolutionController(ScanResolutionApplication scans) { this.scans = scans; }
	@GetMapping("/resolve") ScanResolutionApplication.Resolution resolve(@RequestParam String value) { return scans.resolve(value); }
}
