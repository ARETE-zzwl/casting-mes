package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.renyi.mes.common.DomainException;
import com.renyi.mes.execution.internal.ExecutionReportEntity;
import com.renyi.mes.execution.internal.ExecutionReportRepository;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.TaskStatus;
import com.renyi.mes.traceability.TraceabilityApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.application.name=trace-query-test")
@AutoConfigureMockMvc
@Import(TraceabilityQueryTests.QueryCountingConfiguration.class)
class TraceabilityQueryTests {

	@Autowired private MockMvc mvc;
	@Autowired private PlanningApplication planning;
	@Autowired private TraceabilityApplication traceability;
	@Autowired private ExecutionReportRepository reports;
	private static final ThreadLocal<int[]> QUERY_COUNT = new ThreadLocal<>();

	@Test
	void queryCountDoesNotGrowPerBatchOrTaskAndPreservesProductsReportsAndTimeline() throws Exception {
		UUID small = createOrder(1, true);
		UUID large = createOrder(10, true);
		int smallQueries = traceQueries(small, 2);
		int largeQueries = traceQueries(large, 20);
		System.out.printf("TRACE_QUERY_BUDGET: 2 batches=%d, 20 batches=%d%n", smallQueries, largeQueries);
		assertThat(smallQueries).as("query counter is active").isPositive();
		assertThat(largeQueries).as("bounded query growth across batches and tasks").isLessThanOrEqualTo(smallQueries + 2);
		assertThat(largeQueries).as("complete order trace SQL budget").isLessThanOrEqualTo(12);
	}

	@Test
	void draftOrderHasNoProductionAndUnknownOrderStillFails() throws Exception {
		UUID draft = createOrder(1, false);
		var trace = traceability.traceOrder(draft);
		assertThat(trace.workOrders()).isEmpty();
		assertThat(trace.timeline()).extracting(TraceabilityApplication.TimelineEvent::type).containsExactly("ORDER_CREATED");
		assertThatThrownBy(() -> traceability.traceOrder(UUID.randomUUID())).isInstanceOf(DomainException.class);
	}

	private int traceQueries(UUID orderId, int expectedBatches) {
		QUERY_COUNT.set(new int[1]);
		try {
			var trace = traceability.traceOrder(orderId);
			assertThat(trace.order().id()).isEqualTo(orderId);
			assertThat(trace.workOrders()).hasSize(expectedBatches);
			assertThat(trace.workOrders()).extracting(row -> row.workOrder().productMaterial()).containsOnly("CF8", "CF8M");
			for (var batch : trace.workOrders()) {
				assertThat(batch.workOrder().orderQuantity()).isEqualByComparingTo(BigDecimal.valueOf(expectedBatches * 5L));
				assertThat(batch.tasks()).hasSize(11);
				assertThat(batch.tasks()).extracting(row -> row.task().sequenceNo()).isSorted();
				assertThat(batch.tasks()).allSatisfy(row -> {
					assertThat(row.task().batchId()).isEqualTo(batch.workOrder().batchId());
					assertThat(row.task().orderId()).isEqualTo(orderId);
					assertThat(row.task().productMaterial()).isEqualTo(batch.workOrder().productMaterial());
					assertThat(row.reports()).allSatisfy(report -> assertThat(report.taskId()).isEqualTo(row.task().id()));
				});
				assertThat(batch.tasks().getFirst().reports()).extracting(row -> row.operatorCode()).containsExactly("W001", "W001");
				assertThat(batch.tasks().getFirst().reports()).extracting(row -> row.occurredAt()).isSorted();
			}
			assertThat(trace.timeline()).extracting(TraceabilityApplication.TimelineEvent::occurredAt).isSorted();
			assertThat(trace.timeline().stream().filter(event -> event.type().equals("PRODUCTION_REPORTED"))).hasSize(expectedBatches * 2);
			return QUERY_COUNT.get()[0];
		} finally {
			QUERY_COUNT.remove();
		}
	}

	private UUID createOrder(int batchesPerProduct, boolean release) throws Exception {
		String suffix = UUID.randomUUID().toString();
		String customer = create("/api/customers", "{\"code\":\"C-%s\",\"name\":\"Trace customer\"}".formatted(suffix));
		String first = product(suffix + "-A", "CF8");
		String second = product(suffix + "-B", "CF8M");
		UUID orderId = UUID.fromString(create("/api/orders", """
			{"orderNo":"TRACE-%s","customerId":"%s","priority":"NORMAL","lines":[
			{"productId":"%s","quantity":%d,"unit":"PCS"},
			{"productId":"%s","quantity":%d,"unit":"PCS"}]}
			""".formatted(suffix, customer, first, batchesPerProduct * 10, second, batchesPerProduct * 10)));
		if (!release) return orderId;
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId.toString());
		for (var workOrder : planning.workOrdersForOrder(orderId)) {
			planning.configureBatches(workOrder.id(), Collections.nCopies(batchesPerProduct, BigDecimal.TEN), "GM001");
		}
		for (var batch : planning.workOrdersForOrder(orderId)) {
			UUID taskId = planning.tasksForBatch(batch.batchId()).getFirst().id();
			// Deliberately insert out of time order to verify chronological trace output.
			for (int seconds : List.of(2, 1)) {
				reports.save(new ExecutionReportEntity(UUID.randomUUID(), UUID.randomUUID(), taskId,
					BigDecimal.ONE, BigDecimal.ZERO, "W001", BigDecimal.valueOf(seconds), BigDecimal.ZERO,
					TaskStatus.IN_PROGRESS, "TEST-DEVICE", "TEST-STATION", Instant.parse("2026-09-01T00:00:00Z").plusSeconds(seconds)));
			}
		}
		return orderId;
	}

	private String product(String code, String material) throws Exception {
		return create("/api/products", """
			{"code":"P-%s","name":"Trace valve","material":"%s","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(code, material));
	}

	private String create(String path, String body) throws Exception {
		return JsonPath.read(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class QueryCountingConfiguration {
		@Bean
		static BeanPostProcessor countQueries() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String name) {
					if (!(bean instanceof DataSource source)) return bean;
					return new DelegatingDataSource(source) {
						@Override public Connection getConnection() throws SQLException { return counted(super.getConnection(), Connection.class); }
						@Override public Connection getConnection(String user, String password) throws SQLException {
							return counted(super.getConnection(user, password), Connection.class);
						}
					};
				}
			};
		}

		@SuppressWarnings("unchecked")
		private static <T> T counted(T target, Class<T> type) {
			return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
				if (target instanceof Statement && method.getName().startsWith("execute") && QUERY_COUNT.get() != null) QUERY_COUNT.get()[0]++;
				try {
					Object result = method.invoke(target, args);
					if (result instanceof Statement) return counted(result, (Class<Object>) method.getReturnType());
					return result;
				} catch (InvocationTargetException exception) {
					throw exception.getCause();
				}
			}));
		}
	}
}
