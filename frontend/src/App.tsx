import { lazy, Suspense, useEffect, useMemo, useState, type ReactNode } from "react";
import {
	AlertTriangle,
	BellRing,
  BadgeDollarSign,
  BookOpenCheck,
  Boxes,
  ClipboardList,
  FileOutput,
  Factory,
  Gauge,
  KeyRound,
  ListChecks,
  LogOut,
  Map,
  Menu,
  MonitorUp,
  PackageCheck,
  PackageSearch,
  QrCode,
  Route,
  Rows3,
  Settings2,
  ShieldCheck,
  Link2,
  ScanLine,
  Truck,
  UserRound,
  Warehouse,
  Workflow,
  Wrench,
  X
} from "lucide-react";
import { NavLink, Navigate, Route as RouterRoute, Routes, useLocation } from "react-router-dom";
import { api, ApiError, type AuthSession } from "./api";
import { ErrorNotice, LoadingState } from "./components/ui";
import { RouteErrorBoundary } from "./components/RouteErrorBoundary";
import { useAsyncData } from "./hooks";
import { ChangePasswordPage, LoginPage } from "./pages/LoginPage";
import { roleWorkspaceFor } from "./pages/roleWorkspace";
import type { AccessUser } from "./types";

const AccessControlPage = lazy(() => import("./pages/AccessControlPage").then((module) => ({ default: module.AccessControlPage })));
const AdministrationPage = lazy(() => import("./pages/AdministrationPage").then((module) => ({ default: module.AdministrationPage })));
const AssetQrBindingPage = lazy(() => import("./pages/AssetQrBindingPage").then((module) => ({ default: module.AssetQrBindingPage })));
const AssetQrManagementPage = lazy(() => import("./pages/AssetQrManagementPage").then((module) => ({ default: module.AssetQrManagementPage })));
const DocumentCenterPage = lazy(() => import("./pages/DocumentCenterPage").then((module) => ({ default: module.DocumentCenterPage })));
const FulfillmentPage = lazy(() => import("./pages/FulfillmentPage").then((module) => ({ default: module.FulfillmentPage })));
const InventoryPage = lazy(() => import("./pages/InventoryPage").then((module) => ({ default: module.InventoryPage })));
const MasterDataPage = lazy(() => import("./pages/MasterDataPage").then((module) => ({ default: module.MasterDataPage })));
const MoldRequestsPage = lazy(() => import("./pages/MoldRequestsPage").then((module) => ({ default: module.MoldRequestsPage })));
const OrderMoldSelectionPage = lazy(() => import("./pages/OrderMoldSelectionPage").then((module) => ({ default: module.OrderMoldSelectionPage })));
const OrdersPage = lazy(() => import("./pages/OrdersPage").then((module) => ({ default: module.OrdersPage })));
const OutsourcingPage = lazy(() => import("./pages/OutsourcingPage").then((module) => ({ default: module.OutsourcingPage })));
const PieceworkManagementPage = lazy(() => import("./pages/PieceworkManagementPage").then((module) => ({ default: module.PieceworkManagementPage })));
const PlatformPage = lazy(() => import("./pages/PlatformPage").then((module) => ({ default: module.PlatformPage })));
const ProcessGuidePage = lazy(() => import("./pages/ProcessGuidePage").then((module) => ({ default: module.ProcessGuidePage })));
const QualityPage = lazy(() => import("./pages/QualityPage").then((module) => ({ default: module.QualityPage })));
const SopManagementPage = lazy(() => import("./pages/SopManagementPage").then((module) => ({ default: module.SopManagementPage })));
const SchedulingPage = lazy(() => import("./pages/SchedulingPage").then((module) => ({ default: module.SchedulingPage })));
const ScanPage = lazy(() => import("./pages/ScanPage").then((module) => ({ default: module.ScanPage })));
const CartTransfersPage = lazy(() => import("./pages/CartTransfersPage").then((module) => ({ default: module.CartTransfersPage })));
const CustomerDirectoryPage = lazy(() => import("./pages/CustomerDirectoryPage").then((module) => ({ default: module.CustomerDirectoryPage })));
const CustomerProductMoldRelationsPage = lazy(() => import("./pages/CustomerProductMoldRelationsPage").then((module) => ({ default: module.CustomerProductMoldRelationsPage })));
const FurnaceBatchesPage = lazy(() => import("./pages/FurnaceBatchesPage").then((module) => ({ default: module.FurnaceBatchesPage })));
const HandoffExceptionsPage = lazy(() => import("./pages/HandoffExceptionsPage").then((module) => ({ default: module.HandoffExceptionsPage })));
const ProcessCardTemplatesPage = lazy(() => import("./pages/ProcessCardTemplatesPage").then((module) => ({ default: module.ProcessCardTemplatesPage })));
const TasksPage = lazy(() => import("./pages/TasksPage").then((module) => ({ default: module.TasksPage })));
const PaperReportsPage = lazy(() => import("./pages/PaperReportsPage").then((module) => ({ default: module.PaperReportsPage })));
const TracePage = lazy(() => import("./pages/TracePage").then((module) => ({ default: module.TracePage })));
const WorkOrdersPage = lazy(() => import("./pages/WorkOrdersPage").then((module) => ({ default: module.WorkOrdersPage })));
const RoleWorkspacePage = lazy(() => import("./pages/RoleWorkspacePage").then((module) => ({ default: module.RoleWorkspacePage })));
const WorkerWorkbenchPage = lazy(() => import("./pages/WorkerWorkbenchPage").then((module) => ({ default: module.WorkerWorkbenchPage })));
const ProductionAlertsPage = lazy(() => import("./pages/ProductionAlertsPage").then((module) => ({ default: module.ProductionAlertsPage })));
const ReportLedgerPage = lazy(() => import("./pages/ReportLedgerPage").then((module) => ({ default: module.ReportLedgerPage })));
const NotificationCenterPage = lazy(() => import("./pages/NotificationCenterPage").then((module) => ({ default: module.NotificationCenterPage })));

const DashboardPage = lazy(() =>
  import("./pages/DashboardPage").then((module) => ({ default: module.DashboardPage }))
);
const WorkshopDisplayPage = lazy(() =>
  import("./pages/WorkshopDisplayPage").then((module) => ({ default: module.WorkshopDisplayPage }))
);
const SalesManagementPage = lazy(() =>
  import("./pages/SalesManagementPage").then((module) => ({ default: module.SalesManagementPage }))
);

type NavigationItem = {
  to: string;
  label: string;
  icon: typeof Gauge;
  permission?: string | string[];
};

const navigation: NavigationItem[] = [
  { to: "/workbench", label: "我的工作台", icon: ClipboardList },
  { to: "/guide", label: "流程指南", icon: Map },
	{ to: "/notifications", label: "消息中心", icon: BellRing, permission: "NOTIFICATION_VIEW" },
  { to: "/dashboard", label: "生产概览", icon: Gauge, permission: "DASHBOARD_VIEW" },
  { to: "/workshop-display", label: "车间大屏", icon: MonitorUp, permission: "WORKSHOP_DISPLAY_VIEW" },
  { to: "/orders", label: "客户订单", icon: ClipboardList, permission: "ORDER_MANAGE" },
  { to: "/sales", label: "销售管理", icon: BadgeDollarSign, permission: "SALES_PERFORMANCE_VIEW" },
  { to: "/customers", label: "客户管理", icon: UserRound, permission: "CUSTOMER_VIEW" },
  { to: "/work-orders", label: "工单批次", icon: Boxes, permission: "PLANNING_VIEW" },
  { to: "/scheduling", label: "三线排产", icon: Rows3, permission: "PLANNING_VIEW" },
  { to: "/tasks", label: "生产派工", icon: ListChecks, permission: "TASK_DISPATCH" },
	{ to: "/report-ledger", label: "报工总账", icon: ClipboardList, permission: ["TASK_DISPATCH", "DASHBOARD_VIEW", "PLATFORM_ADMIN"] },
	{ to: "/paper-reports", label: "纸质报工", icon: ClipboardList, permission: "MANUAL_REPORT_REVIEW" },
	{ to: "/handoff-exceptions", label: "交接异常", icon: ShieldCheck, permission: "TASK_DISPATCH" },
	{ to: "/furnace-batches", label: "炉次台账", icon: Factory, permission: "TASK_DISPATCH" },
  { to: "/cart-transfers", label: "周转车流转", icon: Truck, permission: "CART_OPERATE" },
  { to: "/quality", label: "质量管理", icon: ShieldCheck, permission: "QUALITY_MANAGE" },
  { to: "/inventory", label: "仓库管理", icon: Warehouse, permission: ["INVENTORY_MANAGE", "MOLD_WAREHOUSE_MANAGE", "RAW_MATERIAL_WAREHOUSE_MANAGE", "FINISHED_GOODS_WAREHOUSE_MANAGE"] },
  { to: "/molds", label: "模具领用", icon: Boxes, permission: ["MOLD_REQUEST", "MOLD_ISSUE", "MOLD_RECEIVE"] },
  { to: "/order-molds", label: "订单模具选定", icon: Boxes, permission: "ORDER_MOLD_SELECT" },
  { to: "/fulfillment", label: "成品物流交付", icon: PackageCheck, permission: ["FULFILLMENT_MANAGE", "LOGISTICS_MANAGE"] },
  { to: "/piecework", label: "计件管理", icon: BadgeDollarSign, permission: "PIECEWORK_MANAGE" },
  { to: "/outsourcing", label: "外协管理", icon: Truck, permission: "OUTSOURCING_MANAGE" },
  { to: "/sops", label: "SOP 管理", icon: BookOpenCheck, permission: "SOP_MANAGE" },
  { to: "/documents", label: "文档打印", icon: FileOutput, permission: ["PROCESS_CARD_VIEW", "PRINT_WORKSHOP_DOCUMENT", "PRINT_SENSITIVE_ORDER", "PRINT_STATISTICS"] },
  {
    to: "/platform",
    label: "审批配置",
    icon: Workflow,
    permission: ["WORKFLOW_MANAGE", "CONFIG_MANAGE"]
  },
  { to: "/administration", label: "平台运维", icon: Wrench, permission: "PLATFORM_ADMIN" },
  { to: "/asset-qrs", label: "资产二维码", icon: QrCode, permission: "QR_MANAGE" },
  { to: "/qr-bind", label: "扫码绑定", icon: ScanLine, permission: "QR_BIND" },
  { to: "/access", label: "账户权限", icon: KeyRound, permission: "ACCESS_MANAGE" },
  { to: "/trace", label: "订单追溯", icon: PackageSearch, permission: "TRACE_VIEW" },
	{ to: "/master-data", label: "产品与路线", icon: Settings2, permission: "MASTERDATA_MANAGE" },
	{ to: "/customer-product-molds", label: "客户产品模具", icon: Link2, permission: "MASTERDATA_MANAGE" },
	{ to: "/process-card-templates", label: "产品工艺模板", icon: BookOpenCheck, permission: "PROCESS_CARD_TEMPLATE_MANAGE" }
	, { to: "/production-alerts", label: "订单缺口预警", icon: AlertTriangle, permission: "NOTIFICATION_VIEW" },
];

const sidebarNavigationGroups = [
  { label: "工作台", paths: ["/workbench", "/guide", "/notifications", "/dashboard", "/workshop-display"] },
  { label: "生产执行", paths: ["/orders", "/work-orders", "/scheduling", "/tasks", "/report-ledger", "/paper-reports", "/handoff-exceptions", "/furnace-batches", "/cart-transfers", "/quality", "/production-alerts", "/trace"] },
  { label: "仓储交付", paths: ["/inventory", "/molds", "/order-molds", "/fulfillment", "/outsourcing", "/piecework"] },
  { label: "工程资料", paths: ["/master-data", "/customer-product-molds", "/process-card-templates", "/sops", "/documents"] },
  { label: "管理设置", paths: ["/customers", "/sales", "/platform", "/administration", "/asset-qrs", "/qr-bind", "/access"] }
];

function canAccess(user: AccessUser, permission: string | string[]) {
  const codes = Array.isArray(permission) ? permission : [permission];
  return codes.some((code) => user.permissions.includes(code));
}

function homeFor(user: AccessUser) {
  return (
    navigation.find((item) => item.permission && canAccess(user, item.permission))?.to ?? "/guide"
  );
}

function Guard({
  user,
  permission,
  children
}: {
  user: AccessUser;
  permission: string | string[];
  children: ReactNode;
}) {
  return canAccess(user, permission) ? <>{children}</> : <Navigate to={homeFor(user)} replace />;
}

export function App() {
  const config = useAsyncData(api.auth.config);
  if (config.loading) return <LoadingState label="正在连接系统" />;
  if (config.error || typeof config.data?.authenticationRequired !== "boolean") return <ErrorNotice error={config.error ?? new Error("无法读取登录配置")} onRetry={config.reload} />;
  return config.data.authenticationRequired ? <AuthenticatedWorkspace /> : <Workspace />;
}

function AuthenticatedWorkspace() {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [changingPassword, setChangingPassword] = useState(false);
  async function refresh() {
    setError(null);
    try { setSession(await api.auth.me()); }
    catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) setSession(null);
      else setError(caught);
    } finally { setLoading(false); }
  }
  useEffect(() => {
    void refresh();
    const expired = () => { setSession(null); setError(null); setChangingPassword(false); };
    window.addEventListener("mes:session-expired", expired);
    return () => window.removeEventListener("mes:session-expired", expired);
  }, []);
  if (loading) return <LoadingState label="正在验证登录状态" />;
  if (error) return <ErrorNotice error={error} onRetry={refresh} />;
  if (!session) return <LoginPage onAuthenticated={refresh} />;
  if (session.mustChangePassword || changingPassword) return <ChangePasswordPage session={session}
    onChanged={(next) => { setSession(next); setChangingPassword(false); }}
    onCancel={session.mustChangePassword ? undefined : () => setChangingPassword(false)} />;
  return <Workspace key={session.user.employeeCode} authenticatedUser={session.user} onChangePassword={() => setChangingPassword(true)} onLogout={async () => {
    try { await api.auth.logout(); setSession(null); } catch (caught) { setError(caught); }
  }} />;
}

function Workspace({ authenticatedUser, onLogout, onChangePassword }: { authenticatedUser?: AccessUser; onLogout?: () => Promise<void>; onChangePassword?: () => void }) {
  const location = useLocation();
  const usersState = useAsyncData(() => authenticatedUser ? Promise.resolve([authenticatedUser]) : api.access.users());
  const [operatorCode, setOperatorCode] = useState(
    () => localStorage.getItem("mes.operatorCode") || "W001"
  );
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const users = (Array.isArray(usersState.data) ? usersState.data : []).filter((user) => user.active);
  const currentUser =
    users.find((user) => user.employeeCode === operatorCode) ??
    users.find((user) => user.employeeCode === "W001") ??
    users[0];

  useEffect(() => {
    if (!currentUser) return;
    setOperatorCode(currentUser.employeeCode);
    if (!authenticatedUser) localStorage.setItem("mes.operatorCode", currentUser.employeeCode);
  }, [currentUser?.employeeCode]);

  useEffect(() => {
    if (!mobileMenuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMobileMenuOpen(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [mobileMenuOpen]);

  const visibleNavigation = useMemo(
    () =>
      currentUser
        ? navigation.filter(
            (item) => item.permission == null || canAccess(currentUser, item.permission)
          )
        : [],
    [currentUser]
  );
  const mobilePrimaryNavigation = useMemo(() => {
    const preferred = ["/workbench", "/guide", "/tasks", "/dashboard", "/inventory", "/quality", "/fulfillment"];
    return preferred
      .map((path) => visibleNavigation.find((item) => item.to === path))
      .filter((item): item is NavigationItem => item != null)
      .slice(0, 4);
  }, [visibleNavigation]);
  const mobileMoreNavigation = visibleNavigation.filter(
    (item) => !mobilePrimaryNavigation.some((primary) => primary.to === item.to)
  );

  if (usersState.loading) {
    return <LoadingState label="正在加载模拟用户与权限" />;
  }
  if (usersState.error || !currentUser) {
    return <ErrorNotice error={usersState.error ?? new Error("没有可用的模拟用户")} onRetry={usersState.reload} />;
  }

  const operatorProps = { operatorCode: currentUser.employeeCode };
  const roleWorkspace = roleWorkspaceFor(currentUser);
  return (
    <div
      className={`app-shell role-shell role-shell-${roleWorkspace.tone} ${roleWorkspace.kind === "operator" ? "worker-shell" : ""} ${location.pathname === "/workshop-display" ? "workshop-display-shell" : ""}`}
      data-role-kind={roleWorkspace.kind}
    >
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <Factory />
          </span>
          <div>
            <strong>铸造 MES</strong>
            <span>生产执行中心</span>
          </div>
        </div>
        <nav aria-label="主导航">
          {sidebarNavigationGroups.map((group) => {
            const items = group.paths.map((path) => visibleNavigation.find((item) => item.to === path)).filter((item): item is NavigationItem => item != null);
            if (items.length === 0) return null;
            return <div className="sidebar-nav-group" key={group.label}><span className="sidebar-nav-group-label">{group.label}</span>{items.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to}>
                <Icon aria-hidden="true" />
                <span>{label}</span>
              </NavLink>
            ))}</div>;
          })}
        </nav>
        <div className="sidebar-foot">
          <Route aria-hidden="true" />
          <span>中温蜡示范工厂 · G2</span>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="environment">
            <span className="health-dot" aria-hidden="true" />
            <span>{authenticatedUser ? "账号受控访问" : "本地开发环境"}</span>
          </div>
          <div className="operator-control account-control">
            <span className="account-avatar small" aria-hidden="true">
              {currentUser.name.slice(0, 1)}
            </span>
            <span className="account-copy">
              <strong>{currentUser.name}</strong>
              <small>{roleWorkspace.roleLabel} · {currentUser.roles.length} 个角色</small>
            </span>
            {authenticatedUser ? <><button className="button" type="button" onClick={onChangePassword} title="修改密码" aria-label="修改密码"><KeyRound aria-hidden="true" /></button><button className="button" type="button" onClick={onLogout} title="退出登录" aria-label="退出登录"><LogOut aria-hidden="true" /></button></> : <select
              aria-label="当前模拟用户"
              value={currentUser.employeeCode}
              onChange={(event) => setOperatorCode(event.target.value)}
            >
              {users.map((user) => (
                <option key={user.employeeCode} value={user.employeeCode}>
                  {user.employeeCode} · {user.name} · {roleWorkspaceFor(user).roleLabel}
                </option>
              ))}
            </select>}
          </div>
        </header>

        <main>
          <RouteErrorBoundary key={`${currentUser.employeeCode}:${location.pathname}`}>
          <Suspense fallback={<LoadingState label="正在加载页面" />}>
            <Routes>
              <RouterRoute
                path="/workbench"
                element={<RoleWorkspacePage user={currentUser} />}
              />
              <RouterRoute
                path="/operator-tasks"
                element={<Guard user={currentUser} permission="TASK_EXECUTE"><WorkerWorkbenchPage user={currentUser} /></Guard>}
              />
              <RouterRoute path="/scan" element={<ScanPage user={currentUser} />} />
              <RouterRoute path="/workshop-display" element={<Guard user={currentUser} permission="WORKSHOP_DISPLAY_VIEW"><WorkshopDisplayPage user={currentUser} /></Guard>} />
              <RouterRoute
                path="/dashboard"
                element={
                  <Guard user={currentUser} permission="DASHBOARD_VIEW">
                    <DashboardPage />
                  </Guard>
                }
              />
              <RouterRoute path="/guide" element={<ProcessGuidePage user={currentUser} />} />
              <RouterRoute path="/customers" element={<Guard user={currentUser} permission="CUSTOMER_VIEW"><CustomerDirectoryPage user={currentUser} /></Guard>} />
              <RouterRoute
                path="/orders"
                element={
                  <Guard user={currentUser} permission="ORDER_MANAGE">
                    <OrdersPage user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/sales"
                element={
                  <Guard user={currentUser} permission="SALES_PERFORMANCE_VIEW">
                    <SalesManagementPage user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/work-orders"
                element={
                  <Guard user={currentUser} permission="PLANNING_VIEW">
                    <WorkOrdersPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/scheduling"
                element={
                  <Guard user={currentUser} permission="PLANNING_VIEW">
                    <SchedulingPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/tasks"
                element={
                  <Guard user={currentUser} permission="TASK_DISPATCH">
                    <TasksPage {...operatorProps} />
                  </Guard>
                }
              />
			  <RouterRoute path="/report-ledger" element={<Guard user={currentUser} permission={["TASK_DISPATCH", "DASHBOARD_VIEW", "PLATFORM_ADMIN"]}><ReportLedgerPage {...operatorProps} /></Guard>} />
			  <RouterRoute path="/notifications" element={<Guard user={currentUser} permission="NOTIFICATION_VIEW"><NotificationCenterPage user={currentUser} /></Guard>} />
			  <RouterRoute path="/paper-reports" element={<Guard user={currentUser} permission="MANUAL_REPORT_REVIEW"><PaperReportsPage {...operatorProps} /></Guard>} />
			  <RouterRoute path="/handoff-exceptions" element={<Guard user={currentUser} permission="TASK_DISPATCH"><HandoffExceptionsPage {...operatorProps} /></Guard>} />
			  <RouterRoute path="/furnace-batches" element={<Guard user={currentUser} permission="TASK_DISPATCH"><FurnaceBatchesPage {...operatorProps} /></Guard>} />
              <RouterRoute
                path="/cart-transfers"
                element={
                  <Guard user={currentUser} permission="CART_OPERATE">
                    <CartTransfersPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/quality"
                element={
                  <Guard user={currentUser} permission="QUALITY_MANAGE">
                    <QualityPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/inventory"
                element={
                  <Guard user={currentUser} permission={["INVENTORY_MANAGE", "MOLD_WAREHOUSE_MANAGE", "RAW_MATERIAL_WAREHOUSE_MANAGE", "FINISHED_GOODS_WAREHOUSE_MANAGE"]}>
                    <InventoryPage user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/molds"
                element={<Guard user={currentUser} permission={["MOLD_REQUEST", "MOLD_ISSUE", "MOLD_RECEIVE"]}><MoldRequestsPage operatorCode={currentUser.employeeCode} user={currentUser} /></Guard>}
              />
              <RouterRoute
                path="/order-molds"
                element={<Guard user={currentUser} permission="ORDER_MOLD_SELECT"><OrderMoldSelectionPage user={currentUser} /></Guard>}
              />
              <RouterRoute
                path="/fulfillment"
                element={
                  <Guard user={currentUser} permission={["FULFILLMENT_MANAGE", "LOGISTICS_MANAGE"]}>
                    <FulfillmentPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/piecework"
                element={
                  <Guard user={currentUser} permission="PIECEWORK_MANAGE">
                    <PieceworkManagementPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/outsourcing"
                element={
                  <Guard user={currentUser} permission="OUTSOURCING_MANAGE">
                    <OutsourcingPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/sops"
                element={
                  <Guard user={currentUser} permission="SOP_MANAGE">
                    <SopManagementPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/documents"
                element={
                  <Guard user={currentUser} permission={["PROCESS_CARD_VIEW", "PRINT_WORKSHOP_DOCUMENT", "PRINT_SENSITIVE_ORDER", "PRINT_STATISTICS"]}>
                    <DocumentCenterPage user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/platform"
                element={
                  <Guard user={currentUser} permission={["WORKFLOW_MANAGE", "CONFIG_MANAGE"]}>
                    <PlatformPage {...operatorProps} user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/administration"
                element={
                  <Guard user={currentUser} permission="PLATFORM_ADMIN">
                    <AdministrationPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/asset-qrs"
                element={
                  <Guard user={currentUser} permission="QR_MANAGE">
                    <AssetQrManagementPage {...operatorProps} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/qr-bind"
                element={
                  <Guard user={currentUser} permission="QR_BIND">
                    <AssetQrBindingPage {...operatorProps} user={currentUser} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/access"
                element={
                  <Guard user={currentUser} permission="ACCESS_MANAGE">
                    <AccessControlPage operatorCode={currentUser.employeeCode} />
                  </Guard>
                }
              />
              <RouterRoute
                path="/trace"
                element={
                  <Guard user={currentUser} permission="TRACE_VIEW">
                    <TracePage />
                  </Guard>
                }
              />
              <RouterRoute
                path="/master-data"
                element={
                  <Guard user={currentUser} permission="MASTERDATA_MANAGE">
                    <MasterDataPage user={currentUser} />
                  </Guard>
                }
              />
			  <RouterRoute path="/customer-product-molds" element={<Guard user={currentUser} permission="MASTERDATA_MANAGE"><CustomerProductMoldRelationsPage user={currentUser} /></Guard>} />
			  <RouterRoute path="/process-card-templates" element={<Guard user={currentUser} permission="PROCESS_CARD_TEMPLATE_MANAGE"><ProcessCardTemplatesPage {...operatorProps} /></Guard>} />
              <RouterRoute path="*" element={<Navigate to={homeFor(currentUser)} replace />} />
			  <RouterRoute path="/production-alerts" element={<Guard user={currentUser} permission="NOTIFICATION_VIEW"><ProductionAlertsPage user={currentUser} /></Guard>} />
            </Routes>
          </Suspense>
          </RouteErrorBoundary>
        </main>
      </div>
      <nav className="mobile-tabbar" aria-label="移动端主导航">
        {mobilePrimaryNavigation.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} onClick={() => setMobileMenuOpen(false)}>
            <Icon aria-hidden="true" />
            <span>{label}</span>
          </NavLink>
        ))}
        {mobileMoreNavigation.length > 0 && (
          <button
            type="button"
            aria-expanded={mobileMenuOpen}
            aria-controls="mobile-nav-sheet"
            aria-label="更多功能"
            onClick={() => setMobileMenuOpen((open) => !open)}
          >
            <Menu aria-hidden="true" />
            <span>更多</span>
          </button>
        )}
      </nav>
      {mobileMenuOpen && (
        <div className="mobile-nav-backdrop" onMouseDown={() => setMobileMenuOpen(false)}>
          <nav
            id="mobile-nav-sheet"
            className="mobile-nav-sheet"
            aria-label="更多功能菜单"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header>
              <div><strong>全部功能</strong><span>{roleWorkspace.roleLabel}</span></div>
              <button type="button" className="icon-button" aria-label="关闭更多功能" onClick={() => setMobileMenuOpen(false)}>
                <X aria-hidden="true" />
              </button>
            </header>
            {visibleNavigation.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} onClick={() => setMobileMenuOpen(false)}>
                <Icon aria-hidden="true" />
                <span>{label}</span>
              </NavLink>
            ))}
          </nav>
        </div>
      )}
    </div>
  );
}
