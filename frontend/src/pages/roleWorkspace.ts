import {
  BookOpenCheck,
  Boxes,
  ClipboardCheck,
	ClipboardList,
  Factory,
  Gauge,
	MonitorUp,
	PackageCheck,
	Printer,
	ScanLine,
  Route,
  ShieldCheck,
  Truck,
  Warehouse,
  type LucideIcon
} from "lucide-react";
import type { AccessUser } from "../types";

export type RoleWorkspaceKind = "operator" | "supervisor" | "planner" | "warehouse" | "quality" | "engineering" | "admin";
export type RoleTone = "wax" | "casting" | "control" | "warehouse" | "quality" | "engineering" | "system";

export type RoleAction = {
  to: string;
  label: string;
  description: string;
  icon: LucideIcon;
  permission?: string | string[];
};

export type RoleWorkspaceProfile = {
  title: string;
  roleLabel: string;
  focus: string;
  kind: RoleWorkspaceKind;
  tone: RoleTone;
  actions: RoleAction[];
};

const workerActions: RoleAction[] = [
  { to: "/guide", label: "工序指引", description: "查看当前岗位 SOP 与交接要求", icon: BookOpenCheck },
  { to: "/documents", label: "电子工单", description: "查看已授权的工艺卡与作业单", icon: ClipboardCheck, permission: "PROCESS_CARD_VIEW" }
];

function specializedSupervisor(title: string, roleLabel: string, focus: string): RoleWorkspaceProfile {
	return { title, roleLabel, focus, kind: "supervisor", tone: "control", actions: [
		{ to: "/tasks", label: "本工段派工", description: "只显示本人负责产线和工序的任务", icon: ClipboardCheck, permission: "TASK_DISPATCH" },
		{ to: "/report-ledger", label: "报工总账", description: "按订单、员工、工序和日期核对电子与纸质回填记录", icon: ClipboardList, permission: "TASK_DISPATCH" },
		{ to: "/piecework", label: "计件工价", description: "发布本工段下月工价并核对上月计件", icon: Gauge, permission: "PIECEWORK_MANAGE" },
		{ to: "/documents", label: "工单打印", description: "打印本工段受控工艺卡与派工单", icon: BookOpenCheck, permission: "PRINT_WORKSHOP_DOCUMENT" }
	] };
}

const profiles: Record<string, RoleWorkspaceProfile> = {
	WORKSHOP_DISPLAY: {
		title: "车间可视化大屏",
		roleLabel: "车间大屏",
		focus: "仅展示本车间实时队列、产出、异常与扫码定位，不提供生产操作入口。",
		kind: "admin",
		tone: "control",
		actions: [{ to: "/workshop-display", label: "打开车间大屏", description: "查看本车间队列、产出、异常和扫码溯源", icon: MonitorUp, permission: "WORKSHOP_DISPLAY_VIEW" }]
	},
	MID_WAX_SUPERVISOR: specializedSupervisor("中温蜡间主管台", "中温蜡间主管", "负责中温蜡射蜡、修蜡、组树的派工、计件和交接。"),
	LOW_WAX_SUPERVISOR: { kind: "supervisor", tone: "control", title: "低温蜡间主管台", roleLabel: "低温蜡间主管", focus: "负责低温蜡射蜡、修蜡、组树的派工、进度和交接；蜡间不计薪。", actions: [
		{ to: "/tasks", label: "本工段派工", description: "查看低温蜡任务并安排人员", icon: ClipboardList, permission: "TASK_DISPATCH" },
		{ to: "/documents", label: "派工单打印", description: "打印低温蜡电子派工单", icon: Printer, permission: "PRINT_WORKSHOP_DOCUMENT" }
	] },
	MID_SHELL_SUPERVISOR: specializedSupervisor("中温蜡制壳主管台", "中温蜡制壳主管", "负责中温蜡自动与人工制壳的进度、扫码记录和流转。"),
	LOW_SHELL_SUPERVISOR: specializedSupervisor("低温蜡制壳主管台", "低温蜡制壳主管", "负责低温蜡制壳的进度、扫码记录和流转。"),
	POST_PROCESS_SUPERVISOR: specializedSupervisor("后续工艺主管台", "后续工艺主管", "负责脱蜡、浇筑与脱壳分割的派工、进度和交接异常处理。"),
	FINISHING_SUPERVISOR: specializedSupervisor("后处理主管台", "后处理主管", "负责确定后处理去向、派发本厂后处理或协调外送，未选择时直入成品清点。"),
  WAX_INJECTION_OPERATOR: { title: "蜡模制作工作台", roleLabel: "蜡模制作操作工", focus: "处理射蜡、修蜡和组树任务；只聚焦合格数、废品与安全提示。", kind: "operator", tone: "wax", actions: workerActions },
  WAX_REPAIR_OPERATOR: { title: "修蜡工作台", roleLabel: "修蜡操作工", focus: "按工艺卡完成修蜡；大批小件可使用免计数交接。", kind: "operator", tone: "wax", actions: workerActions },
  TREE_ASSEMBLY_OPERATOR: { title: "组树工作台", roleLabel: "组树操作工", focus: "记录树数、每树件数和组树结果，系统自动回算数量。", kind: "operator", tone: "wax", actions: workerActions },
  SHELL_BUILDING_OPERATOR: { title: "制壳工作台", roleLabel: "制壳操作工", focus: "按电子工单记录制壳方式、层数、干燥与进度。", kind: "operator", tone: "wax", actions: workerActions },
  DEWAX_OPERATOR: { title: "脱蜡工作台", roleLabel: "脱蜡操作工", focus: "完成脱蜡报工、结果确认与安全检查。", kind: "operator", tone: "casting", actions: workerActions },
  POURING_OPERATOR: { title: "浇筑工作台", roleLabel: "浇筑操作工", focus: "聚焦浇筑工艺卡、炉前作业确认、报工和异常说明。", kind: "operator", tone: "casting", actions: workerActions },
  KNOCKOUT_OPERATOR: { title: "脱壳分割工作台", roleLabel: "脱壳分割操作工", focus: "完成脱壳分割、自检、报工及半成品交接。", kind: "operator", tone: "casting", actions: workerActions },
  CUTTING_OPERATOR: { title: "分割工作台", roleLabel: "分割操作工", focus: "完成分割、自检、报工及半成品交接。", kind: "operator", tone: "casting", actions: workerActions },
  FINISHING_OPERATOR: { title: "后处理工作台", roleLabel: "后处理操作工", focus: "按后处理工艺报工并登记完工重量；不展示仓库与交付功能。", kind: "operator", tone: "wax", actions: workerActions },
	FRONT_DESK_CLERK: { title: "前台订单受理台", roleLabel: "前台生产文员", focus: "登记客户订单、接收入库新模具并提交工程确认；不审核工艺、不投产、不直接报工。", kind: "admin", tone: "system", actions: [
		{ to: "/orders", label: "订单受理", description: "创建待工程确认订单，跟进工程确认结果", icon: ClipboardCheck, permission: "ORDER_MANAGE" },
		{ to: "/customers", label: "客户查询", description: "检索客户资料并跟进建档申请", icon: ClipboardCheck, permission: "CUSTOMER_VIEW" },
		{ to: "/master-data", label: "产品与模型图", description: "维护产品规格、材质和缺失的产品模型图", icon: Factory, permission: "MASTERDATA_MANAGE" },
		{ to: "/molds", label: "模具入库", description: "登记自有或客户寄存模具，并供订单快速绑定", icon: Boxes, permission: "MOLD_RECEIVE" }
	] },
  OPERATOR: { title: "现场工作台", roleLabel: "现场操作员", focus: "只显示分配给本人的电子工单、SOP 和报工操作。", kind: "operator", tone: "wax", actions: workerActions },
  WORKSHOP_SUPERVISOR: { title: "车间主管台", roleLabel: "车间主管", focus: "接收工序、派工或直接代报，优先处理数量差异与在制风险。", kind: "supervisor", tone: "control", actions: [
    { to: "/tasks", label: "派工与代报", description: "分配工序、核对数据或由主管直接报工", icon: ClipboardCheck, permission: "TASK_DISPATCH" },
    { to: "/scheduling", label: "生产队列", description: "查看当前优先级和下一工序", icon: Route, permission: "PLANNING_VIEW" },
    { to: "/documents", label: "车间文件", description: "打印或查看授权范围内的作业单", icon: BookOpenCheck, permission: "PRINT_WORKSHOP_DOCUMENT" }
  ] },
  PRODUCTION_MANAGER: { title: "生产管理台", roleLabel: "生产总管", focus: "统筹订单优先级、三条产线负荷与现场异常。", kind: "planner", tone: "control", actions: [
		{ to: "/orders", label: "订单投产", description: "查看工程确认后的订单，按中温蜡或低温蜡产线投产", icon: ClipboardCheck, permission: "ORDER_MANAGE" },
    { to: "/scheduling", label: "三线排产", description: "处理急单、样品单和在制订单顺序", icon: Route, permission: "PLANNING_VIEW" },
    { to: "/tasks", label: "派工中心", description: "确认推荐员工并下发任务", icon: ClipboardCheck, permission: "TASK_DISPATCH" },
		{ to: "/report-ledger", label: "报工总账", description: "按订单、员工、工序和日期检查生产事实", icon: ClipboardList, permission: "TASK_DISPATCH" },
		{ to: "/documents", label: "打印工艺卡与派工单", description: "为中温蜡、低温蜡车间统一打印受控作业文件", icon: BookOpenCheck, permission: "PRINT_WORKSHOP_DOCUMENT" },
    { to: "/dashboard", label: "生产概览", description: "查看产出、质量与风险", icon: Gauge, permission: "DASHBOARD_VIEW" }
  ] },
  GLOBAL_SCHEDULER: { title: "全局调度台", roleLabel: "全局调度", focus: "平衡中温蜡、低温蜡和砂型外协的订单优先级与资源占用。", kind: "planner", tone: "control", actions: [
    { to: "/scheduling", label: "三线排产", description: "调整可开工任务的排序与优先级", icon: Route, permission: "PLANNING_VIEW" },
    { to: "/dashboard", label: "生产概览", description: "识别负荷与交期风险", icon: Gauge, permission: "DASHBOARD_VIEW" },
    { to: "/trace", label: "订单追溯", description: "检查订单所在工序与交接记录", icon: Factory, permission: "TRACE_VIEW" }
  ] },
  MOLD_KEEPER: { title: "模具仓工作台", roleLabel: "模具仓管", focus: "处理模具申请、出库、归还和客户模具保管记录。", kind: "warehouse", tone: "warehouse", actions: [
		{ to: "/qr-bind", label: "扫码建档并绑定", description: "扫描模具标签，录入资料并一次完成入库与绑定", icon: ScanLine, permission: "QR_BIND" },
    { to: "/molds", label: "模具领用", description: "处理模具申请、出库与归还", icon: Boxes, permission: ["MOLD_REQUEST", "MOLD_ISSUE"] },
    { to: "/inventory", label: "模具库存", description: "查看模具仓台账与出入库", icon: Warehouse, permission: "MOLD_WAREHOUSE_MANAGE" }
  ] },
  RAW_MATERIAL_KEEPER: { title: "原材料仓工作台", roleLabel: "原材料仓管", focus: "围绕备料、领料、批次库存与投料追溯完成当班工作。", kind: "warehouse", tone: "warehouse", actions: [
    { to: "/inventory", label: "原料收发", description: "完成原材料入库、出库和调整", icon: Warehouse, permission: ["INVENTORY_MANAGE", "RAW_MATERIAL_WAREHOUSE_MANAGE"] },
    { to: "/trace", label: "批次追溯", description: "核对物料与生产批次关联", icon: Factory, permission: "TRACE_VIEW" }
  ] },
  FINISHED_GOODS_KEEPER: { title: "成品仓与交付台", roleLabel: "成品仓管", focus: "核对成品、入库、备货、物流和客户签收，避免暴露无关生产数据。", kind: "warehouse", tone: "warehouse", actions: [
    { to: "/operator-tasks", label: "成品清点任务", description: "接收、开工并完成已派发的成品清点", icon: ClipboardCheck, permission: "TASK_EXECUTE" },
    { to: "/inventory", label: "成品入库", description: "登记成品批次和库存变动", icon: Warehouse, permission: ["INVENTORY_MANAGE", "FINISHED_GOODS_WAREHOUSE_MANAGE"] },
    { to: "/fulfillment", label: "物流交付", description: "备货、发运、运单和签收", icon: PackageCheck, permission: ["FULFILLMENT_MANAGE", "LOGISTICS_MANAGE"] }
  ] },
  QUALITY_INSPECTOR: { title: "质量检验台", roleLabel: "质量检验员", focus: "优先完成待检任务、判定不合格与处置闭环。", kind: "quality", tone: "quality", actions: [
    { to: "/quality", label: "检验与处置", description: "录入检验结果并处理不合格", icon: ShieldCheck, permission: "QUALITY_MANAGE" },
    { to: "/trace", label: "质量追溯", description: "查看工序、批次和报工历史", icon: Factory, permission: "TRACE_VIEW" }
  ] },
  QUALITY_ENGINEER: { title: "质量工程台", roleLabel: "质量工程师", focus: "从缺陷、处置与订单追溯中闭环质量问题。", kind: "quality", tone: "quality", actions: [
    { to: "/quality", label: "质量闭环", description: "分析检验与不合格处置", icon: ShieldCheck, permission: "QUALITY_MANAGE" },
    { to: "/dashboard", label: "质量趋势", description: "查看一次合格率与质量风险", icon: Gauge, permission: "DASHBOARD_VIEW" }
  ] },
  PROCESS_ENGINEER: { title: "工艺工程台", roleLabel: "工艺工程师", focus: "维护产品路线、工艺卡和可供现场查看的 SOP。", kind: "engineering", tone: "engineering", actions: [
    { to: "/master-data", label: "产品与路线", description: "维护产品、路线版本和模型图", icon: Factory, permission: "MASTERDATA_MANAGE" },
	{ to: "/process-card-templates", label: "产品工艺模板", description: "检索历史产品，复制新版本并发布工艺", icon: BookOpenCheck, permission: "PROCESS_CARD_TEMPLATE_MANAGE" },
    { to: "/sops", label: "SOP 管理", description: "发布和维护现场作业标准", icon: BookOpenCheck, permission: "SOP_MANAGE" },
    { to: "/documents", label: "工艺文件", description: "预览和输出授权工艺卡", icon: ClipboardCheck, permission: "PROCESS_CARD_VIEW" }
  ] },
  SYSTEM_ADMIN: { title: "系统管理台", roleLabel: "系统管理员", focus: "维护账户、权限、配置、接口和运行状态，不代替业务审批。", kind: "admin", tone: "system", actions: [
		{ to: "/dashboard", label: "工厂运行总览", description: "查看订单、任务、质量、外协和经营风险全景", icon: Gauge, permission: "DASHBOARD_VIEW" },
		{ to: "/master-data", label: "产品库管理", description: "维护产品、规格、材质、路线和模型图", icon: Factory, permission: "MASTERDATA_MANAGE" },
		{ to: "/report-ledger", label: "全厂报工总账", description: "按权限核对电子报工、主管代报和纸质回填", icon: ClipboardList, permission: "DASHBOARD_VIEW" },
		{ to: "/qr-bind", label: "模具扫码建档", description: "扫描预生成标签，登记模具信息并核对绑定记录", icon: ScanLine, permission: "QR_BIND" },
    { to: "/access", label: "账户权限", description: "维护多角色用户和权限并集", icon: ShieldCheck, permission: "ACCESS_MANAGE" },
    { to: "/administration", label: "平台运维", description: "查看通知、接口和资源运行状态", icon: Gauge, permission: "PLATFORM_ADMIN" },
    { to: "/platform", label: "审批配置", description: "维护审批与业务配置", icon: Route, permission: ["WORKFLOW_MANAGE", "CONFIG_MANAGE"] }
  ] },
  CART_OPERATOR: { title: "周转车工作台", roleLabel: "周转车操作员", focus: "扫码确认周转车装卸、混装产品与工序交接。", kind: "warehouse", tone: "warehouse", actions: [
    { to: "/guide", label: "交接指引", description: "查看周转车扫码和交接 SOP", icon: BookOpenCheck },
    { to: "/cart-transfers", label: "扫码流转", description: "扫描周转车，装车并核对下一工序接收数量", icon: Truck, permission: "CART_OPERATE" }
  ] },
  FINANCE_REVIEWER: { title: "计薪复核台", roleLabel: "财务复核", focus: "核对计件、工时和重量结算数据，保留复核痕迹。", kind: "admin", tone: "system", actions: [
    { to: "/piecework", label: "计薪复核", description: "复核计件、按树和按公斤结算", icon: Gauge, permission: "PIECEWORK_MANAGE" },
    { to: "/documents", label: "统计输出", description: "输出已授权的统计报表", icon: ClipboardCheck, permission: "PRINT_STATISTICS" }
  ] },
  OUTSOURCING_COORDINATOR: { title: "外协协调台", roleLabel: "外协协调员", focus: "跟踪砂型外协下单、在途、收货与异常闭环。", kind: "planner", tone: "control", actions: [
    { to: "/outsourcing", label: "外协台账", description: "维护供应商、订单和交付进度", icon: Truck, permission: "OUTSOURCING_MANAGE" },
    { to: "/trace", label: "外协追溯", description: "查看订单状态与到货记录", icon: Factory, permission: "TRACE_VIEW" }
  ] },
  MOLD_ENGINEER: { title: "模具工程台", roleLabel: "模具工程师", focus: "评估模具适配、定制需求、寿命和技术资料。", kind: "engineering", tone: "engineering", actions: [
    { to: "/molds", label: "模具申请", description: "处理模具适配与领用申请", icon: Boxes, permission: "MOLD_REQUEST" },
    { to: "/documents", label: "模具文件", description: "查看授权的工艺和作业文件", icon: BookOpenCheck, permission: "PROCESS_CARD_VIEW" }
  ] },
  PLANT_MANAGER: { title: "厂级经营台", roleLabel: "厂长 / 生产总监", focus: "关注产线负荷、交期、质量和关键例外，不替代现场审批。", kind: "planner", tone: "control", actions: [
    { to: "/dashboard", label: "经营看板", description: "查看产出、质量、库存和风险", icon: Gauge, permission: "DASHBOARD_VIEW" },
    { to: "/trace", label: "订单追溯", description: "查看关键订单进度与例外", icon: Factory, permission: "TRACE_VIEW" }
  ] },
  METALLURGY_LAB: { title: "冶金实验台", roleLabel: "冶金 / 实验室人员", focus: "关联炉前检验、试样与工艺卡要求，形成质量追溯依据。", kind: "quality", tone: "quality", actions: [
    { to: "/quality", label: "检验记录", description: "查看和录入已授权的检验结果", icon: ShieldCheck, permission: "QUALITY_MANAGE" },
    { to: "/documents", label: "工艺要求", description: "查看相关工艺卡", icon: BookOpenCheck, permission: "PROCESS_CARD_VIEW" }
  ] },
  MAINTENANCE_EHS: { title: "设备与 EHS 工作台", roleLabel: "设备 / EHS 人员", focus: "围绕设备点检、安全风险和现场待办完成支持工作。", kind: "engineering", tone: "engineering", actions: [
    { to: "/guide", label: "现场指引", description: "查看工序安全要求与交接规则", icon: BookOpenCheck },
    { to: "/workbench", label: "岗位待办", description: "回到本岗位任务概览", icon: ClipboardCheck }
  ] },
  DELIVERY_COORDINATOR: { title: "交付协调台", roleLabel: "交付协调员", focus: "协调成品备货、运输、运单和客户签收信息。", kind: "warehouse", tone: "warehouse", actions: [
    { to: "/fulfillment", label: "物流交付", description: "跟踪备货、发运和签收", icon: PackageCheck, permission: ["FULFILLMENT_MANAGE", "LOGISTICS_MANAGE"] },
    { to: "/documents", label: "交付文件", description: "输出授权订单和交付文件", icon: ClipboardCheck, permission: "PRINT_SENSITIVE_ORDER" }
  ] },
  GENERAL_MANAGER: { title: "总经理决策台", roleLabel: "总经理", focus: "查看经营、产线、交期、质量与交付风险，推动管理决策而不替代现场执行。", kind: "planner", tone: "control", actions: [
		{ to: "/customers", label: "客户建档复核", description: "查询客户并审批待处理的建档申请", icon: ClipboardCheck, permission: "CUSTOMER_CHANGE_APPROVE" },
    { to: "/dashboard", label: "经营与生产看板", description: "查看订单、产出、质量和交期风险", icon: Gauge, permission: "DASHBOARD_VIEW" },
		{ to: "/report-ledger", label: "全厂报工总账", description: "按订单、员工、工序与日期复核生产事实", icon: ClipboardList, permission: "DASHBOARD_VIEW" },
    { to: "/trace", label: "订单全程追溯", description: "查看订单在制进度与关键异常", icon: Factory, permission: "TRACE_VIEW" },
    { to: "/documents", label: "受控经营文件", description: "输出授权统计和订单文件", icon: ClipboardCheck, permission: ["PRINT_STATISTICS", "PRINT_SENSITIVE_ORDER"] }
  ] },
	CUSTOMER_MANAGER: { title: "客户经理复核台", roleLabel: "客户经理", focus: "复核客户建档与订单商务信息，一次复核后交由工程确认和生产执行。", kind: "admin", tone: "system", actions: [
		{ to: "/customers", label: "客户建档复核", description: "查询客户并审批待处理的建档申请", icon: ClipboardCheck, permission: "CUSTOMER_CHANGE_APPROVE" },
		{ to: "/orders", label: "订单复核", description: "复核客户、交期和商务备注", icon: ClipboardList, permission: "ORDER_MANAGE" }
	] },
	SALES_REP: { title: "销售工作台", roleLabel: "销售人员", focus: "跟进本人客户、订单商务复核状态与按已复核订单统计的成交业绩。", kind: "admin", tone: "system", actions: [
		{ to: "/sales", label: "我的销售业绩", description: "查看本人已复核订单额、客户数与近六个月趋势", icon: Gauge, permission: "SALES_PERFORMANCE_VIEW" },
		{ to: "/orders", label: "客户订单", description: "录入订单并跟进工程、商务复核和生产进度", icon: ClipboardList, permission: "ORDER_MANAGE" },
		{ to: "/trace", label: "订单追溯", description: "查看本人订单的生产与交付状态", icon: Factory, permission: "TRACE_VIEW" }
	] },
  WAREHOUSE_CLERK: { title: "仓库协同台", roleLabel: "仓库协同员", focus: "按照分配的仓库范围处理收发、盘点和领用协同。", kind: "warehouse", tone: "warehouse", actions: [
    { to: "/inventory", label: "库存作业", description: "查看已授权仓库的收发记录", icon: Warehouse, permission: "INVENTORY_MANAGE" },
    { to: "/molds", label: "模具领用", description: "处理授权范围内的模具出入库", icon: Boxes, permission: "MOLD_ISSUE" }
  ] }
};

const fallbackProfile: RoleWorkspaceProfile = {
  title: "协同工作台",
  roleLabel: "业务协同角色",
  focus: "按当前账户拥有的权限进入相应业务模块。",
  kind: "admin",
  tone: "system",
  actions: [
    { to: "/guide", label: "流程指南", description: "查看角色职责与标准流程", icon: BookOpenCheck },
    { to: "/dashboard", label: "生产概览", description: "查看已授权的业务概览", icon: Gauge, permission: "DASHBOARD_VIEW" }
  ]
};

export function roleWorkspaceFor(user: AccessUser): RoleWorkspaceProfile {
  const specializedRole = user.primaryRole === "OPERATOR"
    ? user.roles.find((role) => role !== "OPERATOR" && profiles[role] != null)
    : undefined;
  return profiles[specializedRole ?? user.primaryRole] ?? fallbackProfile;
}
