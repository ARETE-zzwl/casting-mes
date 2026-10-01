package com.renyi.mes.common;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@Configuration(proxyBeanMethods = false)
@Profile("prod | secure")
class ProductionIdentityBoundary implements WebMvcConfigurer {
    private static final Set<String> REVIEWED_ROUTES = Set.of(
        "GET /api/inventory/balances", "GET /api/inventory/balances/query",
        "GET /api/inventory/movements", "GET /api/inventory/movements/query", "POST /api/inventory/movements",
        "POST /api/inventory/exports", "GET /api/inventory/exports/{id}", "GET /api/inventory/exports/{id}/download",
        "POST /api/files/upload", "GET /uploads/{category}/{name}", "HEAD /uploads/{category}/{name}");
    private final TrustedIdentity identity;
    private final ProductionRoutePolicy policy;

    ProductionIdentityBoundary(TrustedIdentity identity, ProductionRoutePolicy policy) { this.identity = identity; this.policy = policy; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getRequestURI().startsWith("/api/auth/")) return true;
                identity.employeeCode();
                String route = request.getMethod() + " " + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // Unreviewed endpoints remain closed; route permissions do not replace object scope checks.
                if (!identity.isAdministrator() && !REVIEWED_ROUTES.contains(route) && !policy.authorize(route, request)) {
                    throw DomainException.forbidden("PRODUCTION_ENDPOINT_RESTRICTED", "该接口尚未开放给生产环境普通账号");
                }
                for (String name : Set.of("viewerCode", "actorCode", "supervisorCode", "recipientCode")) {
                    String[] values = request.getParameterValues(name);
                    if (values != null) for (String value : values) identity.requireActor(value);
                }
                String header = request.getHeader("X-Operator-Code");
                if (header != null) identity.requireActor(header);
                response.setHeader("Cache-Control", "no-store");
                return true;
            }
        }).addPathPatterns("/api/**", "/uploads/**");
    }
}

@ControllerAdvice
@Profile("prod | secure")
class ProductionActorBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Set<String> ACTORS = Set.of("viewerCode", "actorCode", "supervisorCode", "engineerCode",
        "managerCode", "requesterCode", "requestedBy", "createdBy", "recordedBy", "enteredBy", "reviewedBy",
        "approvedBy", "confirmedBy", "completedBy", "decidedBy", "boundBy", "loadedBy", "receivedBy",
        "editorCode", "releasedBy", "publishedBy", "registeredBy", "selectedBy", "reviewerCode", "inspectorCode",
        "assignedBy", "resolvedBy", "performedBy", "warehouseOperatorCode");
    private final TrustedIdentity identity;
    private final AttachmentAccess attachments;
    private final BusinessAccess access;
    private final tools.jackson.databind.ObjectMapper json;

    ProductionActorBodyAdvice(TrustedIdentity identity, AttachmentAccess attachments, BusinessAccess access, tools.jackson.databind.ObjectMapper json) {
        this.identity = identity; this.attachments = attachments; this.access = access; this.json = json;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        boolean exportFilter = body.getClass().getName().equals("com.renyi.mes.inventory.InventoryExportApplication$ExportCommand");
        boolean handoffRecipient = body.getClass().getSimpleName().equals("HandoffRequest");
        if (body.getClass().isRecord()) {
            for (var component : body.getClass().getRecordComponents()) {
                if ((ACTORS.contains(component.getName()) && !(handoffRecipient && component.getName().equals("receivedBy")))
                        || component.getName().equals("operatorCode") && !exportFilter
                        || body.getClass().getName().equals("com.renyi.mes.notification.NotificationController$ReadRequest") && component.getName().equals("recipientCode")) {
                    try {
                        var accessor = component.getAccessor();
                        accessor.trySetAccessible();
                        String claimed = (String) accessor.invoke(body);
                        if (claimed == null && body.getClass().getName().equals("com.renyi.mes.piecework.PieceworkController$EntryRequest") && component.getName().equals("recordedBy")) {
                            var worker = body.getClass().getDeclaredMethod("workerCode");
                            worker.trySetAccessible();
                            claimed = (String) worker.invoke(body);
                        }
                        identity.requireActor(claimed);
                    } catch (ReflectiveOperationException exception) {
                        throw TrustedIdentity.denied();
                    }
                }
            }
        } else if (body instanceof Map<?, ?> fields) {
            fields.forEach((key, value) -> {
                if (ACTORS.contains(key) || "operatorCode".equals(key)) identity.requireActor((String) value);
            });
        }
        var fields = json.valueToTree(body);
        for (String name : Set.of("taskId", "sourceTaskId", "planningTaskId")) {
            var field = fields.get(name);
            if (field != null && !field.isNull()) {
                var id = java.util.UUID.fromString(field.asText());
                if (body.getClass().getName().equals("com.renyi.mes.quality.QualityController$InspectionRequest")
                        || body.getClass().getName().equals("com.renyi.mes.fulfillment.FulfillmentController$RegisterLotRequest")) access.requireTask(id);
                else access.requireManageTask(id);
            }
        }
        for (String name : Set.of("orderId", "orderLineId")) {
            var field = fields.get(name);
            if (field != null && !field.isNull()) {
                var id = java.util.UUID.fromString(field.asText());
                if (name.equals("orderId")) access.requireOrder(id);
                else access.requireOrderLine(id);
            }
        }
        attachments.validateReferences(body);
        return body;
    }
}
