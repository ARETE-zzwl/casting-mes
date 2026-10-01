package com.renyi.mes.customerorder.web;

import java.util.UUID;
import com.renyi.mes.common.BusinessAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@ControllerAdvice(basePackages = {"com.renyi.mes.customerorder.web", "com.renyi.mes.traceability.web"})
@Profile("prod | secure")
class OrderSecurityResponseAdvice implements ResponseBodyAdvice<Object> {
    private final BusinessAccess access;
    private final ObjectMapper json;
    OrderSecurityResponseAdvice(BusinessAccess access, ObjectMapper json) { this.access = access; this.json = json; }
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) return null;
        JsonNode tree = json.valueToTree(body);
        if (parameter.getContainingClass() == CustomerController.class && !access.commercial()) {
            if (tree.isArray()) for (JsonNode customer : tree) redactCustomer(customer);
            else redactCustomer(tree);
            return tree;
        }
        if (tree.isArray() && parameter.getContainingClass() == OrderController.class) {
            var visible = json.createArrayNode();
            for (JsonNode order : tree) if (access.canReadOrder(UUID.fromString(order.get("id").asText()))) visible.add(redact(order));
            return visible;
        }
        if (tree.has("order")) redact(tree.get("order"));
        return parameter.getContainingClass() == OrderController.class ? redact(tree) : tree;
    }
    private JsonNode redact(JsonNode order) {
        if (!access.commercial() && order instanceof ObjectNode object) {
            object.putNull("contractAttachmentUrl");
            for (JsonNode line : object.path("lines")) if (line instanceof ObjectNode fields) {
                fields.putNull("salesUnitPrice");
                fields.putNull("salesPriceUnit");
            }
        }
        return order;
    }
    private void redactCustomer(JsonNode customer) {
        if (customer instanceof ObjectNode fields) {
            fields.putNull("contactName"); fields.putNull("contactPhone"); fields.putNull("salesOwner");
        }
    }
}
