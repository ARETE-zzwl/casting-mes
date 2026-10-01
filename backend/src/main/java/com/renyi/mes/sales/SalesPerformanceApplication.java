package com.renyi.mes.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.customerorder.CustomerOrderApplication.OrderLineView;
import com.renyi.mes.customerorder.CustomerOrderApplication.OrderView;
import com.renyi.mes.customerorder.OrderPriority;
import com.renyi.mes.customerorder.OrderStatus;
import com.renyi.mes.engineering.RouteType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesPerformanceApplication {

	private static final ZoneId FACTORY_ZONE = ZoneId.of("Asia/Shanghai");
	private static final String UNASSIGNED = "未分配";

	private final CustomerOrderApplication orders;

	public SalesPerformanceApplication(CustomerOrderApplication orders) {
		this.orders = orders;
	}

	@Transactional(readOnly = true)
	public SalesPerformanceView performance(YearMonth period, String ownerFilter, boolean selfOnly) {
		List<OrderView> allOrders = orders.listOrders(null);
		List<OrderView> scopedOrders = allOrders.stream()
			.filter(order -> ownerFilter == null || ownerFilter.equals(ownerOf(order)))
			.toList();
		List<OrderView> reviewedOrders = scopedOrders.stream()
			.filter(order -> reviewTime(order) != null)
			.filter(order -> YearMonth.from(reviewTime(order).atZone(FACTORY_ZONE)).equals(period))
			.toList();
		List<OrderView> pendingOrders = scopedOrders.stream()
			.filter(order -> reviewTime(order) == null)
			.filter(order -> YearMonth.from(order.createdAt().atZone(FACTORY_ZONE)).equals(period))
			.toList();

		Map<String, OwnerAccumulator> ownersBySales = new HashMap<>();
		List<RecentOrderView> recentOrders = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		int excludedLineCount = 0;
		Set<UUID> reviewedCustomers = new HashSet<>();
		for (OrderView order : reviewedOrders) {
			OrderAmount amount = amountOf(order.lines());
			OwnerAccumulator accumulator = ownersBySales.computeIfAbsent(ownerOf(order), ignored -> new OwnerAccumulator());
			accumulator.reviewedOrders++;
			accumulator.customerIds.add(order.customerId());
			accumulator.amount = accumulator.amount.add(amount.recognizedAmount());
			accumulator.excludedLineCount += amount.excludedLineCount();
			total = total.add(amount.recognizedAmount());
			excludedLineCount += amount.excludedLineCount();
			reviewedCustomers.add(order.customerId());
			recentOrders.add(new RecentOrderView(order.id(), order.orderNo(), order.customerName(), ownerOf(order),
				routeTypeOf(order.lines()), order.priority(), order.status(), order.createdAt(), reviewTime(order),
				amount.recognizedAmount(), amount.excludedLineCount()));
		}
		for (OrderView order : pendingOrders) {
			ownersBySales.computeIfAbsent(ownerOf(order), ignored -> new OwnerAccumulator()).pendingReviewOrders++;
		}

		List<OwnerPerformanceView> ranking = ownersBySales.entrySet().stream()
			.map(entry -> new OwnerPerformanceView(entry.getKey(), entry.getValue().reviewedOrders,
				entry.getValue().customerIds.size(), entry.getValue().amount, entry.getValue().pendingReviewOrders,
				entry.getValue().excludedLineCount))
			.sorted(Comparator.comparing(OwnerPerformanceView::recognizedAmount).reversed()
				.thenComparing(OwnerPerformanceView::salesOwner))
			.toList();
		List<MonthlyPerformanceView> trend = monthlyTrend(allOrders, ownerFilter, period);
		recentOrders.sort(Comparator.comparing(RecentOrderView::reviewedAt, Comparator.nullsLast(Comparator.reverseOrder())));
		return new SalesPerformanceView(period.toString(), selfOnly ? "SELF" : "ALL", Instant.now(),
			new SalesOverview(total, reviewedOrders.size(), reviewedCustomers.size(),
				reviewedOrders.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(reviewedOrders.size()), 2,
					java.math.RoundingMode.HALF_UP), pendingOrders.size(), excludedLineCount), ranking, trend,
			recentOrders.stream().limit(12).toList());
	}

	private List<MonthlyPerformanceView> monthlyTrend(List<OrderView> allOrders, String ownerFilter, YearMonth endPeriod) {
		Map<YearMonth, TrendAccumulator> trend = new HashMap<>();
		for (int offset = 5; offset >= 0; offset--) trend.put(endPeriod.minusMonths(offset), new TrendAccumulator());
		for (OrderView order : allOrders) {
			Instant reviewedAt = reviewTime(order);
			if (reviewedAt == null || (ownerFilter != null && !ownerFilter.equals(ownerOf(order)))) continue;
			TrendAccumulator accumulator = trend.get(YearMonth.from(reviewedAt.atZone(FACTORY_ZONE)));
			if (accumulator == null) continue;
			accumulator.reviewedOrders++;
			accumulator.amount = accumulator.amount.add(amountOf(order.lines()).recognizedAmount());
		}
		return trend.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> new MonthlyPerformanceView(entry.getKey().toString(), entry.getValue().amount,
				entry.getValue().reviewedOrders)).toList();
	}

	private static OrderAmount amountOf(List<OrderLineView> orderLines) {
		BigDecimal total = BigDecimal.ZERO;
		int excluded = 0;
		for (OrderLineView line : orderLines) {
			BigDecimal convertedQuantity = quantityForPriceUnit(line.orderedQuantity(), line.unit(), line.salesPriceUnit());
			if (line.salesUnitPrice() == null || convertedQuantity == null) {
				excluded++;
				continue;
			}
			total = total.add(convertedQuantity.multiply(line.salesUnitPrice()));
		}
		return new OrderAmount(total, excluded);
	}

	private static BigDecimal quantityForPriceUnit(BigDecimal quantity, String unit, String priceUnit) {
		if (quantity == null || unit == null || priceUnit == null) return null;
		String orderUnit = unit.trim().toUpperCase(Locale.ROOT);
		String salesUnit = priceUnit.trim().toUpperCase(Locale.ROOT);
		if (orderUnit.equals(salesUnit) || (isCountUnit(orderUnit) && isCountUnit(salesUnit))) return quantity;
		if ("KG".equals(orderUnit) && "TON".equals(salesUnit)) return quantity.divide(BigDecimal.valueOf(1000));
		if ("TON".equals(orderUnit) && "KG".equals(salesUnit)) return quantity.multiply(BigDecimal.valueOf(1000));
		return null;
	}

	private static boolean isCountUnit(String unit) {
		return "PCS".equals(unit) || "EA".equals(unit);
	}

	private static Instant reviewTime(OrderView order) {
		Instant customerReview = order.customerManagerReviewedAt();
		Instant generalReview = order.generalManagerReviewedAt();
		if (customerReview == null) return generalReview;
		if (generalReview == null) return customerReview;
		return customerReview.isAfter(generalReview) ? customerReview : generalReview;
	}

	private static String ownerOf(OrderView order) {
		return order.salesOwner() == null || order.salesOwner().isBlank() ? UNASSIGNED : order.salesOwner();
	}

	private static RouteType routeTypeOf(List<OrderLineView> orderLines) {
		return orderLines.isEmpty() ? null : orderLines.getFirst().routeType();
	}

	private static final class OwnerAccumulator {
		private BigDecimal amount = BigDecimal.ZERO;
		private final Set<UUID> customerIds = new HashSet<>();
		private int reviewedOrders;
		private int pendingReviewOrders;
		private int excludedLineCount;
	}

	private static final class TrendAccumulator {
		private BigDecimal amount = BigDecimal.ZERO;
		private int reviewedOrders;
	}

	private record OrderAmount(BigDecimal recognizedAmount, int excludedLineCount) { }

	public record SalesPerformanceView(String period, String scope, Instant generatedAt, SalesOverview overview,
		List<OwnerPerformanceView> ownerRanking, List<MonthlyPerformanceView> monthlyTrend,
		List<RecentOrderView> recentOrders) { }
	public record SalesOverview(BigDecimal recognizedAmount, int reviewedOrders, int reviewedCustomers,
		BigDecimal averageOrderAmount, int pendingReviewOrders, int excludedLineCount) { }
	public record OwnerPerformanceView(String salesOwner, int reviewedOrders, int customerCount,
		BigDecimal recognizedAmount, int pendingReviewOrders, int excludedLineCount) { }
	public record MonthlyPerformanceView(String month, BigDecimal recognizedAmount, int reviewedOrders) { }
	public record RecentOrderView(UUID orderId, String orderNo, String customerName, String salesOwner,
		RouteType routeType, OrderPriority priority, OrderStatus status, Instant createdAt, Instant reviewedAt,
		BigDecimal recognizedAmount, int excludedLineCount) { }
}
