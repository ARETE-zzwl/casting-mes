import { FormEvent, useEffect, useMemo, useState } from "react";
import { ArrowDownToLine, Box, Boxes, ClipboardCheck, PackageCheck, RefreshCw, ScanLine, Truck, Wrench } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatDate, formatQuantity, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, DeliveryOrder, FinishedGoodsLot, InventoryBalance, InventoryExportJob, InventoryMovement, MoldExternalMovement, MoldLifecycle, MoldRequest, PageResult, ResourceAsset } from "../types";

type WarehouseProfile = {
  code: string;
  title: string;
  description: string;
  permission: string;
  itemLabel: string;
  unit: string;
  icon: typeof Box;
  relatedPath?: string;
  relatedLabel?: string;
};

type WarehouseAction = {
  key: string;
  label: string;
  movementType?: "RECEIPT" | "ISSUE" | "ADJUSTMENT_IN" | "ADJUSTMENT_OUT";
  referenceType: string;
  hint: string;
  stocktake?: boolean;
};

const warehouses: WarehouseProfile[] = [
  { code: "MOLD-01", title: "模具仓", description: "模具在库、内部领用归还、客户模具和维修保养。", permission: "MOLD_WAREHOUSE_MANAGE", itemLabel: "模具资产", unit: "SET", icon: Wrench, relatedPath: "/molds", relatedLabel: "模具领用台" },
  { code: "RM-01", title: "原材料仓", description: "原材料批次收发、备料、投料和按实物数量盘点。", permission: "RAW_MATERIAL_WAREHOUSE_MANAGE", itemLabel: "原材料", unit: "KG", icon: Boxes },
  { code: "FG-01", title: "成品仓", description: "成品与待检暂存、成品入库、备货、物流和交付。", permission: "FINISHED_GOODS_WAREHOUSE_MANAGE", itemLabel: "产品或批次", unit: "PCS", icon: PackageCheck, relatedPath: "/fulfillment", relatedLabel: "成品交付台" }
];

const movementLabels: Record<string, string> = { RECEIPT: "入库", ISSUE: "出库", ADJUSTMENT_IN: "盘盈调整", ADJUSTMENT_OUT: "盘亏调整" };

const warehouseActions: WarehouseAction[] = [
    { key: "purchase-receipt", label: "采购收货入库", movementType: "RECEIPT", referenceType: "采购送货单", hint: "登记供应商送货单号、材料批次和实收重量。" },
    { key: "production-issue", label: "生产领料 / 投料", movementType: "ISSUE", referenceType: "投料单", hint: "按投料单发料，库存不足时系统会阻止出库。" },
    { key: "return-receipt", label: "余料退库", movementType: "RECEIPT", referenceType: "退料单", hint: "将车间未使用的余料退回原材料仓。" },
  { key: "physical-stocktake", label: "按实物盘点", referenceType: "盘点单", hint: "填写实盘数量，系统自动判断盘盈或盘亏。", stocktake: true }
];

function emptyPage<T>(): PageResult<T> {
  return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
}

function useDebouncedValue<T>(value: T, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

function hasAnyPermission(user: AccessUser, permissions: string[]) {
  return user.permissions.includes("INVENTORY_MANAGE") || permissions.some((permission) => user.permissions.includes(permission));
}

export function InventoryPage({ user }: { user: AccessUser }) {
  const canManageMolds = hasAnyPermission(user, ["MOLD_WAREHOUSE_MANAGE"]);
  const canManageFinishedGoods = hasAnyPermission(user, ["FINISHED_GOODS_WAREHOUSE_MANAGE", "FULFILLMENT_MANAGE", "LOGISTICS_MANAGE"]);
  const allowedWarehouses = useMemo(() => {
    if (user.permissions.includes("INVENTORY_MANAGE")) return warehouses;
    return warehouses.filter((warehouse) => user.permissions.includes(warehouse.permission));
  }, [user.permissions]);
  const [activeCode, setActiveCode] = useState(() => allowedWarehouses[0]?.code ?? "RM-01");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [selectedAction, setSelectedAction] = useState<WarehouseAction>(warehouseActions[0]);
  const [itemCode, setItemCode] = useState("");
  const [itemName, setItemName] = useState("");
  const [unit, setUnit] = useState("PCS");
  const [stockQuery, setStockQuery] = useState("");
  const [stockUnit, setStockUnit] = useState("ALL");
  const [stockLevel, setStockLevel] = useState("ALL");
  const [stockPage, setStockPage] = useState(0);
  const [movementQuery, setMovementQuery] = useState("");
  const [movementType, setMovementType] = useState("ALL");
  const [movementOperator, setMovementOperator] = useState("");
  const [movementFrom, setMovementFrom] = useState("");
  const [movementTo, setMovementTo] = useState("");
  const [movementPageNumber, setMovementPage] = useState(0);
  const [exportJob, setExportJob] = useState<InventoryExportJob | null>(null);
  const [exportError, setExportError] = useState<unknown>(null);
  const active = allowedWarehouses.find((warehouse) => warehouse.code === activeCode) ?? allowedWarehouses[0];
  const rawWarehouseActive = active?.code === "RM-01";
  const debouncedStockQuery = useDebouncedValue(stockQuery);
  const debouncedMovementQuery = useDebouncedValue(movementQuery);
  const debouncedItemCode = useDebouncedValue(itemCode);
  const contextState = useAsyncData(async () => {
    const moldData = canManageMolds ? await Promise.all([api.molds.list(), api.molds.externalMovements(), api.molds.maintenanceOverdue(), api.molds.lifecycle()]) : [[], [], [], []] as [MoldRequest[], MoldExternalMovement[], MoldExternalMovement[], MoldLifecycle[]];
    const [moldRequests, externalMovements, overdueExternalMovements, moldLifecycle] = moldData;
    return { moldRequests, externalMovements, overdueExternalMovements, moldLifecycle };
  }, [user.employeeCode, canManageMolds, canManageFinishedGoods]);
  const ledgerState = useAsyncData(async () => {
    if (!rawWarehouseActive || !active) return { balancePage: emptyPage<InventoryBalance>(), movementPage: emptyPage<InventoryMovement>() };
    const [balancePage, movementPage] = await Promise.all([
      api.inventory.balancePage({ viewerCode: user.employeeCode, warehouseCode: active.code, keyword: debouncedStockQuery, unit: stockUnit, stockStatus: stockLevel, page: stockPage }),
      api.inventory.movementPage({ viewerCode: user.employeeCode, warehouseCode: active.code, keyword: debouncedMovementQuery, movementType, operatorCode: movementOperator, fromDate: movementFrom, toDate: movementTo, page: movementPageNumber })
    ]);
    return { balancePage, movementPage };
  }, [user.employeeCode, active?.code, rawWarehouseActive, debouncedStockQuery, stockUnit, stockLevel, stockPage, debouncedMovementQuery, movementType, movementOperator, movementFrom, movementTo, movementPageNumber]);
  const itemLookupState = useAsyncData(async () => {
    if (!rawWarehouseActive || !active || !debouncedItemCode.trim()) return emptyPage<InventoryBalance>();
    return api.inventory.balancePage({ viewerCode: user.employeeCode, warehouseCode: active.code, keyword: debouncedItemCode, page: 0, size: 8 });
  }, [user.employeeCode, active?.code, rawWarehouseActive, debouncedItemCode]);

  useEffect(() => { if (active && !allowedWarehouses.some((warehouse) => warehouse.code === activeCode)) setActiveCode(active.code); }, [active, activeCode, allowedWarehouses]);
  useEffect(() => {
	if (!active) return;
		setSelectedAction(warehouseActions[0]);
		setItemCode(""); setItemName(""); setUnit(active.unit); setStockPage(0); setMovementPage(0);
	}, [active?.code]);
  useEffect(() => {
		const matchingBalance = (itemLookupState.data?.items ?? []).find((item) => item.itemCode.toLowerCase() === itemCode.trim().toLowerCase());
		if (matchingBalance) { setItemName(matchingBalance.itemName); setUnit(matchingBalance.unit); }
	}, [itemLookupState.data, itemCode]);
  useEffect(() => {
    if (!exportJob || !["PENDING", "RUNNING"].includes(exportJob.status)) return;
    let cancelled = false;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const next = await api.inventory.exportStatus(exportJob.id, user.employeeCode);
        if (cancelled) return;
        setExportJob(next);
        if (["PENDING", "RUNNING"].includes(next.status)) timer = window.setTimeout(() => void poll(), 600);
      } catch (caught) {
        if (!cancelled) setExportError(caught);
      }
    };
    void poll();
    return () => { cancelled = true; if (timer !== undefined) window.clearTimeout(timer); };
  }, [exportJob?.id, exportJob?.status, user.employeeCode]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || (!selectedAction.movementType && !selectedAction.stocktake)) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const enteredQuantity = Number(form.get(selectedAction.stocktake ? "physicalQuantity" : "quantity"));
    const currentBalance = formBalances.find((item) => item.itemCode === itemCode && item.unit === unit);
    const delta = selectedAction.stocktake ? enteredQuantity - Number(currentBalance?.quantity ?? 0) : enteredQuantity;
    if (!Number.isFinite(enteredQuantity) || enteredQuantity < 0 || !Number.isFinite(delta) || delta === 0) {
      setError(new Error(selectedAction.stocktake ? "实盘数量与账面数量一致，无需生成盘点调整。" : "请输入大于 0 的数量。"));
      return;
    }
    setSaving(true); setError(null);
    try {
      await api.inventory.move({
        operationId: crypto.randomUUID(), warehouseCode: active.code, itemCode, itemName,
        unit,
        movementType: selectedAction.stocktake ? (delta > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT") : selectedAction.movementType!,
        quantity: Math.abs(delta),
        referenceType: selectedAction.referenceType, referenceNo: String(form.get("referenceNo") || ""), operatorCode: user.employeeCode, remark: String(form.get("remark") || "")
      });
      formElement.reset();
      setItemCode(""); setItemName(""); setUnit(active.unit);
      await Promise.all([ledgerState.reload(), itemLookupState.reload()]);
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  if (contextState.loading || rawWarehouseActive && ledgerState.loading) return <LoadingState label="正在加载仓库台账" />;
  if (contextState.error) return <ErrorNotice error={contextState.error} onRetry={contextState.reload} />;
  if (rawWarehouseActive && ledgerState.error) return <ErrorNotice error={ledgerState.error} onRetry={ledgerState.reload} />;
  if (!active) return <ErrorNotice error={new Error("当前账号未配置仓库权限")} />;

  const balanceResult = ledgerState.data?.balancePage ?? emptyPage<InventoryBalance>();
  const movementResult = ledgerState.data?.movementPage ?? emptyPage<InventoryMovement>();
  const balances = balanceResult.items;
  const movements = movementResult.items;
  const formBalances = itemLookupState.data?.items ?? [];
  const currentBalance = formBalances.find((item) => item.itemCode === itemCode && item.unit === unit);
  const Icon = active.icon;
  const stockUnits = [...new Set(balances.map((item) => item.unit))].sort();
  const movementOperators = [...new Set(movements.map((item) => item.operatorCode))].sort();
  function clearStockFilters() { setStockQuery(""); setStockUnit("ALL"); setStockLevel("ALL"); setStockPage(0); }
  function clearMovementFilters() { setMovementQuery(""); setMovementType("ALL"); setMovementOperator(""); setMovementFrom(""); setMovementTo(""); setMovementPage(0); }
  function reloadWarehouse() { void contextState.reload(); void ledgerState.reload(); }
  async function startMovementExport() {
    if (!rawWarehouseActive || !active) return;
    setExportError(null);
    try {
      const job = await api.inventory.createExport({ operationId: crypto.randomUUID(), viewerCode: user.employeeCode, warehouseCode: active.code, keyword: debouncedMovementQuery, movementType, operatorCode: movementOperator, fromDate: movementFrom, toDate: movementTo });
      setExportJob(job);
    } catch (caught) { setExportError(caught); }
  }
  async function downloadMovementExport() {
    if (!exportJob || exportJob.status !== "COMPLETED") return;
    try {
      const blob = await api.inventory.exportDownload(exportJob.id, user.employeeCode);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = exportJob.fileName ?? "inventory-movements.csv";
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (caught) { setExportError(caught); }
  }
  const exportControl = rawWarehouseActive ? <section className="warehouse-export-panel" aria-label="库存流水异步导出"><div><strong>库存流水导出</strong><small>按当前筛选条件由服务端异步生成，页面不会加载全部流水。</small></div><div className="header-actions"><button type="button" className="button button-secondary button-small" onClick={() => void startMovementExport()} disabled={Boolean(exportJob && ["PENDING", "RUNNING"].includes(exportJob.status))}><ArrowDownToLine aria-hidden="true" />{exportJob?.status === "RUNNING" || exportJob?.status === "PENDING" ? "正在生成" : "生成导出"}</button>{exportJob?.status === "COMPLETED" && <button type="button" className="button button-primary button-small" onClick={() => void downloadMovementExport()}>下载 CSV</button>}</div>{exportError != null && <ErrorNotice error={exportError} />}{exportJob && <small>任务 {exportJob.jobNo} · {exportJob.status === "COMPLETED" ? "已完成，可下载" : exportJob.status === "FAILED" ? `失败：${exportJob.errorMessage ?? "未知错误"}` : "后台处理中"}</small>}</section> : null;

  return <>
    {exportControl}
    <PageHeader title={active.title} description={active.description} action={<div className="header-actions"><Link className="button button-secondary" to="/scan"><ScanLine aria-hidden="true" />扫一扫</Link><button className="icon-button" type="button" onClick={reloadWarehouse} aria-label="刷新仓库数据"><RefreshCw aria-hidden="true" /></button></div>} />
    {error != null && <ErrorNotice error={error} />}
    <section className="warehouse-switcher" aria-label="授权仓库">
      {allowedWarehouses.map((warehouse) => {
        const WarehouseIcon = warehouse.icon;
        return <button key={warehouse.code} type="button" className={warehouse.code === active.code ? "active" : ""} aria-pressed={warehouse.code === active.code} onClick={() => setActiveCode(warehouse.code)}><WarehouseIcon aria-hidden="true" /><span><strong>{warehouse.title}</strong><small>{warehouse.code}</small></span></button>;
      })}
    </section>
    {active.code === "MOLD-01" ? <MoldWarehouseLedger viewerCode={user.employeeCode} requests={contextState.data?.moldRequests ?? []} overdue={contextState.data?.overdueExternalMovements ?? []} lifecycle={contextState.data?.moldLifecycle ?? []} /> : active.code === "FG-01" ? <FinishedGoodsWarehouseLedger viewerCode={user.employeeCode} /> : <>
      <section className="warehouse-action-grid" aria-label={`${active.title}快捷动作`}>
        {warehouseActions.map((action) => <button key={action.key} type="button" className={selectedAction.key === action.key ? "active" : ""} onClick={() => setSelectedAction(action)}><strong>{action.label}</strong><small>{action.hint}</small></button>)}
      </section>
      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading"><div><h2>{selectedAction.label}</h2><p>{selectedAction.stocktake ? "先选已有物料，再填写实盘数量；系统保留账面、差异和盘点单号。" : "确认后生成不可覆盖的收发流水，并更新当前余额。"}</p></div><Icon aria-hidden="true" /></header>
          <form className="compact-form" onSubmit={submit}>
            <div className="form-grid">
              <Field label="仓库"><input value={active.code} readOnly aria-readonly="true" /></Field>
              <Field label="业务类型"><input value={selectedAction.stocktake ? "按实物盘点" : movementLabels[selectedAction.movementType ?? ""]} readOnly aria-readonly="true" /></Field>
              <Field label={`${active.itemLabel}编码`} required><input name="itemCode" value={itemCode} list={`warehouse-items-${active.code}`} required maxLength={64} onChange={(event) => setItemCode(event.target.value)} placeholder="输入编码检索已有库存" /><datalist id={`warehouse-items-${active.code}`}>{formBalances.map((item) => <option key={item.id} value={item.itemCode}>{item.itemName} · {item.unit}</option>)}</datalist></Field>
              <Field label={`${active.itemLabel}名称`} required><input name="itemName" value={itemName} required maxLength={160} onChange={(event) => setItemName(event.target.value)} /></Field>
              {selectedAction.stocktake ? <Field label="账面数量"><input value={currentBalance ? `${formatQuantity(currentBalance.quantity)} ${currentBalance.unit}` : "请先选择已有物料"} readOnly aria-readonly="true" /></Field> : null}
              <Field label={selectedAction.stocktake ? "实盘数量" : "数量"} required><input name={selectedAction.stocktake ? "physicalQuantity" : "quantity"} type="number" min="0" step="0.001" required /></Field>
              <Field label="单位" required><input name="unit" value={unit} required maxLength={16} onChange={(event) => setUnit(event.target.value)} /></Field>
              <Field label="来源单号" required><input name="referenceNo" maxLength={64} required placeholder={selectedAction.stocktake ? "填写盘点单号" : "送货单、领料单或退料单号"} /></Field>
            </div>
            <Field label="备注"><input name="remark" maxLength={500} /></Field>
            <button className="button button-primary" disabled={saving}><ArrowDownToLine aria-hidden="true" />{saving ? "过账中" : selectedAction.stocktake ? "确认盘点并调整" : "确认过账"}</button>
          </form>
          {active.relatedPath && <Link className="button button-secondary warehouse-related-link" to={active.relatedPath}><ClipboardCheck aria-hidden="true" />{active.relatedLabel}</Link>}
        </div>
        <div className="table-pane">
          <header className="section-heading"><div><h2>当前余额</h2><p>服务端筛选结果 {balances.length} / 共 {balanceResult.totalElements} 个物料台账</p></div></header>
          <div className="warehouse-filter-bar" aria-label="库存筛选">
            <Field label="检索"><input value={stockQuery} placeholder="编码、名称或单位" onChange={(event) => { setStockQuery(event.target.value); setStockPage(0); }} /></Field>
            <Field label="单位"><input value={stockUnit === "ALL" ? "" : stockUnit} list="inventory-stock-units" placeholder="全部单位" onChange={(event) => { setStockUnit(event.target.value.toUpperCase() || "ALL"); setStockPage(0); }} /><datalist id="inventory-stock-units">{stockUnits.map((value) => <option key={value} value={value} />)}</datalist></Field>
            <Field label="库存状态"><select value={stockLevel} onChange={(event) => { setStockLevel(event.target.value); setStockPage(0); }}><option value="ALL">全部余额</option><option value="POSITIVE">有库存</option><option value="ZERO">零库存</option></select></Field>
            <button type="button" className="button button-secondary button-small warehouse-filter-clear" onClick={clearStockFilters}>清空</button>
          </div>
          {balanceResult.totalElements === 0 ? <EmptyState title={stockQuery || stockUnit !== "ALL" || stockLevel !== "ALL" ? "未找到库存" : "暂无库存余额"} description={stockQuery || stockUnit !== "ALL" || stockLevel !== "ALL" ? "请调整检索条件。" : "当前仓库尚未建立首笔收发记录。录入入库后会自动生成库存台账和可追溯流水。"} /> : <><div className="table-scroll"><table><thead><tr><th>编码</th><th>名称</th><th>余额</th><th>更新时间</th></tr></thead><tbody>{balances.map((item) => <tr key={item.id}><td className="primary-cell">{item.itemCode}</td><td>{item.itemName}</td><td><strong>{formatQuantity(item.quantity)} {item.unit}</strong></td><td>{formatDate(item.updatedAt, true)}</td></tr>)}</tbody></table></div><Pager page={balanceResult.page} totalPages={balanceResult.totalPages} onPageChange={setStockPage} /></>}
        </div>
      </section>
      <section className="section-block"><header className="section-heading"><div><h2>仓库流水</h2><p>服务端筛选结果 {movements.length} / 共 {movementResult.totalElements} 笔；按单号、物料、类型、责任人和日期追溯。</p></div></header><div className="warehouse-filter-bar warehouse-filter-bar-wide" aria-label="仓库流水筛选"><Field label="检索"><input value={movementQuery} placeholder="流水号、物料、单号、备注或操作人" onChange={(event) => { setMovementQuery(event.target.value); setMovementPage(0); }} /></Field><Field label="业务类型"><select value={movementType} onChange={(event) => { setMovementType(event.target.value); setMovementPage(0); }}><option value="ALL">全部类型</option>{Object.entries(movementLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></Field><Field label="操作人"><input value={movementOperator} list="inventory-movement-operators" placeholder="输入工号" onChange={(event) => { setMovementOperator(event.target.value); setMovementPage(0); }} /><datalist id="inventory-movement-operators">{movementOperators.map((value) => <option key={value} value={value} />)}</datalist></Field><Field label="开始日期"><input type="date" value={movementFrom} onChange={(event) => { setMovementFrom(event.target.value); setMovementPage(0); }} /></Field><Field label="结束日期"><input type="date" value={movementTo} onChange={(event) => { setMovementTo(event.target.value); setMovementPage(0); }} /></Field><button type="button" className="button button-secondary button-small warehouse-filter-clear" onClick={clearMovementFilters}>清空</button></div>{movementResult.totalElements === 0 ? <EmptyState title={movementQuery || movementType !== "ALL" || movementOperator || movementFrom || movementTo ? "未找到仓库流水" : "暂无仓库流水"} description={movementQuery || movementType !== "ALL" || movementOperator || movementFrom || movementTo ? "请调整筛选条件。" : "首笔入库、出库或盘点调整确认后会显示操作人、来源单号与结存变化。"} /> : <><div className="table-scroll"><table><thead><tr><th>流水号</th><th>类型</th><th>物料或资产</th><th>本次数量</th><th>结存</th><th>来源</th><th>操作人</th><th>时间</th></tr></thead><tbody>{movements.map((item) => <tr key={item.id}><td className="primary-cell">{item.movementNo}</td><td><StatusBadge value={item.movementType} /></td><td><strong>{item.itemCode}</strong><small>{item.itemName}</small></td><td>{formatQuantity(item.quantity)} {item.unit}</td><td>{formatQuantity(item.balanceAfter)} {item.unit}</td><td>{item.referenceType || "-"}<small>{item.referenceNo || "未填单号"}</small></td><td>{item.operatorCode}</td><td>{formatDate(item.occurredAt, true)}</td></tr>)}</tbody></table></div><Pager page={movementResult.page} totalPages={movementResult.totalPages} onPageChange={setMovementPage} /></>}</section>
    </>}
  </>;
}

function Pager({ page, totalPages, onPageChange }: { page: number; totalPages: number; onPageChange: (page: number) => void }) {
  if (totalPages <= 1) return null;
  return <nav className="warehouse-pagination" aria-label="库存分页"><button type="button" className="button button-secondary button-small" disabled={page === 0} onClick={() => onPageChange(page - 1)}>上一页</button><span>第 {page + 1} / {totalPages} 页</span><button type="button" className="button button-secondary button-small" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>下一页</button></nav>;
}

function MoldWarehouseLedger({ viewerCode, requests, overdue, lifecycle }: { viewerCode: string; requests: MoldRequest[]; overdue: MoldExternalMovement[]; lifecycle: MoldLifecycle[] }) {
  const [query, setQuery] = useState("");
  const [custody, setCustody] = useState("ALL");
  const [ownership, setOwnership] = useState("ALL");
  const [assetStatus, setAssetStatus] = useState("ALL");
  const [location, setLocation] = useState("ALL");
  const [page, setPage] = useState(0);
  const debouncedQuery = useDebouncedValue(query);
  const assetState = useAsyncData(() => api.resources.assetPage({ keyword: debouncedQuery, custodyStatus: custody, ownershipType: ownership, status: assetStatus, locationCode: location, page }), [viewerCode, debouncedQuery, custody, ownership, assetStatus, location, page]);
  const summaryState = useAsyncData(async () => {
    const [all, inStock, inUse, external] = await Promise.all([
      api.resources.assetPage({ page: 0, size: 1 }),
      api.resources.assetPage({ custodyStatus: "IN_STOCK", status: "AVAILABLE", page: 0, size: 1 }),
      api.resources.assetPage({ custodyStatus: "INTERNAL_IN_USE", page: 0, size: 1 }),
      api.resources.assetPage({ custodyStatus: "EXTERNAL_OUT", page: 0, size: 1 })
    ]);
    return { all, inStock, inUse, external };
  }, [viewerCode]);
  if (assetState.loading || summaryState.loading) return <LoadingState label="正在加载模具台账" />;
  if (assetState.error) return <ErrorNotice error={assetState.error} onRetry={assetState.reload} />;
  if (summaryState.error) return <ErrorNotice error={summaryState.error} onRetry={summaryState.reload} />;
  const assetPage = assetState.data ?? emptyPage<ResourceAsset>();
  const assets = assetPage.items;
  const summary = summaryState.data ?? { all: emptyPage<ResourceAsset>(), inStock: emptyPage<ResourceAsset>(), inUse: emptyPage<ResourceAsset>(), external: emptyPage<ResourceAsset>() };
  const inStock = summary.inStock.totalElements;
  const inUse = summary.inUse.totalElements;
  const external = summary.external.totalElements;
  const pendingRequests = requests.filter((request) => request.status === "APPROVED");
  const dueMaintenance = lifecycle.filter((item) => item.lockReason || item.nextMaintenanceDate && new Date(item.nextMaintenanceDate) <= new Date());
  function clearFilters() { setQuery(""); setCustody("ALL"); setOwnership("ALL"); setAssetStatus("ALL"); setLocation("ALL"); setPage(0); }
  return <>
    <section className="warehouse-mold-summary" aria-label="模具仓资产概况">
      <div><span>在库可用</span><strong>{inStock}</strong><small>可申请领用的模具资产</small></div>
      <div><span>待出库交接</span><strong>{pendingRequests.length}</strong><small>已批准、待仓管处理</small></div>
      <div><span>内部使用中</span><strong>{inUse}</strong><small>等待归还或生产结束</small></div>
      <div><span>外出 / 送修</span><strong>{external}</strong><small>客户召回或维修保养</small></div>
      <div><span>维护关注</span><strong>{dueMaintenance.length + overdue.length}</strong><small>锁定、到期或逾期未回</small></div>
    </section>
    <section className="warehouse-callout-grid">
      <div className={pendingRequests.length ? "warehouse-callout attention" : "warehouse-callout"}><ClipboardCheck aria-hidden="true" /><div><strong>{pendingRequests.length ? `有 ${pendingRequests.length} 张领用单待处理` : "没有待处理领用单"}</strong><small>领用单会带出模具编码、库位、产品和接收人。</small></div><Link className="button button-primary button-small" to="/molds">处理领用</Link></div>
      <div className={overdue.length || dueMaintenance.length ? "warehouse-callout danger" : "warehouse-callout"}><Wrench aria-hidden="true" /><div><strong>{overdue.length || dueMaintenance.length ? "有模具需要关注" : "模具维护状态正常"}</strong><small>送修逾期、保养到期和异常锁定均在模具台账集中处理。</small></div><Link className="button button-secondary button-small" to="/molds">查看台账</Link></div>
    </section>
    <section className="section-block warehouse-mold-actions">
      <header className="section-heading"><div><h2>受控模具操作</h2><p>模具不使用通用收发单手工过账。入库、订单领用、内部归还、对外送修和保养均通过模具台账执行，状态与责任人会同步更新。</p></div><Wrench aria-hidden="true" /></header>
      <div className="header-actions"><Link className="button button-primary" to="/molds">进入模具仓管理</Link><Link className="button button-secondary" to="/scan"><ScanLine aria-hidden="true" />扫模具码核验</Link></div>
    </section>
    <section className="section-block">
      <header className="section-heading"><div><h2>模具资产清册</h2><p>服务端返回 {assets.length} / 共 {assetPage.totalElements} 套；筛选、分页和排序均在后台完成。</p></div></header>
      <div className="warehouse-filter-bar warehouse-filter-bar-wide" aria-label="模具资产筛选"><Field label="检索"><input value={query} placeholder="模具编码、名称、客户或库位" onChange={(event) => { setQuery(event.target.value); setPage(0); }} /></Field><Field label="保管状态"><select value={custody} onChange={(event) => { setCustody(event.target.value); setPage(0); }}><option value="ALL">全部状态</option><option value="IN_STOCK">在库</option><option value="INTERNAL_IN_USE">内部使用中</option><option value="EXTERNAL_OUT">对外送修/召回</option></select></Field><Field label="权属"><select value={ownership} onChange={(event) => { setOwnership(event.target.value); setPage(0); }}><option value="ALL">全部权属</option><option value="COMPANY_OWNED">企业自有</option><option value="CUSTOMER_OWNED">客户寄存</option></select></Field><Field label="资产状态"><select value={assetStatus} onChange={(event) => { setAssetStatus(event.target.value); setPage(0); }}><option value="ALL">全部状态</option><option value="AVAILABLE">可用</option><option value="OCCUPIED">占用</option><option value="EXHAUSTED">异常锁定</option></select></Field><Field label="库位"><input value={location === "ALL" ? "" : location} placeholder="如 MOLD-01-A" list="mold-locations" onChange={(event) => { setLocation(event.target.value || "ALL"); setPage(0); }} /><datalist id="mold-locations"><option value="MOLD-01" /><option value="MOLD-02" /><option value="MOLD-03" /></datalist></Field><button type="button" className="button button-secondary button-small warehouse-filter-clear" onClick={clearFilters}>清空</button></div>
      {assetPage.totalElements === 0 ? <EmptyState title={query || custody !== "ALL" || ownership !== "ALL" || assetStatus !== "ALL" || location !== "ALL" ? "未找到模具资产" : "暂无模具资产"} description="请调整筛选条件或先登记模具入库。" /> : <><div className="table-scroll"><table><thead><tr><th>模具资产</th><th>库位</th><th>权属</th><th>保管状态</th><th>资产状态</th><th>最近更新</th></tr></thead><tbody>{assets.map((asset) => <tr key={asset.id}><td className="mold-name-cell"><strong>{asset.assetName}</strong><small>{asset.assetCode}</small></td><td>{asset.locationCode ?? "未分配"}</td><td>{asset.ownershipType === "CUSTOMER_OWNED" ? `客户寄存${asset.ownerName ? ` · ${asset.ownerName}` : ""}` : "企业自有"}</td><td>{moldCustodyLabel(asset.moldCustodyStatus)}</td><td><StatusBadge value={asset.status} /></td><td>{formatDate(asset.updatedAt, true)}</td></tr>)}</tbody></table></div><Pager page={assetPage.page} totalPages={assetPage.totalPages} onPageChange={setPage} /></>}
    </section>
  </>;
}

function moldCustodyLabel(value: ResourceAsset["moldCustodyStatus"]) {
  return value === "INTERNAL_IN_USE" ? "内部领用中" : value === "EXTERNAL_OUT" ? "对外送修/召回" : "在库";
}

function FinishedGoodsWarehouseLedger({ viewerCode }: { viewerCode: string }) {
  const [lotQuery, setLotQuery] = useState("");
  const [availability, setAvailability] = useState("ALL");
  const [warehouse, setWarehouse] = useState("");
  const [deliveryQuery, setDeliveryQuery] = useState("");
  const [deliveryStatus, setDeliveryStatus] = useState("ALL");
  const [lotPageNumber, setLotPage] = useState(0);
  const [deliveryPageNumber, setDeliveryPage] = useState(0);
  const debouncedLotQuery = useDebouncedValue(lotQuery);
  const debouncedDeliveryQuery = useDebouncedValue(deliveryQuery);
  const lotState = useAsyncData(() => api.fulfillment.lotPage({ viewerCode, keyword: debouncedLotQuery, availability, warehouseCode: warehouse, page: lotPageNumber }), [viewerCode, debouncedLotQuery, availability, warehouse, lotPageNumber]);
  const deliveryState = useAsyncData(() => api.fulfillment.deliveryPage({ viewerCode, keyword: debouncedDeliveryQuery, status: deliveryStatus, page: deliveryPageNumber }), [viewerCode, debouncedDeliveryQuery, deliveryStatus, deliveryPageNumber]);
  useEffect(() => { setLotPage(0); }, [debouncedLotQuery, availability, warehouse]);
  useEffect(() => { setDeliveryPage(0); }, [debouncedDeliveryQuery, deliveryStatus]);
  if (lotState.loading || deliveryState.loading) return <LoadingState label="正在加载成品批次与物流台账" />;
  if (lotState.error) return <ErrorNotice error={lotState.error} onRetry={lotState.reload} />;
  if (deliveryState.error) return <ErrorNotice error={deliveryState.error} onRetry={deliveryState.reload} />;
  const lotResult = lotState.data ?? emptyPage<FinishedGoodsLot>();
  const deliveryResult = deliveryState.data ?? emptyPage<DeliveryOrder>();
  const lots = lotResult.items;
  const deliveries = deliveryResult.items;
  const readyLots = lots.filter((lot) => Number(lot.availableQuantity) > 0);
  const pendingPick = deliveries.filter((delivery) => delivery.status === "DRAFT");
  const shipping = deliveries.filter((delivery) => delivery.status === "PICKED" || delivery.status === "SHIPPED");
  const lotWarehouses = ["FG-01"];
  const visibleLots = lots;
  const visibleDeliveries = deliveries;
  function clearLotFilters() { setLotQuery(""); setAvailability("ALL"); setWarehouse(""); setLotPage(0); }
  function clearDeliveryFilters() { setDeliveryQuery(""); setDeliveryStatus("ALL"); setDeliveryPage(0); }
  return <>
    <section className="warehouse-mold-summary" aria-label="成品仓概况"><div><span>成品批次</span><strong>{lots.length}</strong><small>已入库批次</small></div><div><span>可发数量</span><strong>{readyLots.reduce((sum, lot) => sum + Number(lot.availableQuantity), 0)}</strong><small>按批次可交付</small></div><div><span>待备货</span><strong>{pendingPick.length}</strong><small>已建发货单</small></div><div><span>运输中</span><strong>{shipping.length}</strong><small>待签收或更新运单</small></div><div><span>已签收</span><strong>{deliveries.filter((delivery) => delivery.status === "DELIVERED").length}</strong><small>交付已闭环</small></div></section>
    <section className="warehouse-callout-grid"><div className={pendingPick.length ? "warehouse-callout attention" : "warehouse-callout"}><ClipboardCheck aria-hidden="true" /><div><strong>{pendingPick.length ? `${pendingPick.length} 张发货单待备货` : "没有待备货发货单"}</strong><small>按成品批次备货，系统会冻结可发数量避免重复发货。</small></div><Link className="button button-primary button-small" to="/fulfillment">处理交付</Link></div><div className={shipping.length ? "warehouse-callout attention" : "warehouse-callout"}><Truck aria-hidden="true" /><div><strong>{shipping.length ? `${shipping.length} 张发货单待跟进` : "没有在途发货单"}</strong><small>发运必须记录承运商和运单号，签收后完成交付闭环。</small></div><Link className="button button-secondary button-small" to="/fulfillment">查看物流</Link></div></section>
    <section className="section-block"><header className="section-heading"><div><h2>成品批次</h2><p>显示 {visibleLots.length} / {lots.length} 个批次；按订单、产品、可发状态和仓库快速定位。</p></div><div className="header-actions"><Link className="button button-primary" to="/fulfillment"><PackageCheck aria-hidden="true" />进入成品交付台</Link><Link className="button button-secondary" to="/scan"><ScanLine aria-hidden="true" />扫码核验</Link></div></header>
      <div className="warehouse-filter-bar" aria-label="成品批次筛选"><Field label="检索"><input value={lotQuery} placeholder="批次、订单、产品编码或名称" onChange={(event) => setLotQuery(event.target.value)} /></Field><Field label="可发状态"><select value={availability} onChange={(event) => setAvailability(event.target.value)}><option value="ALL">全部批次</option><option value="AVAILABLE">仍可发货</option><option value="ALLOCATED">已全部占用/发出</option></select></Field><Field label="仓库"><select value={warehouse} onChange={(event) => setWarehouse(event.target.value)}><option value="ALL">全部仓库</option>{lotWarehouses.map((value) => <option key={value} value={value}>{value}</option>)}</select></Field><button type="button" className="button button-secondary button-small warehouse-filter-clear" onClick={clearLotFilters}>清空</button></div>
      {lots.length === 0 ? <EmptyState title="暂无成品批次" description="生产完成并通过终检后，可由成品仓登记成品入库。" /> : visibleLots.length === 0 ? <EmptyState title="未找到成品批次" description="请调整筛选条件。" /> : <div className="table-scroll"><table><thead><tr><th>批次</th><th>订单</th><th>产品</th><th>入库 / 可发</th><th>仓库</th><th>登记信息</th></tr></thead><tbody>{visibleLots.map((lot) => <tr key={lot.id}><td className="primary-cell">{lot.lotNo}</td><td>{lot.orderNo}</td><td><strong>{lot.productName}</strong><small>{lot.productCode}</small></td><td>{formatQuantity(lot.quantity)} / {formatQuantity(lot.availableQuantity)}</td><td>{lot.warehouseCode}</td><td>{lot.registeredBy}<small>{formatDate(lot.registeredAt, true)}</small></td></tr>)}</tbody></table></div>}
    </section>
    <section className="section-block"><header className="section-heading"><div><h2>物流交付追溯</h2><p>显示 {visibleDeliveries.length} / {deliveries.length} 张发货单；按客户、订单、产品、承运商和状态定位。</p></div></header><div className="warehouse-filter-bar" aria-label="物流交付筛选"><Field label="检索"><input value={deliveryQuery} placeholder="发货单、客户、订单、产品、承运商或运单号" onChange={(event) => setDeliveryQuery(event.target.value)} /></Field><Field label="交付状态"><select value={deliveryStatus} onChange={(event) => setDeliveryStatus(event.target.value)}><option value="ALL">全部状态</option><option value="DRAFT">待备货</option><option value="PICKED">已备货</option><option value="SHIPPED">运输中</option><option value="DELIVERED">已签收</option></select></Field><button type="button" className="button button-secondary button-small warehouse-filter-clear" onClick={clearDeliveryFilters}>清空</button></div>{deliveries.length === 0 ? <EmptyState title="暂无发货单" description="创建发货单后，可在这里按客户和物流状态快速追溯。" /> : visibleDeliveries.length === 0 ? <EmptyState title="未找到发货单" description="请调整筛选条件。" /> : <div className="table-scroll"><table><thead><tr><th>发货单</th><th>客户 / 订单</th><th>产品 / 批次</th><th>数量</th><th>物流</th><th>状态</th><th>更新时间</th></tr></thead><tbody>{visibleDeliveries.map((delivery) => <tr key={delivery.id}><td className="primary-cell">{delivery.deliveryNo}</td><td><strong>{delivery.customerName}</strong><small>{delivery.orderNo}</small></td><td><strong>{delivery.productName}</strong><small>{delivery.lotNo}</small></td><td>{formatQuantity(delivery.quantity)}</td><td>{delivery.carrier || "未指定"}<small>{delivery.trackingNo || "未录运单"}</small></td><td><StatusBadge value={delivery.status} /></td><td>{formatDate(delivery.updatedAt, true)}</td></tr>)}</tbody></table></div>}</section>
  </>;
}
