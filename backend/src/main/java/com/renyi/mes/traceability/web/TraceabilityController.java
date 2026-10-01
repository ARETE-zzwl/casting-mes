package com.renyi.mes.traceability.web;

import java.util.UUID;

import com.renyi.mes.traceability.TraceabilityApplication;
import com.renyi.mes.traceability.TraceabilityApplication.OrderTrace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace")
class TraceabilityController {

	private final TraceabilityApplication traceability;

	TraceabilityController(TraceabilityApplication traceability) {
		this.traceability = traceability;
	}

	@GetMapping("/orders/{orderId}")
	OrderTrace traceOrder(@PathVariable UUID orderId) {
		return traceability.traceOrder(orderId);
	}
}
