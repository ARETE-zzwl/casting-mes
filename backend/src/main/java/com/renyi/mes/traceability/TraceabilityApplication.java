package com.renyi.mes.traceability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.ExecutionApplication.ReportView;
import com.renyi.mes.fulfillment.FulfillmentApplication;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import com.renyi.mes.planning.PlanningApplication.WorkOrderView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceabilityApplication {

	private final CustomerOrderApplication customerOrders;
	private final PlanningApplication planning;
	private final ExecutionApplication execution;
	private final FulfillmentApplication fulfillment;

	public TraceabilityApplication(
		CustomerOrderApplication customerOrders,
		PlanningApplication planning,
		ExecutionApplication execution,
		FulfillmentApplication fulfillment
	) {
		this.customerOrders = customerOrders;
		this.planning = planning;
		this.execution = execution;
		this.fulfillment = fulfillment;
	}

	@Transactional(readOnly = true)
	public OrderTrace traceOrder(UUID orderId) {
		CustomerOrderApplication.OrderView order = customerOrders.getOrder(orderId);
		List<WorkOrderTrace> workOrderTraces = planning.workOrdersForOrder(orderId).stream()
			.map(this::traceWorkOrder)
			.toList();
		List<TimelineEvent> timeline = buildTimeline(order, workOrderTraces);
		return new OrderTrace(order, workOrderTraces, timeline);
	}

	private WorkOrderTrace traceWorkOrder(WorkOrderView workOrder) {
		List<TaskTrace> taskTraces = planning.tasksForBatch(workOrder.batchId()).stream()
			.map(task -> new TaskTrace(task, execution.reportsForTask(task.id())))
			.toList();
		return new WorkOrderTrace(workOrder, taskTraces);
	}

	private List<TimelineEvent> buildTimeline(
		CustomerOrderApplication.OrderView order,
		List<WorkOrderTrace> workOrders
	) {
		List<TimelineEvent> events = new ArrayList<>();
		events.add(new TimelineEvent(order.createdAt(), "ORDER_CREATED", order.orderNo(), "订单已创建"));
		addIfPresent(events, order.approvedAt(), "ORDER_APPROVED", order.orderNo(), "订单已审批");
		addIfPresent(events, order.releasedAt(), "ORDER_RELEASED", order.orderNo(), "订单已放行");

		for (WorkOrderTrace workOrderTrace : workOrders) {
			WorkOrderView workOrder = workOrderTrace.workOrder();
			events.add(new TimelineEvent(
				workOrder.createdAt(),
				"WORK_ORDER_CREATED",
				workOrder.workOrderNo(),
				"工单与批次已生成"
			));
			for (TaskTrace taskTrace : workOrderTrace.tasks()) {
				TaskView task = taskTrace.task();
				addIfPresent(
					events,
					task.startedAt(),
					"TASK_STARTED",
					task.taskNo(),
					task.operationName() + "已开工"
				);
				for (ReportView report : taskTrace.reports()) {
					events.add(new TimelineEvent(
						report.occurredAt(),
						"PRODUCTION_REPORTED",
						task.taskNo(),
						"报工：合格 " + report.goodQuantity() + "，报废 " + report.scrapQuantity()
					));
				}
				addIfPresent(
					events,
					task.completedAt(),
					"TASK_COMPLETED",
					task.taskNo(),
					task.operationName() + "已完成"
				);
			}
		}
		fulfillment.eventsForOrder(order.id()).forEach(event -> events.add(new TimelineEvent(
			event.occurredAt(), event.type(), event.reference(), event.summary())));
		return events.stream()
			.sorted(Comparator.comparing(TimelineEvent::occurredAt))
			.toList();
	}

	private static void addIfPresent(
		List<TimelineEvent> events,
		Instant occurredAt,
		String type,
		String reference,
		String summary
	) {
		if (occurredAt != null) {
			events.add(new TimelineEvent(occurredAt, type, reference, summary));
		}
	}

	public record OrderTrace(
		CustomerOrderApplication.OrderView order,
		List<WorkOrderTrace> workOrders,
		List<TimelineEvent> timeline
	) {
	}

	public record WorkOrderTrace(WorkOrderView workOrder, List<TaskTrace> tasks) {
	}

	public record TaskTrace(TaskView task, List<ReportView> reports) {
	}

	public record TimelineEvent(Instant occurredAt, String type, String reference, String summary) {
	}
}
