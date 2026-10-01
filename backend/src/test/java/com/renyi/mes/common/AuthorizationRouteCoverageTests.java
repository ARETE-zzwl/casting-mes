package com.renyi.mes.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
class AuthorizationRouteCoverageTests {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Test void everyBusinessEndpointHasAnExplicitPolicy() {
        BusinessAccess access = mock(BusinessAccess.class);
        when(access.actor()).thenReturn("TEST");
        when(access.role(any(String[].class))).thenReturn(true);
        var policy = new ProductionRoutePolicy(access);
        Set<String> serviceGuards = Set.of("GET /api/inventory/balances", "GET /api/inventory/balances/query",
            "GET /api/inventory/movements", "GET /api/inventory/movements/query", "POST /api/inventory/movements",
            "POST /api/inventory/exports", "GET /api/inventory/exports/{id}", "GET /api/inventory/exports/{id}/download", "POST /api/files/upload");
        var missing = new java.util.ArrayList<String>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/") || path.startsWith("/api/auth/") || path.startsWith("/api/simulations/")) continue;
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    String route = method + " " + path;
                    if (serviceGuards.contains(route)) continue;
                    var request = new MockHttpServletRequest(method.name(), path);
                    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, new HashMap<String, String>());
                    request.addParameter("workerCode", "TEST");
                    if (!policy.authorize(route, request)) missing.add(route);
                }
            }
        });
        assertThat(missing).as("New routes must be reviewed, never silently opened").isEmpty();
        assertThat(policy.authorize("POST /api/unknown-business-action", new MockHttpServletRequest())).isFalse();
    }
}
