package com.renyi.mes.simulation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateCustomerCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateOrderCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateOrderLineCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.CustomerView;
import com.renyi.mes.customerorder.CustomerOrderApplication.EngineeringConfirmationCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.OrderView;
import com.renyi.mes.customerorder.OrderPriority;
import com.renyi.mes.engineering.ProductApplication;
import com.renyi.mes.engineering.ProductApplication.CreateProductCommand;
import com.renyi.mes.engineering.ProductApplication.ProductView;
import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.FurnaceBatchApplication;
import com.renyi.mes.execution.HandoffApplication;
import com.renyi.mes.execution.HandoffExceptionApplication;
import com.renyi.mes.fulfillment.FulfillmentApplication;
import com.renyi.mes.labor.LaborOperationsApplication;
import com.renyi.mes.outsourcing.OutsourcingApplication;
import com.renyi.mes.piecework.PieceworkApplication;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PostTreatmentApplication;
import com.renyi.mes.planning.WaxDispatchApplication;
import com.renyi.mes.quality.QualityApplication;
import com.renyi.mes.resource.MoldApplication;
import com.renyi.mes.resource.CartTransferApplication;
import com.renyi.mes.resource.ResourceApplication;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class MonthlyProductionSimulationApplication {

	private static final BigDecimal QUANTITY = BigDecimal.valueOf(20);

	private final JdbcTemplate jdbc;
	private final ProductApplication products;
	private final CustomerOrderApplication orders;
	private final PlanningApplication planning;
	private final WaxDispatchApplication waxDispatch;
	private final ExecutionApplication execution;
	private final QualityApplication quality;
	private final FulfillmentApplication fulfillment;
	private final LaborOperationsApplication labor;
	private final ResourceApplication resources;
	private final MoldApplication molds;
	private final CartTransferApplication carts;
	private final FurnaceBatchApplication furnaceBatches;
	private final HandoffApplication handoffs;
	private final HandoffExceptionApplication handoffExceptions;
	private final OutsourcingApplication outsourcing;
	private final PieceworkApplication piecework;
	private final PostTreatmentApplication postTreatment;
	private final EntityManager entityManager;

	public MonthlyProductionSimulationApplication(JdbcTemplate jdbc, ProductApplication products,
			CustomerOrderApplication orders, PlanningApplication planning, ExecutionApplication execution,
			QualityApplication quality, FulfillmentApplication fulfillment, LaborOperationsApplication labor,
			ResourceApplication resources, MoldApplication molds, CartTransferApplication carts,
			FurnaceBatchApplication furnaceBatches, HandoffApplication handoffs, HandoffExceptionApplication handoffExceptions,
			OutsourcingApplication outsourcing, WaxDispatchApplication waxDispatch, PieceworkApplication piecework,
			PostTreatmentApplication postTreatment,
			EntityManager entityManager) {
		this.jdbc = jdbc;
		this.products = products;
		this.orders = orders;
		this.planning = planning;
		this.waxDispatch = waxDispatch;
		this.execution = execution;
		this.quality = quality;
		this.fulfillment = fulfillment;
		this.labor = labor;
		this.resources = resources;
		this.molds = molds;
		this.carts = carts;
		this.furnaceBatches = furnaceBatches;
		this.handoffs = handoffs;
		this.handoffExceptions = handoffExceptions;
		this.outsourcing = outsourcing;
		this.piecework = piecework;
		this.postTreatment = postTreatment;
		this.entityManager = entityManager;
	}

	@Transactional
	public SimulationRunView run(YearMonth month) {
		return run(month, false);
	}

	private SimulationRunView run(YearMonth month, boolean includeInHouseFinishing) {
		String code = "MONTHLY-" + month;
		SimulationRunView existing = findRun(code);
		if (existing != null) {
			ensureWorkshopStageRecords(existing.id(), existing.completedOrderId(), month);
			normalizeFulfillmentTimeline(existing.completedOrderId(), month);
			return findRun(code);
		}

		UUID runId = UUID.randomUUID();
		LocalDate start = month.atDay(1);
		Instant createdAt = start.atTime(8, 0).toInstant(ZoneOffset.UTC);
		jdbc.update("insert into simulation_run (id, simulation_code, month_start, created_at) values (?, ?, ?, ?)",
			runId, code, start, Timestamp.from(createdAt));

		String suffix = month.toString().replace("-", "");
		Map<RouteType, List<ProductView>> productByRoute = Map.of(
			RouteType.MID_TEMP_WAX, List.of(
				createProduct("VLV-CF8-DN50", "CF8不锈钢法兰阀体", RouteType.MID_TEMP_WAX, "DN50 x PN16", "CF8", "/product-models/valve-body.png"),
				createProduct("IMP-304-180", "304不锈钢闭式叶轮", RouteType.MID_TEMP_WAX, "Ø180 x 48 mm", "304", "/product-models/impeller.png")
			),
			RouteType.LOW_TEMP_WAX, List.of(
				createProduct("BRK-304-120", "304管夹安装支架", RouteType.LOW_TEMP_WAX, "120 x 80 x 12 mm", "304", "/product-models/mounting-bracket.png"),
				createProduct("FLG-CF8-DN80", "CF8不锈钢管法兰", RouteType.LOW_TEMP_WAX, "DN80 x PN16", "CF8", "/product-models/pipe-flange.png")
			),
			RouteType.SAND_OUTSOURCE, List.of(
				createProduct("PCS-HT250-100", "HT250离心泵壳", RouteType.SAND_OUTSOURCE, "DN100 inlet", "HT250", "/product-models/pump-casing.png"),
				createProduct("GBX-ZG270-220", "铸钢减速机箱体", RouteType.SAND_OUTSOURCE, "220 x 180 x 160 mm", "ZG270-500", "/product-models/gearbox-housing.png")
			)
		);
		List<CustomerView> customers = List.of(
			createCustomer("MSC-" + suffix + "-01", "华东泵阀装备有限公司"),
			createCustomer("MSC-" + suffix + "-02", "华南流体控制有限公司"),
			createCustomer("MSC-" + suffix + "-03", "西部工程装备有限公司"),
			createCustomer("MSC-" + suffix + "-04", "北方矿山机械有限公司"),
			createCustomer("MSC-" + suffix + "-05", "长三角精密制造有限公司")
		);
		registerCarrier("CART-MID-01", "中温蜡周转车 01", "MID-WAX");
		registerCarrier("CART-LOW-01", "低温蜡周转车 01", "LOW-WAX");
		registerCarrier("CART-SAND-01", "砂型外协周转车 01", "SAND-OUTSOURCE");

		for (int index = 0; index < 24; index++) {
			RouteType route = switch (index % 3) {
				case 0 -> RouteType.MID_TEMP_WAX;
				case 1 -> RouteType.LOW_TEMP_WAX;
				default -> RouteType.SAND_OUTSOURCE;
			};
			LocalDate day = start.plusDays(Math.min(index, month.lengthOfMonth() - 1));
			ProductView product = productByRoute.get(route).get(index % 2);
			OrderView order = createReleasedOrder("MSO-" + suffix + "-" + String.format("%02d", index + 1),
				customers.get(index % customers.size()), product, index % 8 == 0 ? OrderPriority.URGENT : OrderPriority.NORMAL,
				day.plusDays(12), BigDecimal.valueOf((index % 5 + 1) * 10L));
			backdateOrder(order.id(), day);
			if (index < 8) {
				completeTasks(order, day, false, null, null);
			}
			else if (index < 16) {
				completeTasks(order, day, false, 3, null);
			}
		}

		OrderView deliveredOrder = createReleasedOrder("MSO-" + suffix + "-DELIVERY", customers.getFirst(),
			productByRoute.get(RouteType.MID_TEMP_WAX).getFirst(), OrderPriority.URGENT, month.atEndOfMonth(), QUANTITY);
		backdateOrder(deliveredOrder.id(), month.atDay(24));
		log(runId, month.atDay(24), "E001", "PROCESS_ENGINEER", "engineering", "PRODUCT_PROCESS_CARD", deliveredOrder.orderNo(), "创建产品工艺路线并发布电子工艺卡");
		log(runId, month.atDay(24), "S001", "PRODUCTION_MANAGER", "customerorder", "ORDER_APPROVAL_RELEASE", deliveredOrder.orderNo(), "审核客户订单并释放工单和生产批次");
		log(runId, month.atDay(24), "P001", "GLOBAL_SCHEDULER", "planning", "SCHEDULE_RECOMMENDATION", deliveredOrder.orderNo(), "根据急单优先级将订单排入中温蜡生产线");
		log(runId, month.atDay(24), "K001", "RAW_MATERIAL_KEEPER", "inventory", "RAW_MATERIAL_PREPARE", deliveredOrder.orderNo(), "核对原材料库存并完成投料备料");

		List<PlanningApplication.TaskView> completedTasks = completeTasks(deliveredOrder, month.atDay(25), true, null, runId, false,
			includeInHouseFinishing);
		PlanningApplication.TaskView finalTask = completedTasks.getLast();
		QualityApplication.InspectionResult inspection = quality.inspect(new QualityApplication.InspectionCommand(
			UUID.randomUUID(), finalTask.id(), finalTask.goodQuantity(), finalTask.goodQuantity(), BigDecimal.ZERO,
			null, "Q001", "月度仿真终检合格"));
		backdate("quality_inspection", inspection.inspection().id(), month.atDay(28));
		log(runId, month.atDay(28), "Q001", "QUALITY_INSPECTOR", "quality", "FINAL_INSPECTION", deliveredOrder.orderNo(), "执行终检，20 件合格并放行成品入库");

		FulfillmentApplication.LotSubmission lot = fulfillment.registerLot(new FulfillmentApplication.RegisterLotCommand(
			UUID.randomUUID(), finalTask.id(), finalTask.goodQuantity(), "FG-01", "G001"));
		log(runId, month.atDay(28), "G001", "FINISHED_GOODS_KEEPER", "fulfillment", "FINISHED_GOODS_RECEIPT", deliveredOrder.orderNo(), "登记成品批次 " + lot.lot().lotNo() + " 入成品仓");
		FulfillmentApplication.DeliveryView delivery = fulfillment.createDelivery(new FulfillmentApplication.CreateDeliveryCommand(
			deliveredOrder.id(), lot.lot().id(), finalTask.goodQuantity(), "华东设备收货组", "江苏省苏州市工业园区 1 号仓", "G001"));
		log(runId, month.atDay(29), "G001", "FINISHED_GOODS_KEEPER", "fulfillment", "DELIVERY_CREATE_PICK", deliveredOrder.orderNo(), "创建交付单 " + delivery.deliveryNo() + " 并完成备货");
		FulfillmentApplication.DeliveryView picked = fulfillment.transition(delivery.id(),
			new FulfillmentApplication.TransitionCommand("PICKED", "G001", null, null, "按订单备货完成"));
		FulfillmentApplication.DeliveryView shipped = fulfillment.transition(picked.id(),
			new FulfillmentApplication.TransitionCommand("SHIPPED", "G001", "顺丰工业物流", "SF-" + suffix, "承运商取货"));
		FulfillmentApplication.DeliveryView delivered = fulfillment.transition(shipped.id(),
			new FulfillmentApplication.TransitionCommand("DELIVERED", "G001", null, null, "客户签收确认"));
		backdateFulfillment(lot.lot().id(), delivered.id(), month);
		log(runId, month.atEndOfMonth(), "G001", "FINISHED_GOODS_KEEPER", "fulfillment", "LOGISTICS_SHIP_DELIVER", deliveredOrder.orderNo(), "登记承运商、运单并确认客户签收");

		jdbc.update("update simulation_run set completed_order_id = ?, completed_order_no = ? where id = ?",
			deliveredOrder.id(), deliveredOrder.orderNo(), runId);
		return new SimulationRunView(runId, code, month.atDay(1), deliveredOrder.id(), deliveredOrder.orderNo(),
			delivered.deliveryNo(), countOrders(code), logs(runId));
	}

	@Transactional
	public SimulationRunView resetAndRun(YearMonth month) {
		clearBusinessData();
		entityManager.clear();
		return run(month);
	}

	@Transactional
	public PayrollSimulationRunView resetAndRunPayroll(YearMonth month) {
		clearBusinessData();
		entityManager.clear();
		SimulationRunView production = run(month, true);
		List<PlanningApplication.TaskView> tasks = planning.listTasks(null, null, production.completedOrderId()).stream()
			.filter(task -> task.routeType() == RouteType.MID_TEMP_WAX)
			.filter(task -> List.of("WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY", "OPTIONAL_FINISHING").contains(task.operationCode()))
			.filter(task -> task.status() == com.renyi.mes.planning.TaskStatus.COMPLETED)
			.sorted(Comparator.comparingInt(PlanningApplication.TaskView::sequenceNo))
			.toList();

		BigDecimal confirmedAmount = BigDecimal.ZERO;
		for (PlanningApplication.TaskView task : tasks) {
			configurePayrollTask(task, workerFor(task));
			PlanningApplication.TaskView settledTask = planning.getTask(task.id());
			String supervisor = supervisorFor(settledTask);
			String unit = payrollUnit(settledTask.operationCode());
			ensurePayrollRate(settledTask, supervisor, unit, month);
			BigDecimal quantity = payrollQuantity(settledTask, unit);
			PieceworkApplication.EntryResult created = piecework.createEntry(new PieceworkApplication.EntryCommand(
				UUID.randomUUID(), settledTask.id(), settledTask.assignedTo(), supervisor, quantity));
			PieceworkApplication.EntryView confirmed = piecework.confirm(created.entry().id(), "PM01");
			confirmedAmount = confirmedAmount.add(confirmed.amount());
			log(production.id(), settledTask.completedAt().atZone(ZoneOffset.UTC).toLocalDate(), supervisor,
				supervisor.equals("FS001") ? "FINISHING_SUPERVISOR" : "MID_WAX_SUPERVISOR", "piecework",
				"PIECEWORK_BACKFILL_CONFIRM", settledTask.taskNo(), "主管代录并确认计件：" + quantity + " " + unit + "，金额 " + confirmed.amount());
		}
		return new PayrollSimulationRunView(production, month, tasks.size(), confirmedAmount);
	}

	private void configurePayrollTask(PlanningApplication.TaskView task, String simulatedWorker) {
		String mode = switch (task.operationCode()) {
			case "TREE_ASSEMBLY" -> "PIECE_TREE";
			case "OPTIONAL_FINISHING" -> "PIECE_KG";
			default -> "PIECE_PCS";
		};
		String unit = payrollUnit(task.operationCode());
		BigDecimal weight = "KG".equals(unit) ? task.goodQuantity().multiply(BigDecimal.valueOf(0.35)) : null;
		jdbc.update("""
			update planning_task
			set compensation_mode = ?, settlement_unit = ?, completed_weight_kg = ?, assigned_to = coalesce(assigned_to, ?)
			where id = ?
			""", mode, unit, weight, simulatedWorker, task.id());
		entityManager.clear();
	}

	private void ensurePayrollRate(PlanningApplication.TaskView task, String supervisor, String unit, YearMonth month) {
		Integer existing = jdbc.queryForObject("""
			select count(*) from piecework_rate
			where operation_code = ? and route_type = ? and product_code = ? and active = true and effective_from <= ?
			""", Integer.class, task.operationCode(), task.routeType().name(), task.productCode(), month.atEndOfMonth());
		if (existing != null && existing > 0) {
			return;
		}
		piecework.createRate(new PieceworkApplication.RateCommand(
			supervisor, task.operationCode(), task.operationName(), task.routeType(),
			"SIM-PW-" + month.toString().replace("-", "") + "-" + task.operationCode(), unit,
			payrollRate(task.operationCode()), month.atDay(1), task.productCode(), task.productName()));
	}

	private static String payrollUnit(String operationCode) {
		return switch (operationCode) {
			case "TREE_ASSEMBLY" -> "TREE";
			case "OPTIONAL_FINISHING" -> "KG";
			default -> "PCS";
		};
	}

	private static BigDecimal payrollQuantity(PlanningApplication.TaskView task, String unit) {
		return switch (unit) {
			case "TREE" -> task.treeCount();
			case "KG" -> task.completedWeightKg();
			default -> task.goodQuantity();
		};
	}

	private static BigDecimal payrollRate(String operationCode) {
		return switch (operationCode) {
			case "WAX_INJECTION" -> BigDecimal.valueOf(0.85);
			case "WAX_REPAIR" -> BigDecimal.valueOf(0.65);
			case "TREE_ASSEMBLY" -> BigDecimal.valueOf(6.50);
			case "OPTIONAL_FINISHING" -> BigDecimal.valueOf(3.20);
			default -> throw new IllegalArgumentException("Unsupported simulation piecework operation: " + operationCode);
		};
	}

	@Transactional
	public FactoryAcceptanceRunView resetAndRunFactoryAcceptance(YearMonth month) {
		clearBusinessData();
		entityManager.clear();

		String code = "FACTORY-ACCEPTANCE-" + month;
		UUID runId = UUID.randomUUID();
		jdbc.update("insert into simulation_run (id, simulation_code, month_start, created_at) values (?, ?, ?, ?)",
			runId, code, month.atDay(1), Timestamp.from(month.atDay(1).atTime(8, 0).toInstant(ZoneOffset.UTC)));

		ProductView midProduct = createProduct("FAC-MID-VALVE", "中温蜡 CF8 阀体", RouteType.MID_TEMP_WAX,
			"DN50 x PN16", "CF8", "/product-models/valve-body.png");
		ProductView lowProduct = createProduct("FAC-LOW-FLANGE", "低温蜡 CF8 法兰", RouteType.LOW_TEMP_WAX,
			"DN80 x PN16", "CF8", "/product-models/pipe-flange.png");
		ProductView sandProduct = createProduct("FAC-SAND-PUMP", "砂型 HT250 泵壳", RouteType.SAND_OUTSOURCE,
			"DN100 inlet", "HT250", "/product-models/pump-casing.png");
		ProductView customProduct = createProduct("FAC-MID-IMPELLER", "中温蜡 304 叶轮", RouteType.MID_TEMP_WAX,
			"Ø180 x 48 mm", "304", "/product-models/impeller.png");

		CustomerView east = createCustomer("FAC-CUS-EAST", "华东泵阀装备有限公司");
		CustomerView south = createCustomer("FAC-CUS-SOUTH", "华南流体控制有限公司");
		CustomerView west = createCustomer("FAC-CUS-WEST", "西部工程装备有限公司");
		CustomerView newMoldCustomer = createCustomer("FAC-CUS-NEW", "长三角精密制造有限公司");
		registerCarrier("CART-MID-01", "中温蜡周转车 01", "MID-WAX");
		registerCarrier("CART-LOW-01", "低温蜡周转车 01", "LOW-WAX");
		registerCarrier("CART-SAND-01", "砂型外协周转车 01", "SAND-OUTSOURCE");
		ResourceApplication.AssetView dewaxFurnace = registerOrFindAsset(new ResourceApplication.RegisterCommand(
			"FUR-DEWAX-01", "脱蜡炉 01", "FURNACE", "DEWAX-AREA", null, "COMPANY_OWNED", null));
		ResourceApplication.AssetView pouringFurnace = registerOrFindAsset(new ResourceApplication.RegisterCommand(
			"FUR-POUR-01", "浇筑炉 01", "FURNACE", "POURING-AREA", null, "COMPANY_OWNED", null));

		OrderView customerMoldOrder = createReleasedOrder("FAC-202607-001", east, midProduct, OrderPriority.URGENT,
			month.atDay(Math.min(month.lengthOfMonth(), 18)), BigDecimal.valueOf(20));
		OrderView companyMoldOrder = createReleasedOrder("FAC-202607-002", south, lowProduct, OrderPriority.NORMAL,
			month.atDay(Math.min(month.lengthOfMonth(), 21)), BigDecimal.valueOf(16));
		OrderView sandOrder = createReleasedOrder("FAC-202607-003", west, sandProduct, OrderPriority.NORMAL,
			month.atDay(Math.min(month.lengthOfMonth(), 24)), BigDecimal.valueOf(12));
		OrderView customMoldOrder = createReleasedOrder("FAC-202607-004", newMoldCustomer, customProduct, OrderPriority.SAMPLE,
			month.atDay(Math.min(month.lengthOfMonth(), 16)), BigDecimal.valueOf(30));

		ResourceApplication.AssetView customerMold = resources.register(new ResourceApplication.RegisterCommand(
			"MOLD-CUS-VALVE-01", "客户寄存阀体模具", "MOLD", "MOLD-A-01", 5000, "CUSTOMER_OWNED", east.name()));
		ResourceApplication.AssetView companyMold = resources.register(new ResourceApplication.RegisterCommand(
			"MOLD-OWN-FLANGE-01", "企业自有法兰模具", "MOLD", "MOLD-B-03", 8000, "COMPANY_OWNED", null));
		ResourceApplication.AssetView customMold = molds.receive(new MoldApplication.ReceiveCommand(null, "MOLD-NEW-IMPELLER-01",
			"新定制叶轮模具", "MOLD-C-02", 6000, "COMPANY_OWNED", null, "M001", null));
		molds.selectForOrderLine(customerMoldOrder.id(), customerMoldOrder.lines().getFirst().id(), customerMold.id(), "S001");
		molds.selectForOrderLine(companyMoldOrder.id(), companyMoldOrder.lines().getFirst().id(), companyMold.id(), "LW01");
		molds.selectForOrderLine(customMoldOrder.id(), customMoldOrder.lines().getFirst().id(), customMold.id(), "S001");
		log(runId, month.atDay(1), "FD01", "FRONT_DESK_CLERK", "customer", "CREATE_CUSTOMERS_AND_ORDERS", code,
			"新建 4 个客户、4 张订单和产品图纸附件；订单按产线分开创建。 ");
		log(runId, month.atDay(2), "E001", "PROCESS_ENGINEER", "engineering", "CONFIRM_PROCESS_CARDS", code,
			"补充每张订单的产品专属参数并确认工艺卡，系统释放对应生产工单。 ");
		log(runId, month.atDay(2), "M001", "MOLD_KEEPER", "mold", "MOLD_CASES_READY", code,
			"验证客户寄存、企业自有与新定制入库三种模具状态及库位。 ");

		List<PlanningApplication.TaskView> midTasks = completeTasks(customerMoldOrder, month.atDay(3), true, null, runId);
		List<PlanningApplication.TaskView> lowTasks = completeTasks(companyMoldOrder, month.atDay(6), true, null, runId);
		List<PlanningApplication.TaskView> customTasks = completeTasks(customMoldOrder, month.atDay(9), true, null, runId, true);
		List<PlanningApplication.TaskView> sandTasks = completeTasks(sandOrder, month.atDay(12), true, null, runId);

		createAndCompleteFurnaceBatch("DEWAX", dewaxFurnace.id(), "CF8-202607-A", BigDecimal.valueOf(65),
			BigDecimal.valueOf(170), BigDecimal.valueOf(171), BigDecimal.valueOf(0.65),
			List.of(taskFor(midTasks, "DEWAX").id(), taskFor(lowTasks, "DEWAX").id(), taskFor(customTasks, "DEWAX").id()), "DW01");
		createAndCompleteFurnaceBatch("POURING", pouringFurnace.id(), "CF8-202607-B", BigDecimal.valueOf(65),
			BigDecimal.valueOf(1580), BigDecimal.valueOf(1584), null,
			List.of(taskFor(midTasks, "POURING").id(), taskFor(lowTasks, "POURING").id(), taskFor(customTasks, "POURING").id()), "PO01");
		log(runId, month.atDay(14), "DW01", "DEWAX_OPERATOR", "furnace", "DEWAX_FURNACE_BATCH", code,
			"将三个兼容订单的脱蜡任务合并入同一炉次，记录装炉量、压力和实际温度。 ");
		log(runId, month.atDay(15), "PO01", "POURING_OPERATOR", "furnace", "POURING_FURNACE_BATCH", code,
			"将三个兼容订单的浇筑任务合并入同一炉次，记录材质批次、目标与实际温度。 ");

		PlanningApplication.TaskView repair = taskFor(customTasks, "WAX_REPAIR");
		UUID exceptionId = jdbc.query("select id from production_handoff_event where task_id = ?", (rs, row) -> rs.getObject(1, UUID.class), repair.id()).getFirst();
		handoffExceptions.assign("HANDOFF", exceptionId, "S001", "S001");
		handoffExceptions.resolve("HANDOFF", exceptionId, "组树复核为 29 件，差异 1 件计入修蜡损耗，后续按 29 件继续生产。", "S001");
		log(runId, month.atDay(11), "S001", "MID_TEMP_WAX_SUPERVISOR", "handoff", "RESOLVE_HANDOFF_EXCEPTION", customMoldOrder.orderNo(),
			"修蜡至组树交接数量差异已指派、复核并关闭，不阻断后续工序。 ");

		OutsourcingApplication.SupplierView supplier = outsourcing.createSupplier(new OutsourcingApplication.SupplierCommand(
			"SUP-SAND-01", "华北砂型铸造协作厂", "陈工", "13800000000"));
		OutsourcingApplication.OrderView outsourcingOrder = outsourcing.createOrder(new OutsourcingApplication.OrderCommand(
			"OUT-FAC-202607-003", supplier.id(), sandProduct.code(), sandProduct.name(), BigDecimal.valueOf(12), "PCS",
			month.atDay(Math.min(month.lengthOfMonth(), 20)), "砂型外协验收场景"));
		outsourcing.transition(outsourcingOrder.id(), new OutsourcingApplication.MilestoneCommand("SENT", null, "外协发出", "OS01"));
		outsourcing.transition(outsourcingOrder.id(), new OutsourcingApplication.MilestoneCommand("IN_PROGRESS", null, "供应商造型与浇注中", "OS01"));
		outsourcing.transition(outsourcingOrder.id(), new OutsourcingApplication.MilestoneCommand("RECEIVED", BigDecimal.valueOf(12), "来料齐套接收", "OS01"));
		outsourcing.transition(outsourcingOrder.id(), new OutsourcingApplication.MilestoneCommand("CLOSED", null, "来料检验合格，外协订单关闭", "OS01"));
		log(runId, month.atDay(17), "OS01", "OUTSOURCING_MANAGER", "outsourcing", "OUTSOURCE_RECEIPT", sandOrder.orderNo(),
			"完成砂型外协发出、进度、收货和关闭；客户订单同步完成来料检验。 ");

		List<String> deliveries = List.of(
			deliver(customerMoldOrder, midTasks, month.atDay(18), runId),
			deliver(companyMoldOrder, lowTasks, month.atDay(21), runId),
			deliver(sandOrder, sandTasks, month.atDay(24), runId),
			deliver(customMoldOrder, customTasks, month.atDay(Math.min(month.lengthOfMonth(), 27)), runId));
		jdbc.update("update simulation_run set completed_order_id = ?, completed_order_no = ? where id = ?",
			customerMoldOrder.id(), customerMoldOrder.orderNo(), runId);
		return new FactoryAcceptanceRunView(code, 4, deliveries.size(), 2, 1,
			List.of(new MoldCaseView("CUSTOMER_STOCK", customerMoldOrder.orderNo(), customerMold.assetCode(), customerMold.ownershipType(), customerMold.moldCustodyStatus()),
				new MoldCaseView("COMPANY_STOCK", companyMoldOrder.orderNo(), companyMold.assetCode(), companyMold.ownershipType(), companyMold.moldCustodyStatus()),
				new MoldCaseView("CUSTOM_MOLD_RECEIPT", customMoldOrder.orderNo(), customMold.assetCode(), customMold.ownershipType(), customMold.moldCustodyStatus())),
			logs(runId));
	}

	private void createAndCompleteFurnaceBatch(String operationCode, UUID furnaceId, String materialBatch,
			BigDecimal chargeQuantity, BigDecimal targetTemperature, BigDecimal actualTemperature, BigDecimal pressureMpa,
			List<UUID> taskIds, String operatorCode) {
		FurnaceBatchApplication.FurnaceBatchView batch = furnaceBatches.create(new FurnaceBatchApplication.CreateCommand(
			operationCode, furnaceId, materialBatch, chargeQuantity, targetTemperature, actualTemperature, pressureMpa,
			taskIds, "工厂验收仿真多订单合炉", operatorCode));
		furnaceBatches.complete(batch.id(), new FurnaceBatchApplication.CompleteCommand(actualTemperature, pressureMpa,
			"炉次完成并保留实际参数", operatorCode));
	}

	private String deliver(OrderView order, List<PlanningApplication.TaskView> tasks, LocalDate day, UUID runId) {
		PlanningApplication.TaskView finalTask = tasks.getLast();
		quality.inspect(new QualityApplication.InspectionCommand(UUID.randomUUID(), finalTask.id(), finalTask.goodQuantity(),
			finalTask.goodQuantity(), BigDecimal.ZERO, null, "Q001", "工厂验收仿真终检合格"));
		FulfillmentApplication.LotSubmission lot = fulfillment.registerLot(new FulfillmentApplication.RegisterLotCommand(
			UUID.randomUUID(), finalTask.id(), finalTask.goodQuantity(), "FG-01", "G001"));
		FulfillmentApplication.DeliveryView delivery = fulfillment.createDelivery(new FulfillmentApplication.CreateDeliveryCommand(
			order.id(), lot.lot().id(), finalTask.goodQuantity(), order.customerName() + "收货组", "客户成品收货仓", "G001"));
		FulfillmentApplication.DeliveryView picked = fulfillment.transition(delivery.id(),
			new FulfillmentApplication.TransitionCommand("PICKED", "G001", null, null, "按订单备货"));
		FulfillmentApplication.DeliveryView shipped = fulfillment.transition(picked.id(),
			new FulfillmentApplication.TransitionCommand("SHIPPED", "G001", "演示工业物流", "SIM-" + order.orderNo(), "承运商提货"));
		FulfillmentApplication.DeliveryView delivered = fulfillment.transition(shipped.id(),
			new FulfillmentApplication.TransitionCommand("DELIVERED", "G001", null, null, "客户签收"));
		log(runId, day, "Q001", "QUALITY_INSPECTOR", "quality", "FINAL_INSPECTION", order.orderNo(), "终检合格并放行。 ");
		log(runId, day, "G001", "FINISHED_GOODS_KEEPER", "fulfillment", "FINISHED_GOODS_DELIVERY", order.orderNo(),
			"成品入库、备货、发运并确认签收，交付单 " + delivered.deliveryNo());
		return delivered.deliveryNo();
	}

	private static PlanningApplication.TaskView taskFor(List<PlanningApplication.TaskView> tasks, String operationCode) {
		return tasks.stream().filter(task -> operationCode.equals(task.operationCode())).findFirst().orElseThrow();
	}

	private void clearBusinessData() {
		List<String> tables = List.of(
			"document_print_audit", "notification_item", "integration_job", "workflow_action", "workflow_request", "operation_audit_event",
			"customer_sensitive_request", "order_engineering_reminder",
			"furnace_batch_task", "furnace_batch", "production_cart_transfer", "partial_flow_release", "production_shortage_alert",
			"delivery_event", "delivery_order", "finished_goods_lot", "mold_external_movement", "mold_movement", "mold_request", "order_mold_selection",
			"customer_product_mold_relation", "product_mold_relation",
			"mold_maintenance_record",
			"resource_occupation", "schedule_queue_item", "quality_disposition", "quality_inspection",
			"piecework_entry", "labor_time_entry", "manual_report_sheet", "shell_building_record",
			"post_treatment_decision", "production_handoff_event", "execution_report", "inventory_movement", "inventory_balance_creation_lock",
			"inventory_balance", "outsourcing_milestone", "outsourcing_order", "outsourcing_supplier",
			"simulation_role_operation_log", "simulation_run", "planning_task", "planning_batch", "planning_work_order",
			"asset_qr_label",
			"customer_order_line", "customer_order_header", "customer_order_customer", "product_process_card_template", "engineering_product", "resource_asset"
		);
		tables.forEach(table -> jdbc.update("delete from " + table));
	}

	private ProductView createProduct(String code, String name, RouteType route, String specification,
			String material, String modelImageUrl) {
		return products.list().stream()
			.filter(product -> product.code().equalsIgnoreCase(code))
			.findFirst()
			.orElseGet(() -> products.create(new CreateProductCommand(code, name, route, "V1", modelImageUrl, specification, material)));
	}

	private void registerCarrier(String code, String name, String locationCode) {
		registerOrFindAsset(new ResourceApplication.RegisterCommand(code, name, "CARRIER", locationCode, null,
			"COMPANY_OWNED", null));
	}

	private ResourceApplication.AssetView registerOrFindAsset(ResourceApplication.RegisterCommand command) {
		return resources.listAssets().stream()
			.filter(asset -> asset.assetCode().equalsIgnoreCase(command.assetCode()))
			.findFirst()
			.orElseGet(() -> resources.register(command));
	}

	private CustomerView createCustomer(String code, String name) {
		return orders.createCustomer(new CreateCustomerCommand(code, name, "仿真联系人", "13800000000"));
	}

	private OrderView createReleasedOrder(String orderNo, CustomerView customer, ProductView product,
			OrderPriority priority, LocalDate deliveryDate, BigDecimal quantity) {
		OrderView order = orders.createOrder(new CreateOrderCommand(orderNo, customer.id(), priority, deliveryDate,
			"月度生产仿真数据", "FD01", null, null, List.of(new CreateOrderLineCommand(product.id(), quantity, "PCS"))));
		orders.submit(order.id());
		orders.confirmEngineering(order.id(), new EngineeringConfirmationCommand("E001", "SIM-" + product.routeVersion(),
			engineeringParameters(product), operationParameters(product.routeType())));
		orders.reviewByCustomerManager(order.id(), "CM001", "仿真订单客户需求复核通过");
		planning.releaseOrder(order.id()).forEach(workOrder ->
			planning.launchBatch(workOrder.batchId(), workOrder.plannedQuantity(), supervisorForRoute(workOrder.routeType())));
		return orders.getOrder(order.id());
	}

	private List<PlanningApplication.TaskView> completeTasks(OrderView order, LocalDate day, boolean recordRoles,
			Integer limit, UUID runId) {
		return completeTasks(order, day, recordRoles, limit, runId, false, false);
	}

	private List<PlanningApplication.TaskView> completeTasks(OrderView order, LocalDate day, boolean recordRoles,
			Integer limit, UUID runId, boolean simulateRepairHandoffException) {
		return completeTasks(order, day, recordRoles, limit, runId, simulateRepairHandoffException, false);
	}

	private List<PlanningApplication.TaskView> completeTasks(OrderView order, LocalDate day, boolean recordRoles,
			Integer limit, UUID runId, boolean simulateRepairHandoffException, boolean simulateInHouseFinishing) {
		List<PlanningApplication.TaskView> tasks = planning.listTasks(null, null, order.id()).stream()
			.sorted(Comparator.comparingInt(PlanningApplication.TaskView::sequenceNo)).toList();
		int maximum = limit == null ? tasks.size() : Math.min(limit, tasks.size());
		for (int index = 0; index < maximum; index++) {
			PlanningApplication.TaskView task = planning.getTask(tasks.get(index).id());
			if (task.status() == com.renyi.mes.planning.TaskStatus.COMPLETED) {
				continue;
			}
			String worker = workerFor(task);
			String supervisor = supervisorFor(task);
			MoldApplication.MoldRequestView issuedMold = task.operationCode().equals("WAX_INJECTION")
				? issueSimulationMold(task, worker, supervisor) : null;
			boolean treeAssembly = task.operationCode().equals("TREE_ASSEMBLY");
			boolean hourlyOperation = isHourlyOperation(task.operationCode());
			String reportingMode = treeAssembly ? "TREE_COUNT"
				: simulateRepairHandoffException && task.operationCode().equals("WAX_REPAIR") ? "HANDOFF_TO_TREE" : "SELF_REPORTED_QUANTITY";
			if (issuedMold != null) {
				waxDispatch.dispatch(task.id(), new WaxDispatchApplication.Command(null, "MOLD-01", "M001", worker,
					reportingMode, null, "PCS", null, null, supervisor));
			}
			else {
				if (task.operationCode().equals("SHELL_BUILDING")) {
					planning.configureShellLineMode(task.id(), "AUTOMATED", supervisor);
				}
				planning.assign(task.id(), worker, reportingMode, null, treeAssembly ? "TREE" : "PCS", null, supervisor);
			}
			if (recordRoles) {
				if (issuedMold != null) {
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), "S001", "PRODUCTION_MANAGER", "resource", "MOLD_REQUEST",
						task, "选择订单专属模具 " + issuedMold.requestNo() + " 并提交领用申请");
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), "M001", "MOLD_KEEPER", "resource", "MOLD_ISSUE",
						task, "模具仓出库并交接给射蜡工 " + worker);
				}
				logForTask(runId, order.orderNo(), day.plusDays(index / 2), "S001", "PRODUCTION_MANAGER", "planning", "TASK_DISPATCH",
					task, "按优先级派发任务给 " + worker);
				logForTask(runId, order.orderNo(), day.plusDays(index / 2), worker, roleFor(worker), "execution", "PROCESS_CARD_AND_START",
					task, "查看电子工单和工艺卡后开工");
			}
			PlanningApplication.TaskView started = planning.start(task.id(), worker);
			if (simulateInHouseFinishing && task.operationCode().equals("SEMI_FINISHED_COUNT")) {
				postTreatment.decide(new PostTreatmentApplication.DecisionCommand(task.id(), "IN_HOUSE", List.of("GRINDING"),
					"月度计件仿真：厂内打磨后处理", null, null, "", "FS001"));
			}
			BigDecimal reportedGood;
			if (task.operationCode().equals("MANUAL_SHELL_BUILDING")) {
				for (int layer = 1; layer <= 6; layer++) {
					labor.recordShell(new LaborOperationsApplication.ShellRecordCommand(task.id(), UUID.randomUUID(), "MANUAL",
						layer, 300, started.plannedQuantity(), worker, "Simulation manual shell layer " + layer,
						layer == 6 ? "FLOW_TO_NEXT" : "WAIT_NEXT_LAYER", null));
				}
				reportedGood = started.plannedQuantity();
			}
			else if (task.operationCode().equals("TREE_ASSEMBLY")) {
				BigDecimal treeCount = started.plannedQuantity().remainder(BigDecimal.TEN).signum() == 0
					? started.plannedQuantity().divide(BigDecimal.TEN) : BigDecimal.ONE;
				BigDecimal piecesPerTree = treeCount.equals(BigDecimal.ONE) ? started.plannedQuantity() : BigDecimal.TEN;
				reportedGood = execution.reportTree(new ExecutionApplication.TreeReportCommand(UUID.randomUUID(), task.id(),
					treeCount, piecesPerTree, BigDecimal.ZERO, worker, "SIMULATOR", "SIM-WORKSHOP")).report().goodQuantity();
			}
			else if (simulateRepairHandoffException && task.operationCode().equals("WAX_REPAIR")) {
				reportedGood = started.plannedQuantity().subtract(BigDecimal.ONE);
				handoffs.handoff(new HandoffApplication.HandoffCommand(task.id(), reportedGood,
					"Repair-to-tree quantity mismatch", worker, "TA01"));
			}
			else {
				ExecutionApplication.ReportView report = execution.report(new ExecutionApplication.ReportCommand(UUID.randomUUID(), task.id(),
					started.plannedQuantity(), BigDecimal.ZERO, worker, "SIMULATOR", "SIM-WORKSHOP")).report();
				reportedGood = report.goodQuantity();
				backdate("execution_report", report.id(), day.plusDays(index / 2));
			}
			backdateTask(task.id(), day.plusDays(index / 2));
			if (hourlyOperation) {
				recordWorkshopTime(task, worker, supervisor, day.plusDays(index / 2));
				if (recordRoles) {
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), worker, roleFor(worker), "labor", "LABOR_TIME_REPORT",
						task, "登记 " + workshopHours(task.operationCode()) + " 小时工时");
				}
			}
			if (isShellBuilding(task.operationCode()) && !task.operationCode().equals("MANUAL_SHELL_BUILDING")) {
				recordShell(task, worker, day.plusDays(index / 2));
				if (recordRoles) {
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), worker, roleFor(worker), "labor", "SHELL_LAYER_RECORD",
						task, "记录 " + (task.operationCode().equals("SHELL_BUILDING") ? "自动" : "人工") + "制壳 6 层、干燥 240 分钟");
				}
			}
			if (recordRoles) {
				logForTask(runId, order.orderNo(), day.plusDays(index / 2), worker, roleFor(worker), "execution", "TASK_REPORT",
					task, "完成报工，合格 " + reportedGood + " 件");
				if (index + 1 < maximum) {
					PlanningApplication.TaskView nextTask = tasks.get(index + 1);
					if (planning.getTask(nextTask.id()).status() != com.renyi.mes.planning.TaskStatus.COMPLETED) {
						String cartCode = carrierFor(order.lines().getFirst().routeType());
					CartTransferApplication.CartTransferView transfer = carts.load(new CartTransferApplication.LoadCommand(
						task.id(), cartCode, reportedGood, null, worker, UUID.randomUUID(), "SIMULATOR", "SIM-WORKSHOP"));
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), worker, roleFor(worker), "cart", "CART_LOAD",
						task, "扫描 " + cartCode + "，装载 " + reportedGood + " 件并流转至 " + nextTask.operationName());
					String receiver = workerFor(nextTask);
					carts.receive(transfer.id(), new CartTransferApplication.ReceiveCommand(reportedGood, null, null, receiver, UUID.randomUUID(), "SIMULATOR", "SIM-WORKSHOP"));
					logForTask(runId, order.orderNo(), day.plusDays(index / 2), receiver, roleFor(receiver), "cart", "CART_RECEIVE",
						nextTask, "扫描 " + cartCode + "，核对并接收 " + reportedGood + " 件");
					}
				}
			}
			if (issuedMold != null) {
				molds.returnMold(issuedMold.id(), "M001");
			}
		}
		return planning.listTasks(null, null, order.id()).stream()
			.sorted(Comparator.comparingInt(PlanningApplication.TaskView::sequenceNo)).toList();
	}

	private void logForTask(UUID runId, String orderNo, LocalDate date, String employee, String role, String module,
			String function, PlanningApplication.TaskView task, String summary) {
		log(runId, date, employee, role, module, function, orderNo + " / " + task.taskNo(),
			task.operationName() + "：" + summary);
	}

	private void log(UUID runId, LocalDate date, String employee, String role, String module, String function,
			String reference, String summary) {
		jdbc.update("""
			insert into simulation_role_operation_log (
				id, simulation_run_id, occurred_at, employee_code, role_code, module_code,
				function_code, business_reference, operation_summary
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), runId, Timestamp.from(date.atTime(9, 0).toInstant(ZoneOffset.UTC)), employee,
			role, module, function, reference, summary);
	}

	private MoldApplication.MoldRequestView issueSimulationMold(PlanningApplication.TaskView task, String workerCode, String supervisorCode) {
		List<UUID> selectedMolds = jdbc.query("""
			select s.mold_asset_id
			from planning_batch b
			join planning_work_order w on w.id = b.work_order_id
			join order_mold_selection s on s.order_line_id = w.order_line_id
			where b.id = ?
			""", (rs, row) -> rs.getObject(1, UUID.class), task.batchId());
		UUID moldId;
		if (selectedMolds.isEmpty()) {
			String suffix = task.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
			String location = "SIM-LOC-" + suffix;
			molds.createStorageLocation(new MoldApplication.CreateStorageLocationCommand(location, "仿真模具库位 " + suffix, 1, "M001"));
			moldId = resources.register(new ResourceApplication.RegisterCommand(
				"SIM-MOLD-" + suffix, "月度仿真订单专属模具 " + task.workOrderNo(), "MOLD", location, null,
				"COMPANY_OWNED", null)).id();
		} else {
			moldId = selectedMolds.getFirst();
		}
		MoldApplication.MoldRequestView request = molds.requestForWaxTask(task.id(),
			new MoldApplication.TaskRequestCommand(moldId, supervisorCode, workerCode));
		return molds.issue(request.id(), new MoldApplication.IssueCommand("MOLD-01", "M001", workerCode));
	}

	private static String engineeringParameters(ProductView product) {
		return "产品：" + product.name() + "；规格：" + product.specification() + "；材质：" + product.material() + "；" + switch (product.routeType()) {
			case MID_TEMP_WAX -> "射蜡温度：68±2C；射蜡压力：0.55±0.05MPa；保压：12s；"
				+ "修蜡外观：A级；组树：10件/树；自动制壳：6层，层间干燥240min；脱蜡：170C/0.65MPa；浇筑：1580±20C。";
			case LOW_TEMP_WAX -> "射蜡温度：62±2C；射蜡压力：0.45±0.05MPa；保压：10s；"
				+ "修蜡外观：A级；组树：8件/树；人工制壳：6层，层间干燥300min；脱蜡：165C/0.60MPa；浇筑：1560±20C。";
			case SAND_OUTSOURCE -> "外协工艺要求：按确认图纸造型、浇注和检验；来料检验按批次执行。";
		};
	}

	private static String operationParameters(RouteType routeType) {
		return switch (routeType) {
			case MID_TEMP_WAX -> "{\"WAX_INJECTION\":\"68±2C；0.55±0.05MPa；保压12s\",\"WAX_REPAIR\":\"A级外观，毛刺与变形隔离\",\"TREE_ASSEMBLY\":\"10件/树，按组树标准图\",\"SHELL_BUILDING\":\"自动制壳6层，层间干燥240min\",\"DEWAX\":\"170C / 0.65MPa\",\"POURING\":\"1580±20C；炉号与材质批次必填\",\"KNOCKOUT_CUTTING\":\"按分割线作业并清点半成品\",\"OPTIONAL_FINISHING\":\"按实际公斤记录后处理计件\"}";
			case LOW_TEMP_WAX -> "{\"WAX_INJECTION\":\"62±2C；0.45±0.05MPa；保压10s\",\"WAX_REPAIR\":\"A级外观，毛刺与变形隔离\",\"TREE_ASSEMBLY\":\"8件/树，按组树标准图\",\"SHELL_BUILDING\":\"手工制壳6层，层间干燥300min\",\"DEWAX\":\"165C / 0.60MPa\",\"POURING\":\"1560±20C；炉号与材质批次必填\",\"KNOCKOUT_CUTTING\":\"按分割线作业并清点半成品\",\"OPTIONAL_FINISHING\":\"按实际公斤记录后处理计件\"}";
			case SAND_OUTSOURCE -> "{\"OUTSOURCE_SEND\":\"确认图纸、材质与交期后发出\",\"OUTSOURCE_PROGRESS\":\"外协方按节点反馈造型、浇注、检验\",\"INCOMING_INSPECTION\":\"按来料批次检验并判定入库\"}";
		};
	}

	private void backdateOrder(UUID orderId, LocalDate date) {
		Timestamp timestamp = Timestamp.from(date.atTime(8, 0).toInstant(ZoneOffset.UTC));
		jdbc.update("""
			update customer_order_header
			set created_at = ?, engineering_confirmed_at = ?, approved_at = ?, released_at = ?
			where id = ?
			""", timestamp, timestamp, timestamp, timestamp, orderId);
	}

	private void backdateTask(UUID taskId, LocalDate date) {
		Timestamp timestamp = Timestamp.from(date.atTime(16, 0).toInstant(ZoneOffset.UTC));
		jdbc.update("update planning_task set started_at = ?, completed_at = ? where id = ?", timestamp, timestamp, taskId);
	}

	private void backdate(String table, UUID id, LocalDate date) {
		jdbc.update("update " + table + " set occurred_at = ? where id = ?",
			Timestamp.from(date.atTime(15, 0).toInstant(ZoneOffset.UTC)), id);
	}

	private void backdateFulfillment(UUID lotId, UUID deliveryId, YearMonth month) {
		Instant received = month.atDay(28).atTime(15, 0).toInstant(ZoneOffset.UTC);
		Instant picked = month.atDay(29).atTime(10, 0).toInstant(ZoneOffset.UTC);
		Instant shipped = month.atDay(29).atTime(16, 0).toInstant(ZoneOffset.UTC);
		Instant delivered = month.atEndOfMonth().atTime(14, 0).toInstant(ZoneOffset.UTC);
		jdbc.update("update finished_goods_lot set registered_at = ? where id = ?", Timestamp.from(received), lotId);
		jdbc.update("""
			update delivery_order set created_at = ?, picked_at = ?, shipped_at = ?, delivered_at = ?, updated_at = ?
			where id = ?
			""", Timestamp.from(picked), Timestamp.from(picked), Timestamp.from(shipped), Timestamp.from(delivered),
			Timestamp.from(delivered), deliveryId);
		jdbc.update("""
			update delivery_event set occurred_at = case to_status
				when 'DRAFT' then ? when 'PICKED' then ? when 'SHIPPED' then ? else ? end
			where delivery_id = ?
			""", Timestamp.from(picked), Timestamp.from(picked), Timestamp.from(shipped), Timestamp.from(delivered), deliveryId);
	}

	private void normalizeFulfillmentTimeline(UUID orderId, YearMonth month) {
		if (orderId == null) return;
		List<UUID> lots = jdbc.query("select id from finished_goods_lot where order_id = ?", (rs, rowNum) -> rs.getObject(1, UUID.class), orderId);
		List<UUID> deliveries = jdbc.query("select id from delivery_order where order_id = ?", (rs, rowNum) -> rs.getObject(1, UUID.class), orderId);
		if (!lots.isEmpty() && !deliveries.isEmpty()) {
			backdateFulfillment(lots.getFirst(), deliveries.getFirst(), month);
		}
	}

	private void ensureWorkshopStageRecords(UUID runId, UUID orderId, YearMonth month) {
		if (orderId == null) return;
		for (PlanningApplication.TaskView task : planning.listTasks(null, null, orderId)) {
			String worker = workerFor(task);
			if (hasDedicatedStageOperator(task.operationCode())) {
				jdbc.update("update planning_task set assigned_to = ? where id = ?", worker, task.id());
				jdbc.update("update labor_time_entry set worker_code = ? where task_id = ?", worker, task.id());
				updateStageOperationActors(runId, month, task, worker);
			}
			if (!isHourlyOperation(task.operationCode())) continue;
			LocalDate date = month.atDay(Math.min(month.lengthOfMonth(), 25 + task.sequenceNo() / 3));
			jdbc.update("update planning_task set compensation_mode = 'HOURLY' where id = ?", task.id());
			// The task list above may already be managed with the previous compensation mode.
			entityManager.clear();
			if (!hasRecord("labor_time_entry", task.id())) {
					recordWorkshopTime(planning.getTask(task.id()), worker, supervisorFor(task), date);
			}
			addLogIfMissing(runId, date, worker, roleFor(worker), "labor", "LABOR_TIME_REPORT",
				"MSO-" + month.toString().replace("-", "") + "-DELIVERY / " + task.taskNo(),
				task.operationName() + "：登记 " + workshopHours(task.operationCode()) + " 小时工时");
			if (isShellBuilding(task.operationCode()) && !hasRecord("shell_building_record", task.id())) {
				recordShell(task, worker, date);
			}
			if (isShellBuilding(task.operationCode())) {
				addLogIfMissing(runId, date, worker, roleFor(worker), "labor", "SHELL_LAYER_RECORD",
					"MSO-" + month.toString().replace("-", "") + "-DELIVERY / " + task.taskNo(),
					task.operationName() + "：记录自动制壳 6 层、干燥 240 分钟");
			}
		}
	}

	private void updateStageOperationActors(UUID runId, YearMonth month, PlanningApplication.TaskView task, String worker) {
		String reference = "MSO-" + month.toString().replace("-", "") + "-DELIVERY / " + task.taskNo();
		jdbc.update("""
			update simulation_role_operation_log set employee_code = ?, role_code = ?
			where simulation_run_id = ? and business_reference = ?
			  and function_code in ('PROCESS_CARD_AND_START', 'TASK_REPORT', 'LABOR_TIME_REPORT')
			""", worker, roleFor(worker), runId, reference);
	}

	private boolean hasRecord(String table, UUID taskId) {
		Integer count = jdbc.queryForObject("select count(*) from " + table + " where task_id = ?", Integer.class, taskId);
		return count != null && count > 0;
	}

	private void recordWorkshopTime(PlanningApplication.TaskView task, String worker, String supervisor, LocalDate date) {
		LaborOperationsApplication.TimeEntryView entry = labor.recordTime(new LaborOperationsApplication.TimeCommand(
			task.id(), worker, workshopHours(task.operationCode()), supervisor, "SUPERVISOR"));
		backdate("labor_time_entry", entry.id(), date);
	}

	private void recordShell(PlanningApplication.TaskView task, String worker, LocalDate date) {
		LaborOperationsApplication.ShellRecordView shell = labor.recordShell(new LaborOperationsApplication.ShellRecordCommand(
			task.id(), UUID.randomUUID(), task.operationCode().equals("SHELL_BUILDING") ? "AUTOMATED" : "MANUAL",
			6, 240, task.plannedQuantity(), worker, "月度仿真制壳层数与干燥记录", "FLOW_TO_NEXT", null));
		backdate("shell_building_record", shell.id(), date);
	}

	private void addLogIfMissing(UUID runId, LocalDate date, String employee, String role, String module,
			String function, String reference, String summary) {
		Integer count = jdbc.queryForObject("""
			select count(*) from simulation_role_operation_log
			where simulation_run_id = ? and function_code = ? and business_reference = ?
			""", Integer.class, runId, function, reference);
		if (count == null || count == 0) log(runId, date, employee, role, module, function, reference, summary);
	}

	private static boolean isShellBuilding(String operationCode) {
		return operationCode.equals("SHELL_BUILDING") || operationCode.equals("MANUAL_SHELL_BUILDING");
	}

	private static boolean isHourlyOperation(String operationCode) {
		return isShellBuilding(operationCode) || operationCode.equals("DEWAX") || operationCode.equals("POURING")
			|| operationCode.equals("KNOCKOUT_CUTTING") || operationCode.equals("KNOCKOUT") || operationCode.equals("CUTTING") || operationCode.equals("SEMI_FINISHED_COUNT");
	}

	private static boolean hasDedicatedStageOperator(String operationCode) {
		return operationCode.equals("POURING") || operationCode.equals("KNOCKOUT_CUTTING") || operationCode.equals("KNOCKOUT") || operationCode.equals("CUTTING");
	}

	private static BigDecimal workshopHours(String operationCode) {
		return switch (operationCode) {
			case "SHELL_BUILDING", "MANUAL_SHELL_BUILDING" -> BigDecimal.valueOf(4);
			case "DEWAX" -> BigDecimal.valueOf(2);
			case "POURING" -> BigDecimal.valueOf(3);
			case "KNOCKOUT_CUTTING" -> BigDecimal.valueOf(2.5);
			case "KNOCKOUT" -> BigDecimal.valueOf(1.5);
			case "CUTTING" -> BigDecimal.valueOf(1.5);
			default -> BigDecimal.valueOf(1.5);
		};
	}

	private SimulationRunView findRun(String code) {
		List<RunRow> rows = jdbc.query("""
			select id, simulation_code, month_start, completed_order_id, completed_order_no
			from simulation_run where simulation_code = ?
			""", (rs, rowNum) -> new RunRow(rs.getObject("id", UUID.class), rs.getString("simulation_code"),
			rs.getObject("month_start", LocalDate.class), rs.getObject("completed_order_id", UUID.class),
			rs.getString("completed_order_no")), code);
		if (rows.isEmpty()) return null;
		RunRow row = rows.getFirst();
		return new SimulationRunView(row.id(), row.code(), row.monthStart(), row.completedOrderId(), row.completedOrderNo(),
			findDeliveryNo(row.completedOrderId()), countOrders(row.code()), logs(row.id()));
	}

	private String findDeliveryNo(UUID orderId) {
		if (orderId == null) return null;
		List<String> values = jdbc.query("select delivery_no from delivery_order where order_id = ? order by created_at desc",
			(rs, rowNum) -> rs.getString(1), orderId);
		return values.isEmpty() ? null : values.getFirst();
	}

	private int countOrders(String runCode) {
		String suffix = runCode.replace("MONTHLY-", "").replace("-", "");
		Integer count = jdbc.queryForObject("select count(*) from customer_order_header where order_no like ?", Integer.class,
			"MSO-" + suffix + "-%");
		return count == null ? 0 : count;
	}

	private List<RoleOperationView> logs(UUID runId) {
		return jdbc.query("""
			select occurred_at, employee_code, role_code, module_code, function_code, business_reference, operation_summary
			from simulation_role_operation_log where simulation_run_id = ? order by occurred_at, employee_code
			""", (rs, rowNum) -> new RoleOperationView(rs.getTimestamp("occurred_at").toInstant(),
			rs.getString("employee_code"), rs.getString("role_code"), rs.getString("module_code"),
			rs.getString("function_code"), rs.getString("business_reference"), rs.getString("operation_summary")), runId);
	}

	private static String supervisorFor(PlanningApplication.TaskView task) {
		return supervisorForRoute(task.routeType(), task.operationCode());
	}

	private static String supervisorForRoute(RouteType routeType) {
		return supervisorForRoute(routeType, "WAX_INJECTION");
	}

	private static String supervisorForRoute(RouteType routeType, String operationCode) {
		if (routeType == RouteType.SAND_OUTSOURCE) return "GM001";
		return switch (operationCode) {
			case "WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY" -> routeType == RouteType.LOW_TEMP_WAX ? "LW01" : "S001";
			case "SHELL_BUILDING", "MANUAL_SHELL_BUILDING" -> routeType == RouteType.LOW_TEMP_WAX ? "L001" : "S002";
			case "SEMI_FINISHED_COUNT", "OPTIONAL_FINISHING" -> "FS001";
			default -> "S003";
		};
	}

	private static String workerFor(PlanningApplication.TaskView task) {
		if (task.routeType() == RouteType.SAND_OUTSOURCE) return "GM001";
		boolean lowTemperatureWax = task.routeType() == RouteType.LOW_TEMP_WAX;
		String operationCode = task.operationCode();
		return switch (operationCode) {
			case "WAX_INJECTION" -> lowTemperatureWax ? "LWX01" : "WX01";
			case "WAX_REPAIR" -> lowTemperatureWax ? "LWR01" : "WR01";
			case "TREE_ASSEMBLY" -> lowTemperatureWax ? "LTA01" : "TA01";
			case "SHELL_BUILDING", "MANUAL_SHELL_BUILDING" -> lowTemperatureWax ? "LSH01" : "SH01";
			case "DEWAX" -> lowTemperatureWax ? "LDW01" : "DW01";
			case "POURING" -> lowTemperatureWax ? "LP01" : "PO01";
			case "KNOCKOUT_CUTTING", "KNOCKOUT", "CUTTING" -> lowTemperatureWax ? "LKO01" : operationCode.equals("CUTTING") ? "CT01" : "KO01";
			case "OPTIONAL_FINISHING", "FINAL_COUNT" -> lowTemperatureWax ? "LFN01" : "FN01";
			default -> "PM01";
		};
	}

	private static String carrierFor(RouteType routeType) {
		return switch (routeType) {
			case MID_TEMP_WAX -> "CART-MID-01";
			case LOW_TEMP_WAX -> "CART-LOW-01";
			case SAND_OUTSOURCE -> "CART-SAND-01";
		};
	}

	private static String roleFor(String employeeCode) {
		return switch (employeeCode.toUpperCase(Locale.ROOT)) {
			case "M001" -> "MOLD_KEEPER";
			case "WX01" -> "WAX_INJECTION_OPERATOR";
			case "WR01" -> "WAX_REPAIR_OPERATOR";
			case "TA01" -> "TREE_ASSEMBLY_OPERATOR";
			case "SH01" -> "SHELL_BUILDING_OPERATOR";
			case "DW01" -> "DEWAX_OPERATOR";
			case "PO01" -> "POURING_OPERATOR";
			case "KO01" -> "KNOCKOUT_OPERATOR";
			case "CT01" -> "CUTTING_OPERATOR";
			case "FN01" -> "FINISHING_OPERATOR";
			case "G001" -> "FINISHED_GOODS_KEEPER";
			case "L001" -> "WORKSHOP_SUPERVISOR";
			default -> "OPERATOR";
		};
	}

	private record RunRow(UUID id, String code, LocalDate monthStart, UUID completedOrderId, String completedOrderNo) {
	}

	public record SimulationRunView(UUID id, String simulationCode, LocalDate monthStart, UUID completedOrderId,
			String completedOrderNo, String deliveryNo, int generatedOrderCount, List<RoleOperationView> roleOperations) {
	}

	public record PayrollSimulationRunView(SimulationRunView production, YearMonth payrollMonth,
		int confirmedEntryCount, BigDecimal confirmedAmount) {
	}

	public record RoleOperationView(Instant occurredAt, String employeeCode, String roleCode, String moduleCode,
			String functionCode, String businessReference, String operationSummary) {
	}

	public record MoldCaseView(String caseCode, String orderNo, String moldAssetCode, String ownershipType,
			String custodyStatus) {
	}

	public record FactoryAcceptanceRunView(String simulationCode, int generatedOrderCount, int deliveredOrderCount,
			int furnaceBatchCount, int resolvedHandoffExceptionCount, List<MoldCaseView> moldCases,
			List<RoleOperationView> roleOperations) {
	}
}

@RestController
@RequestMapping("/api/simulations/monthly-production")
class MonthlyProductionSimulationController {

	private final MonthlyProductionSimulationApplication simulations;

	MonthlyProductionSimulationController(MonthlyProductionSimulationApplication simulations) {
		this.simulations = simulations;
	}

	@PostMapping
	MonthlyProductionSimulationApplication.SimulationRunView run(@RequestParam(defaultValue = "2026-06") String month) {
		return simulations.run(YearMonth.parse(month));
	}

	@PostMapping("/reset")
	MonthlyProductionSimulationApplication.SimulationRunView reset(@RequestParam(defaultValue = "2026-07") String month) {
		return simulations.resetAndRun(YearMonth.parse(month));
	}

	@PostMapping("/payroll-reset")
	MonthlyProductionSimulationApplication.PayrollSimulationRunView payrollReset(@RequestParam(defaultValue = "2026-07") String month) {
		return simulations.resetAndRunPayroll(YearMonth.parse(month));
	}

	@GetMapping("/{simulationCode}/role-operations")
	List<MonthlyProductionSimulationApplication.RoleOperationView> operations(@PathVariable String simulationCode) {
		MonthlyProductionSimulationApplication.SimulationRunView run = simulations.run(YearMonth.parse(
			simulationCode.replace("MONTHLY-", "")));
		return run.roleOperations();
	}
}

@RestController
@RequestMapping("/api/simulations/factory-acceptance")
class FactoryAcceptanceSimulationController {

	private final MonthlyProductionSimulationApplication simulations;

	FactoryAcceptanceSimulationController(MonthlyProductionSimulationApplication simulations) {
		this.simulations = simulations;
	}

	@PostMapping("/reset")
	MonthlyProductionSimulationApplication.FactoryAcceptanceRunView reset(@RequestParam(defaultValue = "2026-07") String month) {
		return simulations.resetAndRunFactoryAcceptance(YearMonth.parse(month));
	}
}
