import { FormEvent, useState } from "react";
import { ArrowRight, Box, ClipboardCheck, FileText, Layers, Route, ScanLine, Truck, Wrench } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import { deviceCode } from "../offlineQueue";
import type { AccessUser, ResourceAsset, ScanResolution, Task } from "../types";

function canUse(user: AccessUser, permission: string) {
  return user.permissions.includes(permission);
}

type ScanResult = ScanResolution;

function scanToken(value: string) {
  return value.trim().replace(/^MES:ASSET_QR:/i, "").toUpperCase();
}

export function ScanPage({ user }: { user: AccessUser }) {
  const [value, setValue] = useState("");
  const [submittedValue, setSubmittedValue] = useState("");
  const [result, setResult] = useState<ScanResult | null>(null);
  const [resolving, setResolving] = useState(false);
  const [resolveError, setResolveError] = useState<unknown>(null);
  const state = useAsyncData(api.access.users, []);

  async function auditScan(item: ScanResult, intent: string, scannedValue = submittedValue) {
    await api.scanEvents.record({
      operationId: crypto.randomUUID(),
      entityType: item.kind === "task" ? "TASK" : item.kind === "asset" ? "ASSET" : "BATCH",
      entityId: item.kind === "task" ? item.task.id : item.kind === "asset" ? item.asset.id : item.batchId,
      intent,
      operatorCode: user.employeeCode,
      scannedValue,
      deviceCode: deviceCode(),
      workstationCode: user.unitCode
    });
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const scannedValue = value.trim();
    setSubmittedValue(scannedValue); setResult(null); setResolveError(null); setResolving(true);
    try {
      const resolved = await api.scanEvents.resolve(scannedValue);
      setResult(resolved);
      void auditScan(resolved, "VIEW", scannedValue).catch(() => undefined);
    } catch (caught) {
      setResolveError(caught);
    } finally {
      setResolving(false);
    }
  }

  if (state.loading) return <LoadingState label="正在准备扫码核验" />;
  if (state.error || !state.data) return <ErrorNotice error={state.error ?? new Error("扫码数据不可用")} onRetry={state.reload} />;

  return <>
    <PageHeader title="扫一扫" description="各角色均可用。扫码枪可直接写入输入框；支持资产、任务和流转卡上的常用编码。" />
    <section className="scan-panel">
      <ScanLine aria-hidden="true" />
      <form onSubmit={(event) => void submit(event)}><Field label="扫码结果" required hint="支持资产码、二维码标签号、任务号（TK-）、任务二维码（MES:TASK:）和流转卡批次码（MES:BATCH:）"><input value={value} onChange={(event) => setValue(event.target.value)} placeholder="扫描二维码或输入资产码、任务号、批次码" autoCapitalize="characters" autoCorrect="off" inputMode="text" autoFocus required /></Field><button className="button button-primary" disabled={resolving}><ScanLine aria-hidden="true" />{resolving ? "核验中" : "核验"}</button></form>
    </section>
    <section className="scan-mobile-guide" aria-label="扫码使用提示"><strong>扫码后再选择动作</strong><span>查看溯源、进入我的任务、领取任务或进入资产台账均需明确确认，不会自动接收或开工。</span></section>
    {resolveError && <ErrorNotice error={resolveError} />}
    {submittedValue && resolving && <LoadingState label="正在核验扫码内容" />}
    {submittedValue && !result && !resolving && !resolveError && <EmptyState title="未识别扫码内容" description="请确认编码已登记。可输入模具/周转车资产码、二维码标签号、任务号（TK-）、MES:TASK: 任务二维码或 MES:BATCH: 流转卡批次二维码。" />}
    {result?.kind === "task" && <TaskScanResult task={result.task} users={state.data} user={user} onAudit={(intent) => auditScan(result, intent)} />}
    {result?.kind === "asset" && <AssetScanResult asset={result.asset} user={user} onAudit={(intent) => auditScan(result, intent)} />}
		{result?.kind === "batch" && <BatchScanResult batchNo={result.batchNo} tasks={result.tasks} users={state.data} user={user} onAudit={(intent) => auditScan(result, intent)} />}
  </>;
}

function resolveScan(value: string, assets: ResourceAsset[], labels: Array<{ qrToken: string; labelNo?: string; assetId: string | null; assetCode: string | null }>, tasks: Task[]): ScanResult | null {
  const scanned = value.trim();
  if (!scanned) return null;
  const normalized = scanned.toUpperCase();
  const taskValue = normalized.startsWith("MES:TASK:") ? normalized.substring("MES:TASK:".length).trim() : normalized;
  const task = tasks.find((item) => item.id.toUpperCase() === taskValue || item.taskNo.toUpperCase() === taskValue);
  if (task) return { kind: "task" as const, task, asset: null, batchId: null, batchNo: null, tasks: [] };
	const batchValue = normalized.startsWith("MES:BATCH:") ? normalized.substring("MES:BATCH:".length).trim() : normalized;
	const batchTasks = tasks.filter((item) => String(item.batchId ?? "").toUpperCase() === batchValue || String(item.batchNo ?? "").toUpperCase() === batchValue)
		.sort((left, right) => left.sequenceNo - right.sequenceNo);
	if (batchTasks.length > 0) return { kind: "batch", task: null, asset: null, batchId: batchTasks[0].batchId, batchNo: batchTasks[0].batchNo, tasks: batchTasks };
  const token = scanToken(scanned);
  const label = labels.find((item) => item.qrToken.toUpperCase() === token || item.labelNo?.toUpperCase() === normalized);
  const asset = assets.find((item) => item.id === label?.assetId) ?? assets.find((item) => item.assetCode.toUpperCase() === normalized || item.id.toUpperCase() === normalized);
  return asset ? { kind: "asset" as const, task: null, asset, batchId: null, batchNo: null, tasks: [] } : null;
}

export function assigneeLabel(employeeCode: string | null, users: AccessUser[]) {
  if (!employeeCode) return "未派工";
  const employee = users.find((item) => item.employeeCode === employeeCode);
  return employee ? `${employee.employeeCode} · ${employee.name}` : employeeCode;
}

export function batchCurrentTask(tasks: Task[], employeeCode: string) {
  const ordered = [...tasks].sort((left, right) => left.sequenceNo - right.sequenceNo);
  return ordered.find((task) => task.assignedTo === employeeCode && task.status !== "COMPLETED")
    ?? ordered.find((task) => task.status === "IN_PROGRESS" || task.status === "ASSIGNED" || task.status === "READY")
    ?? ordered.find((task) => task.status !== "COMPLETED")
    ?? ordered.at(-1);
}

function TaskScanResult({ task, users, user, onAudit }: { task: Task; users: AccessUser[]; user: AccessUser; onAudit: (intent: string) => Promise<void> }) {
  const navigate = useNavigate();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const assignedToMe = task.assignedTo === user.employeeCode;
  const canClaim = task.status === "READY" && task.operationCode !== "WAX_INJECTION" && canUse(user, "TASK_SELF_CLAIM");

  async function claimTask() {
    setPending(true); setError(null);
    try {
      await api.tasks.claim(task.id, user.employeeCode);
      await onAudit("CLAIM_TASK").catch(() => undefined);
      navigate(`/workbench?task=${encodeURIComponent(task.id)}`);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  async function openTrace() {
    await onAudit("VIEW_TRACE").catch(() => undefined);
    navigate(`/trace?orderId=${encodeURIComponent(task.orderId)}`);
  }

  async function openDocument() {
    await onAudit("VIEW_JOB_CARD").catch(() => undefined);
    navigate(`/documents?documentType=PROCESS_CARD&taskId=${encodeURIComponent(task.id)}`);
  }

  async function openWorkbench() {
    await onAudit("OPEN_WORKBENCH").catch(() => undefined);
    navigate(`/workbench?task=${encodeURIComponent(task.id)}`);
  }

  return <section className="scan-result scan-task-result"><ClipboardCheck aria-hidden="true" /><div className="scan-task-content"><span>生产任务已识别</span><h2>{task.taskNo}</h2><p>{task.productCode} · {task.productName} · {task.operationName} · 批次 {task.batchNo}</p><div className="scan-task-facts"><span>工单 {task.workOrderNo}</span><span>计划 {task.plannedQuantity}</span><span>已完成 {task.goodQuantity}</span><StatusBadge value={task.status} />{task.assignedTo && <span>执行员工：{assigneeLabel(task.assignedTo, users)}</span>}</div>{task.status === "BLOCKED" && <small className="scan-readonly">前序工序尚未完成，本次扫码仅供查看，不能领取或开工。</small>}{task.assignedTo && !assignedToMe && <small className="scan-readonly">该任务已派给 {assigneeLabel(task.assignedTo, users)}，当前扫码只可查看信息与追溯。</small>}</div><div className="scan-result-actions">{canUse(user, "TRACE_VIEW") && <button className="button button-secondary button-small" type="button" onClick={() => void openTrace()}><Route aria-hidden="true" />查看溯源</button>}{canUse(user, "PROCESS_CARD_VIEW") && <button className="button button-secondary button-small" type="button" onClick={() => void openDocument()}><FileText aria-hidden="true" />查看电子工单</button>}{assignedToMe && task.status !== "COMPLETED" && <button className="button button-primary button-small" type="button" onClick={() => void openWorkbench()}><ArrowRight aria-hidden="true" />进入我的任务{task.sequenceNo > 1 ? "并核对交接" : ""}</button>}{canClaim && <button className="button button-primary button-small" type="button" disabled={pending} onClick={() => void claimTask()}><ClipboardCheck aria-hidden="true" />{pending ? "领取中" : "领取到我的任务"}</button>}{!assignedToMe && !canClaim && !task.assignedTo && task.status !== "COMPLETED" && <small className="scan-readonly">当前账号无此工序领取权限。</small>}</div>{error != null && <div className="scan-task-error"><ErrorNotice error={error} /></div>}</section>;
}

function BatchScanResult({ batchNo, tasks, users, user, onAudit }: { batchNo: string; tasks: Task[]; users: AccessUser[]; user: AccessUser; onAudit: (intent: string) => Promise<void> }) {
	const navigate = useNavigate();
	const [pending, setPending] = useState(false);
	const [error, setError] = useState<unknown>(null);
	const currentTask = batchCurrentTask(tasks, user.employeeCode);
	const awaitingLaunch = tasks.length > 0 && tasks.every((task) => task.status === "BLOCKED");
	const completed = tasks.filter((task) => task.status === "COMPLETED").length;
	const traceTask = currentTask ?? tasks[0]!;
	const assignedToMe = currentTask?.assignedTo === user.employeeCode;
	const canClaim = currentTask?.status === "READY" && currentTask.operationCode !== "WAX_INJECTION" && canUse(user, "TASK_SELF_CLAIM");
	const canInspect = currentTask != null && canUse(user, "TASK_DISPATCH");

	async function openTrace() {
		await onAudit("VIEW_BATCH_TRACE").catch(() => undefined);
		navigate(`/trace?orderId=${encodeURIComponent(traceTask.orderId)}`);
	}

	async function openWorkbench() {
		await onAudit("OPEN_BATCH_TASK").catch(() => undefined);
		navigate(`/workbench?task=${encodeURIComponent(currentTask!.id)}`);
	}

	async function claimTask() {
		if (!currentTask) return;
		setPending(true); setError(null);
		try {
			await api.tasks.claim(currentTask.id, user.employeeCode);
			await onAudit("CLAIM_BATCH_TASK").catch(() => undefined);
			navigate(`/workbench?task=${encodeURIComponent(currentTask.id)}`);
		} catch (caught) {
			setError(caught);
		} finally {
			setPending(false);
		}
	}

	return <section className="scan-result scan-batch-result"><Layers aria-hidden="true" /><div className="scan-task-content"><span>生产批次已识别</span><h2>{batchNo}</h2><p>{traceTask.productCode} · {traceTask.productName} · 工单 {traceTask.workOrderNo}</p><div className="scan-task-facts"><span>共 {tasks.length} 道工序</span><span>已完成 {completed} 道</span>{currentTask && <><span>当前工序 {currentTask.operationName}</span><StatusBadge value={currentTask.status} />{currentTask.assignedTo && <span>执行员工：{assigneeLabel(currentTask.assignedTo, users)}</span>}</>}</div><small className="scan-readonly">{awaitingLaunch ? `本批尚未确认投产，当前首道为 ${currentTask?.operationName ?? "射蜡"}；请由生产主管确认投产后再派工。` : "扫码仅核验与定位批次；进入任务、领取任务或交接均需在后续页面明确确认。"}</small></div><div className="scan-result-actions">{canUse(user, "TRACE_VIEW") && <button className="button button-secondary button-small" type="button" onClick={() => void openTrace()}><Route aria-hidden="true" />查看批次溯源</button>}{assignedToMe && currentTask?.status !== "COMPLETED" && <button className="button button-primary button-small" type="button" onClick={() => void openWorkbench()}><ArrowRight aria-hidden="true" />进入我的当前任务</button>}{canClaim && <button className="button button-primary button-small" type="button" disabled={pending} onClick={() => void claimTask()}><ClipboardCheck aria-hidden="true" />{pending ? "领取中" : "领取当前工序"}</button>}{canInspect && !awaitingLaunch && !assignedToMe && !canClaim && <button className="button button-secondary button-small" type="button" onClick={() => void openWorkbench()}><ArrowRight aria-hidden="true" />查看当前工序</button>}</div>{error != null && <div className="scan-task-error"><ErrorNotice error={error} /></div>}</section>;
}

function AssetScanResult({ asset, user, onAudit }: { asset: ResourceAsset; user: AccessUser; onAudit: (intent: string) => Promise<void> }) {
	const navigate = useNavigate();
  const mold = asset.assetType === "MOLD";
  const carrier = asset.assetType === "CARRIER";
  const destination = mold && (canUse(user, "MOLD_ISSUE") || canUse(user, "MOLD_RECEIVE") || canUse(user, "MOLD_REQUEST"))
    ? { to: "/molds", label: "进入模具台账", icon: Wrench }
    : carrier && canUse(user, "CART_OPERATE")
      ? { to: "/cart-transfers", label: "进入周转车流转", icon: Truck }
      : null;
  const Icon = mold ? Wrench : carrier ? Truck : Box;
  async function openDestination() {
		await onAudit(mold ? "OPEN_MOLD_LEDGER" : carrier ? "OPEN_CART_TRANSFER" : "OPEN_ASSET").catch(() => undefined);
		if (destination) navigate(destination.to);
	}
  return <section className="scan-result"><Icon aria-hidden="true" /><div><span>{mold ? "模具核验" : carrier ? "周转车核验" : "资产核验"}</span><h2>{asset.assetCode}</h2><p>{asset.assetName} · 位置 {asset.locationCode ?? "未登记"} · {mold ? (asset.moldCustodyStatus === "IN_STOCK" ? "在库" : asset.moldCustodyStatus === "INTERNAL_IN_USE" ? "内部领用中" : "对外送修/召回") : "可用"}</p><StatusBadge value={asset.status} /></div>{destination ? <button className="button button-primary" type="button" onClick={() => void openDestination()}>{destination.label}<ArrowRight aria-hidden="true" /></button> : <small className="scan-readonly">已完成核验；当前账号无该资产的后续操作权限。</small>}</section>;
}
