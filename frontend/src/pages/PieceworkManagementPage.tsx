import { FormEvent, useMemo, useState } from "react";
import { BadgeDollarSign, Check, Download, Plus, RefreshCw } from "lucide-react";
import { api } from "../api";
import { ErrorNotice, Field, formatDate, formatQuantity, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { PieceworkRate, RouteType, SettlementUnit, Task, WorkerOutputDetail, WorkerPayrollDetail } from "../types";

type RateOperation = { key: string; productCode: string; productName: string; operationCode: string; operationName: string; routeType: RouteType; settlementUnit: SettlementUnit };

function settlementUnitFor(task: Task): SettlementUnit | null {
  if (task.compensationMode === "PIECE_PCS") return "PCS";
  if (task.compensationMode === "PIECE_TREE") return "TREE";
  if (task.compensationMode === "PIECE_KG") return "KG";
  return null;
}

function isLowWaxProgressOnly(task: Task) {
  return task.routeType === "LOW_TEMP_WAX" && ["WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY"].includes(task.operationCode);
}

function routeLabel(route: RouteType) {
  return route === "MID_TEMP_WAX" ? "中温蜡" : route === "LOW_TEMP_WAX" ? "低温蜡" : "砂型外协";
}

function outputSourceLabel(source: WorkerOutputDetail["source"]) {
  return source === "PIECEWORK_SETTLEMENT" ? "计件结算回填" : "电子报工";
}

function unitLabel(unit: SettlementUnit) {
  return unit === "TREE" ? "树" : unit === "KG" ? "公斤" : "件";
}

export function localIsoDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function firstDayOfNextMonth(now = new Date()) {
  return localIsoDate(new Date(now.getFullYear(), now.getMonth() + 1, 1));
}

export function previousLocalMonth(now = new Date()) {
  return localIsoDate(new Date(now.getFullYear(), now.getMonth() - 1, 1)).slice(0, 7);
}

export function shanghaiSettlementDate(value: string | null) {
  if (!value) return null;
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit"
  }).formatToParts(new Date(value));
  const valueFor = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value;
  return `${valueFor("year")}-${valueFor("month")}-${valueFor("day")}`;
}

export function applicableRateForTask(rates: PieceworkRate[], task: Task) {
  const unit = settlementUnitFor(task);
  const settlementDate = shanghaiSettlementDate(task.completedAt);
  if (!unit || !settlementDate) return null;
  return rates
    .filter((rate) => rate.active && rate.routeType === task.routeType && rate.operationCode === task.operationCode
      && rate.settlementUnit === unit && rate.effectiveFrom <= settlementDate
      && (rate.productCode === task.productCode || rate.productCode == null))
    .sort((left, right) => Number(left.productCode !== task.productCode) - Number(right.productCode !== task.productCode)
      || right.effectiveFrom.localeCompare(left.effectiveFrom) || right.createdAt.localeCompare(left.createdAt))[0] ?? null;
}

function nextRateVersion(rates: PieceworkRate[], option: RateOperation, effectiveFrom: string) {
  const revisions = rates.filter((rate) => rate.operationCode === option.operationCode
    && rate.routeType === option.routeType && rate.productCode === option.productCode && rate.effectiveFrom === effectiveFrom).length;
  return `${effectiveFrom.slice(0, 7)}-R${revisions + 1}`;
}

export function PieceworkManagementPage({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [rates, entries, tasks, users] = await Promise.all([
      api.piecework.rates(), api.piecework.entries(operatorCode),
      api.tasks.list(undefined, undefined, undefined, operatorCode), api.access.users()
    ]);
    return { rates, entries, tasks, users };
  }, [operatorCode]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [payrollMonth, setPayrollMonth] = useState(previousLocalMonth);
  const [rateOperationKey, setRateOperationKey] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState(firstDayOfNextMonth);
  const [candidateTaskId, setCandidateTaskId] = useState("");
  const [exportWorkerCode, setExportWorkerCode] = useState("");
  const [outputRoute, setOutputRoute] = useState<RouteType | "">("");
  const [workerPayrollDetails, setWorkerPayrollDetails] = useState<WorkerPayrollDetail[] | null>(null);
  const [workerOutputDetails, setWorkerOutputDetails] = useState<WorkerOutputDetail[] | null>(null);
  const rates = state.data?.rates ?? [];
  const entries = state.data?.entries ?? [];
  const allTasks = state.data?.tasks ?? [];
  const users = state.data?.users ?? [];
  const currentUser = state.data?.users.find((user) => user.employeeCode === operatorCode);
  const lowWaxProgressOnly = currentUser?.primaryRole === "LOW_WAX_SUPERVISOR";
  const financeReviewOnly = currentUser?.roles.includes("FINANCE_REVIEWER") ?? false;

  const rateOptions = useMemo(() => Array.from(new Map(allTasks.filter((task) => settlementUnitFor(task) != null && !isLowWaxProgressOnly(task)).map((task) => {
    const option: RateOperation = { key: `${task.routeType}:${task.productCode}:${task.operationCode}`, productCode: task.productCode, productName: task.productName, operationCode: task.operationCode, operationName: task.operationName, routeType: task.routeType, settlementUnit: settlementUnitFor(task)! };
    return [option.key, option];
  })).values()), [allTasks]);
  const selectedRate = rateOptions.find((option) => option.key === rateOperationKey) ?? rateOptions[0];
  const settledByTask = useMemo(() => {
    const result = new Map<string, number>();
    entries.filter((entry) => entry.status !== "REVERSED").forEach((entry) => result.set(entry.taskId, (result.get(entry.taskId) ?? 0) + entry.quantity));
    return result;
  }, [entries]);
  const unsettledTasks = allTasks.filter((task) => {
    const unit = settlementUnitFor(task);
    if (unit == null || isLowWaxProgressOnly(task) || task.goodQuantity <= 0 || !task.assignedTo) return false;
    const limit = unit === "KG" ? task.completedWeightKg : unit === "TREE" ? task.treeCount : task.goodQuantity;
    return limit != null && limit > 0 && (settledByTask.get(task.id) ?? 0) < limit;
  });
  const candidateTasks = unsettledTasks.filter((task) => applicableRateForTask(rates, task) != null);
  const missingRateTasks = unsettledTasks.filter((task) => applicableRateForTask(rates, task) == null);
  const selectedCandidate = candidateTasks.find((task) => task.id === candidateTaskId);
  const candidateUnit = selectedCandidate == null ? null : settlementUnitFor(selectedCandidate);
  const candidateLimit = selectedCandidate == null ? 0 : candidateUnit === "KG" ? selectedCandidate.completedWeightKg ?? 0 : candidateUnit === "TREE" ? selectedCandidate.treeCount ?? 0 : selectedCandidate.goodQuantity;
  const candidateRemaining = Math.max(0, candidateLimit - (selectedCandidate == null ? 0 : settledByTask.get(selectedCandidate.id) ?? 0));
  const payrollEntries = entries.filter((entry) => entry.status === "CONFIRMED" && entry.settlementDate.slice(0, 7) === payrollMonth);
  const payrollTotal = payrollEntries.reduce((total, entry) => total + entry.amount, 0);
  const candidateEntries = entries.filter((entry) => entry.status === "CANDIDATE" && entry.settlementDate.slice(0, 7) === payrollMonth);
  const candidateTotal = candidateEntries.reduce((total, entry) => total + entry.amount, 0);

  async function createRate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (financeReviewOnly) { setError(new Error("财务复核员只确认和导出计件工资，工价由生产主管发布。")); return; }
    if (!selectedRate) { setError(new Error("当前权限范围内没有可配置计件工序。")); return; }
    setSaving(true); setError(null);
    try {
      const form = new FormData(event.currentTarget);
      await api.piecework.createRate({ supervisorCode: operatorCode, productCode: selectedRate.productCode, productName: selectedRate.productName, operationCode: selectedRate.operationCode, operationName: selectedRate.operationName, routeType: selectedRate.routeType, version: nextRateVersion(rates, selectedRate, effectiveFrom), settlementUnit: selectedRate.settlementUnit, unitRate: Number(form.get("unitRate")), effectiveFrom });
      await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function createEntry(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (financeReviewOnly) { setError(new Error("财务复核员不能生成候选计件单，请由生产主管或授权代录人员生成。")); return; }
    if (!selectedCandidate?.assignedTo || !candidateUnit) return;
    const quantity = Number(new FormData(event.currentTarget).get("quantity"));
    if (!Number.isFinite(quantity) || quantity <= 0 || quantity > candidateRemaining || (candidateUnit !== "KG" && !Number.isInteger(quantity))) {
      setError(new Error("计件数量必须符合剩余可结算数量；按件、按树仅允许整数。")); return;
    }
    setSaving(true); setError(null);
    try {
      await api.piecework.createEntry({ operationId: crypto.randomUUID(), taskId: selectedCandidate.id, workerCode: selectedCandidate.assignedTo, recordedBy: operatorCode, quantity });
      setCandidateTaskId(""); await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function confirm(entryId: string) {
    setSaving(true); setError(null);
    try { await api.piecework.confirm(entryId, operatorCode); await state.reload(); } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function exportPayroll() {
    setSaving(true); setError(null);
    try {
      const file = await api.piecework.payrollExport(operatorCode, payrollMonth);
      const link = document.createElement("a");
      link.href = URL.createObjectURL(file);
      link.download = `计件工资明细-${payrollMonth}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(link.href);
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  function saveExport(file: Blob, name: string) {
    const link = document.createElement("a");
    link.href = URL.createObjectURL(file);
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  async function exportWorkerPayroll() {
    if (!exportWorkerCode) { setError(new Error("请先选择员工。")); return; }
    setSaving(true); setError(null);
    try { saveExport(await api.piecework.workerPayrollExport(operatorCode, exportWorkerCode, payrollMonth), `个人计件工资条-${exportWorkerCode}-${payrollMonth}.csv`); } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function exportWorkerOutput() {
    if (!exportWorkerCode) { setError(new Error("请先选择员工。")); return; }
    setSaving(true); setError(null);
    try { saveExport(await api.piecework.workerOutputExport(operatorCode, exportWorkerCode, payrollMonth, outputRoute || undefined), `员工产出明细-${exportWorkerCode}-${payrollMonth}.csv`); } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function loadWorkerDetails() {
    if (!exportWorkerCode) { setError(new Error("请先选择员工。")); return; }
    setSaving(true); setError(null);
    try {
      const [payroll, output] = await Promise.all([
        api.piecework.workerPayroll(operatorCode, exportWorkerCode, payrollMonth),
        api.piecework.workerOutput(operatorCode, exportWorkerCode, payrollMonth, outputRoute || undefined)
      ]);
      setWorkerPayrollDetails(payroll);
      setWorkerOutputDetails(output);
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  function clearWorkerDetails() {
    setWorkerPayrollDetails(null);
    setWorkerOutputDetails(null);
  }

  if (state.loading) return <LoadingState label="正在加载计件管理" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  return <>
    <PageHeader title="计件管理" description="按任务实际合格产出核算；工价按生效日冻结，代录和确认均保留人员痕迹。" action={<div className="header-actions"><button className="button button-secondary" type="button" disabled={saving || payrollEntries.length === 0} onClick={() => void exportPayroll()}><Download aria-hidden="true" />导出本月工资</button><button className="icon-button" type="button" onClick={state.reload} aria-label="刷新计件数据"><RefreshCw aria-hidden="true" /></button></div>} />
    {error != null && <ErrorNotice error={error} />}
    <section className="piecework-payroll-cycle" aria-label="月度计薪周期"><div><span>计薪周期</span><strong>按自然月结算</strong><small>候选记录生成时冻结当日适用工价；补录已完工任务时按完工日取价。</small></div><label><span>核算月份</span><input type="month" value={payrollMonth} onChange={(event) => { setPayrollMonth(event.target.value); clearWorkerDetails(); }} /></label><div><span>已确认计件工资</span><strong>¥{payrollTotal.toFixed(2)}</strong><small>{payrollEntries.length} 条已确认记录</small></div></section>
    <section className="piecework-employee-exports" aria-label="员工月度单据导出">
      <header className="section-heading"><div><h2>员工月度单据</h2><p>工资条只含已确认的中温蜡计件；产出明细按报工事实导出，适用于中温蜡和低温蜡。</p></div><Download aria-hidden="true" /></header>
      <div className="form-grid">
        <Field label="员工"><select value={exportWorkerCode} onChange={(event) => { setExportWorkerCode(event.target.value); clearWorkerDetails(); }}><option value="">选择员工</option>{users.filter((user) => user.active).map((user) => <option key={user.employeeCode} value={user.employeeCode}>{user.employeeCode} · {user.name} · {user.unitName}</option>)}</select></Field>
        <Field label="产出产线"><select value={outputRoute} onChange={(event) => { setOutputRoute(event.target.value as RouteType | ""); clearWorkerDetails(); }}><option value="">全部产线</option><option value="MID_TEMP_WAX">中温蜡</option><option value="LOW_TEMP_WAX">低温蜡</option><option value="SAND_OUTSOURCE">砂型外协</option></select></Field>
        <div className="piecework-export-actions"><button className="button button-secondary" type="button" disabled={saving || !exportWorkerCode} onClick={() => void loadWorkerDetails()}>查询明细</button><button className="button button-secondary" type="button" disabled={saving || !exportWorkerCode} onClick={() => void exportWorkerPayroll()}><Download aria-hidden="true" />导出个人工资条</button><button className="button button-primary" type="button" disabled={saving || !exportWorkerCode} onClick={() => void exportWorkerOutput()}><Download aria-hidden="true" />导出个人产出明细</button></div>
      </div>
    </section>
    {(workerPayrollDetails != null || workerOutputDetails != null) && <section className="piecework-detail-preview" aria-label="员工月度明细预览">
      <header className="section-heading"><div><h2>员工月度明细</h2><p>{exportWorkerCode} · {payrollMonth}；当前筛选结果可直接核对或导出。</p></div></header>
      <div className="table-scroll"><table><thead><tr><th colSpan={8}>个人计件工资明细</th></tr><tr><th>订单 / 产品</th><th>工序</th><th>数量</th><th>单位工价</th><th>金额</th><th>工价版本</th><th>确认人</th><th>结算日</th></tr></thead><tbody>{workerPayrollDetails?.length ? workerPayrollDetails.map((row) => <tr key={`${row.taskNo}-${row.rateVersion}-${row.settlementDate}`}><td><strong>{row.orderNo}</strong><small>{row.productName} · {row.productMaterial || "未填材质"}</small></td><td>{row.operationName}</td><td>{formatQuantity(row.quantity)} {unitLabel(row.settlementUnit)}</td><td>¥{row.unitRate.toFixed(4)}</td><td><strong>¥{row.amount.toFixed(2)}</strong></td><td>{row.rateVersion}</td><td>{row.confirmedBy}</td><td>{formatDate(row.settlementDate)}</td></tr>) : <tr><td colSpan={8} className="muted">本月没有已确认的计件工资记录。</td></tr>}</tbody></table></div>
      <div className="table-scroll"><table><thead><tr><th colSpan={9}>员工产出明细</th></tr><tr><th>来源</th><th>产线 / 订单</th><th>产品</th><th>工序</th><th>合格数</th><th>报废数</th><th>批次</th><th>任务号</th><th>报工时间</th></tr></thead><tbody>{workerOutputDetails?.length ? workerOutputDetails.map((row) => <tr key={`${row.source}-${row.taskNo}-${row.occurredAt}`}><td>{outputSourceLabel(row.source)}</td><td><strong>{routeLabel(row.routeType)}</strong><small>{row.orderNo}</small></td><td>{row.productName}<small>{row.productMaterial || "未填材质"}</small></td><td>{row.operationName}</td><td>{formatQuantity(row.goodQuantity)}</td><td>{formatQuantity(row.scrapQuantity)}</td><td>{row.batchNo}</td><td>{row.taskNo}</td><td>{formatDate(row.occurredAt, true)}</td></tr>) : <tr><td colSpan={9} className="muted">本月没有符合筛选条件的报工产出。</td></tr>}</tbody></table></div>
    </section>}
    <section className="piecework-summary-grid" aria-label="本月计件结算概览">
      <article><span>待财务确认</span><strong>¥{candidateTotal.toFixed(2)}</strong><small>{candidateEntries.length} 条候选记录</small></article>
      <article><span>已确认工资</span><strong>¥{payrollTotal.toFixed(2)}</strong><small>{payrollEntries.length} 条已入账记录</small></article>
      <article><span>可生成候选</span><strong>{candidateTasks.length}</strong><small>已完工且有可用工价的任务</small></article>
      <article className={missingRateTasks.length > 0 ? "attention" : ""}><span>缺少可用工价</span><strong>{missingRateTasks.length}</strong><small>{missingRateTasks.length > 0 ? "不会生成候选，避免错价入账" : "本范围任务工价完整"}</small></article>
    </section>
    {financeReviewOnly && <section className="piecework-finance-note"><strong>财务复核模式</strong><span>此账号只处理候选确认与月度导出；生产主管负责生成候选，工价版本由生产主管维护。</span></section>}
    {missingRateTasks.length > 0 && !financeReviewOnly && <section className="piecework-finance-note warning"><strong>有 {missingRateTasks.length} 条已完工任务暂不能计薪</strong><span>请补充产品、工序和生效日对应的工价；这只影响计件候选，不影响派工、报工、流转和员工产出明细导出。</span></section>}
    {lowWaxProgressOnly ? <section className="section-block"><div className="empty-state"><h2>低温蜡间仅统计进度</h2><p>射蜡、修蜡和组树的数量通过派工与报工追溯，不进入蜡间计件工资；成品称重结算由对应成品环节统一处理。</p></div></section> : <>
      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading"><div><h2>发布产品工序工价</h2><p>每项工价绑定一个产品和一个计件工序；任务会优先使用产品专属工价。</p></div><BadgeDollarSign aria-hidden="true" /></header>
          <form className="compact-form" onSubmit={createRate}>
            <div className="form-grid">
              <Field label="产品与计件工序" required>
                <select value={selectedRate?.key ?? ""} onChange={(event) => setRateOperationKey(event.target.value)} disabled={rateOptions.length === 0} required>
                  <option value="" disabled>选择产品和计件工序</option>
                  {rateOptions.map((option) => <option key={option.key} value={option.key}>{option.productCode} · {option.productName} · {option.operationName} · 按{unitLabel(option.settlementUnit)}</option>)}
                </select>
              </Field>
              <Field label="单位工价" required><input name="unitRate" type="number" min="0" step="0.0001" required /></Field>
              <Field label="生效日期" required hint="建议发布下月工价；同日重发会形成修订版本。"><input type="date" value={effectiveFrom} onChange={(event) => setEffectiveFrom(event.target.value)} required /></Field>
              <Field label="自动生成版本"><input value={selectedRate ? nextRateVersion(rates, selectedRate, effectiveFrom) : "待选择产品与工序"} readOnly /></Field>
            </div>
            <button className="button button-primary" disabled={saving || !selectedRate || financeReviewOnly}><Plus aria-hidden="true" />发布工价</button>
          </form>
        </div>
        <div className="table-pane">
          <header className="section-heading"><div><h2>工价版本</h2><p>专属产品工价优先；历史通用工价仅作为未配置产品时的受控兜底。</p></div></header>
          <div className="table-scroll"><table><thead><tr><th>产品 / 工序</th><th>产线 / 版本</th><th>单位工价</th><th>生效日期</th><th>状态</th></tr></thead><tbody>
            {rates.map((rate) => <tr key={rate.id}><td><strong>{rate.productName ?? "通用工价"} · {rate.operationName}</strong><small>{rate.productCode ?? "ALL_PRODUCTS"} · {rate.operationCode}</small></td><td>{routeLabel(rate.routeType)}<small>{rate.version} · 按{unitLabel(rate.settlementUnit)}</small></td><td>¥{rate.unitRate.toFixed(4)}</td><td>{formatDate(rate.effectiveFrom)}</td><td><StatusBadge value={rate.active ? "ACTIVE" : "INACTIVE"} /></td></tr>)}
          </tbody></table></div>
        </div>
      </section>
      <section className="split-section"><div className="editor-pane"><header className="section-heading"><div><h2>生成计件候选</h2><p>只展示本人范围内已报合格产出且尚有可结算余量的任务。</p></div></header><form className="compact-form" onSubmit={createEntry}><div className="form-grid"><Field label="生产任务" required><select value={candidateTaskId} onChange={(event) => setCandidateTaskId(event.target.value)} required><option value="" disabled>选择待结算任务</option>{candidateTasks.map((task) => <option key={task.id} value={task.id}>{task.taskNo} · {task.productName} · {task.operationName} · {task.assignedTo}</option>)}</select></Field><Field label="本次计件数量" required hint={candidateUnit ? `剩余可结算 ${formatQuantity(candidateRemaining)} ${unitLabel(candidateUnit)}` : "先选择任务以显示剩余可结算数量。"}><input name="quantity" type="number" min={candidateUnit === "KG" ? "0.001" : "1"} step={candidateUnit === "KG" ? "0.001" : "1"} max={candidateRemaining || undefined} disabled={!selectedCandidate} required /></Field></div><button className="button button-primary" disabled={saving || !selectedCandidate}><Plus aria-hidden="true" />生成候选</button></form></div><div className="table-pane"><header className="section-heading"><div><h2>计件台账</h2><p>代录人与确认人分别记录，工资归属始终是派工执行员工。</p></div></header><div className="table-scroll"><table><thead><tr><th>计件单</th><th>任务 / 工序</th><th>工资归属</th><th>代录</th><th>数量</th><th>单价 / 金额</th><th>状态</th><th>操作</th></tr></thead><tbody>{entries.map((entry) => <tr key={entry.id}><td className="primary-cell">{entry.entryNo}</td><td><strong>{entry.operationName}</strong><small>{entry.taskNo}</small></td><td>{entry.workerCode}</td><td>{entry.recordedBy}</td><td>{formatQuantity(entry.quantity)}<small>{unitLabel(entry.settlementUnit)}</small></td><td><strong>¥{entry.amount.toFixed(2)}</strong><small>¥{entry.unitRate.toFixed(4)} · {entry.rateVersion}</small></td><td><StatusBadge value={entry.status} /></td><td className="actions-cell">{entry.status === "CANDIDATE" ? <button className="button button-secondary button-small" disabled={saving} onClick={() => confirm(entry.id)}><Check aria-hidden="true" />确认</button> : <span className="muted">{entry.confirmedBy || "-"}</span>}</td></tr>)}</tbody></table></div></div></section>
    </>}
  </>;
}
