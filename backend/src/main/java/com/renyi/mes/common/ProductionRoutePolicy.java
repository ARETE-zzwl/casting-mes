package com.renyi.mes.common;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

@Component
class ProductionRoutePolicy {
    private final BusinessAccess access;
    ProductionRoutePolicy(BusinessAccess access) { this.access = access; }

    boolean authorize(String route, HttpServletRequest request) {
        switch (route) {
            case "GET /api/notifications", "POST /api/notifications/{id}/read" -> { return true; }
            case "GET /api/products", "GET /api/routes" -> access.requirePermission("MASTERDATA_MANAGE", "ORDER_MANAGE", "TASK_DISPATCH", "MOLD_WAREHOUSE_MANAGE");
            case "POST /api/products", "POST /api/products/{productId}", "POST /api/products/{productId}/model-image" -> access.requirePermission("MASTERDATA_MANAGE");
            case "GET /api/customers" -> access.requirePermission("CUSTOMER_VIEW", "MASTERDATA_MANAGE");
            case "POST /api/customers", "POST /api/orders", "POST /api/orders/{orderId}/draft", "POST /api/orders/{orderId}/submit" -> access.requireRole("FRONT_DESK_CLERK");
            case "GET /api/orders", "GET /api/orders/{orderId}" -> access.requirePermission("ORDER_MANAGE", "PLANNING_VIEW", "MASTERDATA_MANAGE", "TRACE_VIEW", "PRINT_SENSITIVE_ORDER", "PIECEWORK_MANAGE");
            case "POST /api/orders/{orderId}/customer-manager-review", "POST /api/orders/{orderId}/customer-manager-return" -> access.requireRole("CUSTOMER_MANAGER");
            case "POST /api/orders/{orderId}/general-manager-review", "POST /api/orders/{orderId}/general-manager-return" -> access.requireRole("GENERAL_MANAGER");
            case "POST /api/orders/{orderId}/engineering-confirmation", "POST /api/orders/{orderId}/engineering-return" -> access.requireRole("PROCESS_ENGINEER");
            case "POST /api/orders/{orderId}/default-process-release", "POST /api/orders/{orderId}/engineering-reminders" -> access.requirePermission("TASK_DISPATCH");
            case "GET /api/process-card-templates" -> access.requirePermission("MASTERDATA_MANAGE", "TASK_DISPATCH");
            case "POST /api/process-card-templates", "POST /api/process-card-templates/{id}/publish", "POST /api/process-card-templates/ai-suggestion" -> access.requireRole("PROCESS_ENGINEER");
            case "GET /api/tasks", "GET /api/tasks/{taskId}", "GET /api/tasks/{taskId}/reports",
                 "GET /api/tasks/{taskId}/reports/upstream", "GET /api/tasks/{taskId}/handoff-receipt" -> access.requirePermission("WORKBENCH_VIEW", "TASK_DISPATCH", "PLANNING_VIEW", "ORDER_MANAGE", "MASTERDATA_MANAGE", "WORKSHOP_DISPLAY_VIEW", "QUALITY_MANAGE", "PIECEWORK_MANAGE", "PROCESS_CARD_VIEW", "PRINT_WORKSHOP_DOCUMENT");
            case "GET /api/tasks/claimable" -> {
                access.requirePermission("TASK_SELF_CLAIM");
                if (!access.actor().equals(request.getParameter("workerCode"))) throw BusinessAccess.forbidden();
            }
            case "POST /api/tasks/{taskId}/claim", "POST /api/tasks/{taskId}/claim-and-start" -> {
                access.requirePermission("TASK_SELF_CLAIM");
                return true; // Claim service verifies operation, line, assignment and predecessor.
            }
            case "POST /api/tasks/{taskId}/start" -> access.requirePermission("TASK_EXECUTE", "TASK_DISPATCH");
            case "POST /api/tasks/{taskId}/reports", "POST /api/tasks/{taskId}/tree-reports",
                 "POST /api/tasks/{taskId}/handoff-receipt", "POST /api/tasks/{taskId}/handoff-without-count", "POST /api/tasks/{taskId}/partial-flow" -> access.requirePermission("TASK_EXECUTE");
            case "GET /api/work-orders" -> access.requirePermission("PLANNING_VIEW");
            case "GET /api/planning/dispatch-recommendations",
                 "POST /api/tasks/{taskId}/assignment", "POST /api/tasks/{taskId}/wax-dispatch", "POST /api/tasks/{taskId}/shell-line",
                 "POST /api/tasks/{taskId}/mold-return", "POST /api/tasks/{taskId}/supervisor-reports", "POST /api/tasks/{taskId}/weights",
                 "POST /api/work-orders/{workOrderId}/batches", "POST /api/work-orders/{workOrderId}/production-quantity",
                 "POST /api/batches/{batchId}/launch" -> access.requirePermission("TASK_DISPATCH");
            case "GET /api/execution/reports", "GET /api/execution/reports/query" -> access.requirePermission("WORKBENCH_VIEW", "TASK_DISPATCH", "DASHBOARD_VIEW", "PIECEWORK_MANAGE");
            case "GET /api/sops/{operationCode}", "GET /api/report-form-profiles/{operationCode}" -> access.requirePermission("WORKBENCH_VIEW", "TASK_DISPATCH", "MASTERDATA_MANAGE");
            case "GET /api/documents/options", "POST /api/documents/previews", "GET /api/documents/audits" -> access.requirePermission("PROCESS_CARD_VIEW", "PRINT_WORKSHOP_DOCUMENT", "PRINT_SENSITIVE_ORDER", "PRINT_STATISTICS");
            case "GET /api/fulfillment/lots", "GET /api/fulfillment/lots/query", "GET /api/fulfillment/summary",
                 "GET /api/fulfillment/receipts", "GET /api/fulfillment/deliveries", "GET /api/fulfillment/deliveries/query",
                 "GET /api/fulfillment/deliveries/{id}", "POST /api/fulfillment/lots", "POST /api/fulfillment/deliveries",
                 "POST /api/fulfillment/deliveries/{id}/transitions" -> {
                // Fulfillment service enforces warehouse permissions; all viewer/writer codes are bound below.
                access.requirePermission("FINISHED_GOODS_WAREHOUSE_MANAGE", "FULFILLMENT_MANAGE", "LOGISTICS_MANAGE");
            }
            case "GET /api/resources", "GET /api/resources/query" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE", "ORDER_MOLD_SELECT", "TASK_DISPATCH", "CART_OPERATE", "WORKSHOP_DISPLAY_VIEW");
            case "GET /api/molds", "GET /api/molds/due", "GET /api/molds/{moldId}/maintenance-records" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE");
            case "GET /api/factory/mold-requests", "GET /api/factory/mold-requests/order-selections" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE", "ORDER_MOLD_SELECT", "TASK_DISPATCH");
            case "GET /api/factory/mold-requests/location-suggestions", "GET /api/factory/mold-requests/locations",
                 "GET /api/factory/mold-requests/external-movements", "GET /api/factory/mold-requests/maintenance-overdue" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE");
            case "POST /api/factory/mold-requests/receipts", "POST /api/resources/{assetId}/mold-image" -> access.requirePermission("MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE");
            case "POST /api/factory/mold-requests/locations", "POST /api/factory/mold-requests/locations/{locationCode}",
                 "POST /api/factory/mold-requests/external-movements", "POST /api/factory/mold-requests/external-movements/{id}/return",
                 "POST /api/factory/mold-requests/{id}/issue", "POST /api/factory/mold-requests/{id}/return" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE");
            default -> { if (!authorizeAdditional(route, request)) return false; }
        }
        @SuppressWarnings("unchecked")
        var variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables != null) {
            if (variables.containsKey("orderId")) {
                if (route.contains("/outsourcing/")) access.requireOutsource(UUID.fromString(variables.get("orderId")));
                else access.requireOrder(UUID.fromString(variables.get("orderId")));
            }
            if (variables.containsKey("taskId")) {
                UUID task = UUID.fromString(variables.get("taskId"));
                if (request.getMethod().equals("GET")) access.requireTask(task); else access.requireManageTask(task);
            }
            if (variables.containsKey("workOrderId")) access.requireWorkOrder(UUID.fromString(variables.get("workOrderId")));
            if (variables.containsKey("batchId")) access.requireBatch(UUID.fromString(variables.get("batchId")));
        }
        if (request.getMethod().equals("GET") && request.getParameterValues("taskId") != null) {
            for (String task : request.getParameterValues("taskId")) access.requireTask(UUID.fromString(task));
        }
        return true;
    }
    private boolean authorizeAdditional(String route, HttpServletRequest request) {
        switch (route) {
            case "GET /api/piecework/rates", "GET /api/piecework/entries", "GET /api/piecework/worker-payroll",
                 "GET /api/piecework/worker-output", "GET /api/piecework/exports/payroll",
                 "GET /api/piecework/exports/worker-payroll", "GET /api/piecework/exports/worker-output",
                 "POST /api/piecework/rates", "POST /api/piecework/entries", "POST /api/piecework/entries/{entryId}/confirmation" -> access.requirePermission("PIECEWORK_MANAGE", "LABOR_TIME_MANAGE");
            case "GET /api/furnace-batches", "POST /api/furnace-batches", "POST /api/furnace-batches/{id}/complete" -> access.requirePermission("TASK_DISPATCH", "TASK_EXECUTE");
            case "GET /api/outsourcing/suppliers" -> access.requirePermission("OUTSOURCING_MANAGE", "TASK_DISPATCH");
            case "POST /api/outsourcing/suppliers", "GET /api/outsourcing/orders", "POST /api/outsourcing/orders",
                 "GET /api/outsourcing/ready-production-tasks", "GET /api/outsourcing/orders/{orderId}/milestones",
                 "POST /api/outsourcing/orders/{orderId}/milestones" -> access.requirePermission("OUTSOURCING_MANAGE", "TASK_DISPATCH");
            case "GET /api/scans/resolve", "POST /api/scan-events", "GET /api/access/users" -> { return true; }
            case "POST /api/labor/time-entries" -> access.requirePermission("LABOR_TIME_MANAGE", "TASK_EXECUTE");
            case "POST /api/labor/paper-sheets", "GET /api/labor/paper-sheets",
                 "POST /api/labor/paper-sheets/{id}/approval", "POST /api/labor/paper-sheets/{id}/rejection" -> access.requirePermission("MANUAL_REPORT_REVIEW");
            case "POST /api/labor/shell-records", "POST /api/labor/manual-shell/scan-progress" -> access.requirePermission("SHELL_SCAN_REPORT", "TASK_DISPATCH");
            case "GET /api/labor/shell-records", "GET /api/labor/shell-records/summary" -> access.requirePermission("WORKBENCH_VIEW", "TASK_DISPATCH", "WORKSHOP_DISPLAY_VIEW");
            case "GET /api/cart-transfers", "GET /api/cart-transfers/ready-sources", "POST /api/cart-transfers/load", "POST /api/cart-transfers/{transferId}/receive" -> access.requirePermission("CART_OPERATE", "TASK_DISPATCH");
            case "GET /api/handoff-exceptions", "GET /api/handoff-exceptions/query" -> access.requirePermission("TRACE_VIEW", "TASK_DISPATCH");
            case "POST /api/handoff-exceptions/{sourceType}/{id}/assign", "POST /api/handoff-exceptions/{sourceType}/{id}/resolve" -> access.requirePermission("TASK_DISPATCH", "QUALITY_MANAGE");
            case "GET /api/production-alerts", "GET /api/manual-shell-drying-alerts" -> access.requirePermission("NOTIFICATION_VIEW");
            case "GET /api/post-treatment/decisions", "POST /api/post-treatment/decisions" -> access.requirePermission("TASK_DISPATCH");
            case "GET /api/trace/orders/{orderId}" -> access.requirePermission("TRACE_VIEW");
            case "GET /api/quality/inspections", "POST /api/quality/inspections", "GET /api/quality/inspections/{inspectionId}/dispositions",
                 "POST /api/quality/inspections/{inspectionId}/dispositions" -> access.requirePermission("QUALITY_MANAGE");
            case "GET /api/scheduling/queue" -> access.requirePermission("PLANNING_VIEW", "SCHEDULE_MANAGE");
            case "POST /api/scheduling/queue/{taskId}/rank", "POST /api/scheduling/queue/{taskId}/dispatch" -> access.requirePermission("SCHEDULE_MANAGE");
            case "GET /api/product-molds", "GET /api/customer-product-molds", "GET /api/customer-product-molds/history" -> access.requirePermission("ORDER_MOLD_SELECT", "MOLD_WAREHOUSE_MANAGE", "MASTERDATA_MANAGE", "TASK_DISPATCH");
            case "POST /api/product-molds", "POST /api/product-molds/{relationId}/unbind",
                 "POST /api/customer-product-molds", "POST /api/customer-product-molds/{relationId}/unbind" -> access.requirePermission("ORDER_MOLD_SELECT", "MOLD_WAREHOUSE_MANAGE", "MASTERDATA_MANAGE");
            case "POST /api/factory/mold-requests/order-selections", "POST /api/factory/mold-requests/order-mold-plans" -> access.requirePermission("ORDER_MOLD_SELECT");
            case "POST /api/factory/mold-requests", "POST /api/factory/mold-requests/tasks/{taskId}" -> access.requirePermission("MOLD_REQUEST", "TASK_DISPATCH");
            case "POST /api/factory/mold-requests/{id}/approval" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE");
            case "POST /api/molds/{moldId}/configuration", "POST /api/molds/{moldId}/maintenance-records",
                 "POST /api/molds/{moldId}/lock", "POST /api/molds/{moldId}/unlock" -> access.requirePermission("MOLD_WAREHOUSE_MANAGE");
            case "GET /api/asset-qr-codes" -> access.requirePermission("QR_MANAGE", "QR_BIND", "WORKSHOP_DISPLAY_VIEW");
            case "POST /api/asset-qr-codes/batch", "POST /api/asset-qr-codes/assets/{assetId}/issue",
                 "POST /api/asset-qr-codes/{labelId}/reprint", "GET /api/asset-qr-codes/exports/ezcad-variable-data",
                 "POST /api/asset-qr-codes/exports/dxf", "POST /api/asset-qr-codes/exports/png" -> access.requirePermission("QR_MANAGE", "MOLD_WAREHOUSE_MANAGE");
            case "POST /api/asset-qr-codes/bind" -> access.requirePermission("QR_BIND");
            case "GET /api/access/users/{employeeCode}", "GET /api/access/users/{employeeCode}/scopes" -> {
                if (!access.role("SYSTEM_ADMIN") && !access.actor().equals(((Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).get("employeeCode"))) throw BusinessAccess.forbidden();
            }
            case "GET /api/organization/members" -> access.requirePermission("ORDER_MANAGE", "TASK_DISPATCH", "PIECEWORK_MANAGE", "ACCESS_MANAGE");
            case "GET /api/organization/units" -> access.requirePermission("ACCESS_MANAGE", "MASTERDATA_MANAGE");
            case "GET /api/sales/performance" -> access.requirePermission("SALES_PERFORMANCE_VIEW");
            case "GET /api/customers/sensitive-requests" -> access.requirePermission("CUSTOMER_CHANGE_REQUEST", "CUSTOMER_CHANGE_APPROVE");
            case "POST /api/customers/sensitive-requests" -> access.requirePermission("CUSTOMER_CHANGE_REQUEST");
            case "POST /api/customers/sensitive-requests/{requestId}/approve", "POST /api/customers/sensitive-requests/{requestId}/reject" -> access.requirePermission("CUSTOMER_CHANGE_APPROVE");
            case "GET /api/reporting/dashboard" -> access.requireRole("GENERAL_MANAGER", "PRODUCTION_MANAGER", "GLOBAL_SCHEDULER", "FINANCE_REVIEWER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER");
            case "GET /api/sops", "POST /api/sops" -> access.requirePermission("SOP_MANAGE");
            case "GET /api/report-form-profiles", "POST /api/report-form-profiles" -> access.requireRole("PROCESS_ENGINEER");
            case "POST /api/orders/{orderId}/approval" -> access.requireRole("GENERAL_MANAGER", "CUSTOMER_MANAGER");
            case "POST /api/orders/{orderId}/release" -> access.requirePermission("TASK_DISPATCH");
            case "POST /api/ai-assistant/handoff/{sourceType}/{id}", "POST /api/ai-assistant/schedule-risk", "POST /api/ai-assistant/furnace/{id}" -> access.requirePermission("TASK_DISPATCH");
            case "POST /api/ai-assistant/sop-draft" -> access.requireRole("PROCESS_ENGINEER");
            case "GET /api/workflows/definitions" -> access.requirePermission("WORKFLOW_MANAGE", "CONFIG_MANAGE");
            case "GET /api/workflows", "POST /api/workflows", "POST /api/workflows/{requestId}/actions", "GET /api/workflows/{requestId}/actions" -> access.requirePermission("WORKFLOW_MANAGE");
            case "POST /api/notifications", "POST /api/workflows/definitions", "POST /api/resources", "GET /api/resources/occupations",
                 "POST /api/resources/{assetId}/occupations", "POST /api/resources/{assetId}/release",
                 "GET /api/access/roles", "GET /api/access/permissions", "POST /api/access/roles",
                 "POST /api/access/users/{employeeCode}/scopes", "POST /api/access/users/{employeeCode}/roles", "POST /api/access/roles/{roleCode}/permissions",
                 "POST /api/organization/units", "POST /api/organization/members", "POST /api/organization/members/{employeeCode}/maintenance",
                 "GET /api/configurations", "POST /api/configurations", "POST /api/configurations/{packageId}/publication", "POST /api/configurations/{packageId}/rollback",
                 "GET /api/integration/jobs", "POST /api/integration/jobs", "POST /api/integration/jobs/{id}/success", "POST /api/integration/jobs/{id}/failure", "POST /api/integration/jobs/{id}/retry",
                 "GET /api/operations/overview", "POST /api/access/users/{employeeCode}/password-reset", "GET /api/access/users/{employeeCode}/account" -> access.requireRole("SYSTEM_ADMIN");
            default -> { return false; }
        }
        return true;
    }
}
