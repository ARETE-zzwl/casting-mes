import { FormEvent, useEffect, useMemo, useState } from "react";
import { CheckCircle2, ChevronRight, ClipboardCheck, FileText, Play, RefreshCw, ScanLine, Send, ShieldAlert } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatQuantity, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import { deviceCode, flushPendingReports, pendingReportCount, queueReport } from "../offlineQueue";
import type { AccessUser, DocumentPreview, HandoffReceipt, OperationSop, ReportFormProfile, ReportLedgerItem, Task, UpstreamReports } from "../types";
import type { RoleWorkspaceProfile } from "./roleWorkspace";
import { roleWorkspaceFor } from "./roleWorkspace";

function reportingHint(task: Task) {
  if (task.reportingMode === "HANDOFF_TO_TREE") return "完成修蜡后不录件数，确认交给组树。";
	if (task.reportingMode === "HANDOFF_TO_NEXT") return "本工序只确认交接；由下一工序核对实际接收数量。";
  if (task.reportingMode === "TREE_COUNT") return "录入树数与每树件数，系统计算最终合格件数。";
  if (task.reportingMode === "FIXED_QUANTITY") return `固定任务数量：${formatQuantity(task.assignedQuantity ?? task.plannedQuantity)}。`;
  return "录入本次合格数和报废数。";
}

function isManualShellTask(task: Task) {
  return task.operationCode === "MANUAL_SHELL_BUILDING" || task.shellLineMode === "MANUAL";
}

export function WorkerWorkbenchPage({ user, profile: suppliedProfile }: { user: AccessUser; profile?: RoleWorkspaceProfile }) {
	const profile = suppliedProfile ?? roleWorkspaceFor(user);
  const [searchParams] = useSearchParams();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sop, setSop] = useState<OperationSop | null>(null);
  const [sopError, setSopError] = useState<unknown>(null);
  const [reportTask, setReportTask] = useState<Task | null>(null);
  const [partialFlowTask, setPartialFlowTask] = useState<Task | null>(null);
  const [jobCard, setJobCard] = useState<DocumentPreview | null>(null);
  const [showShellScan, setShowShellScan] = useState(false);
	const [showReceipt, setShowReceipt] = useState(false);
	const [receipt, setReceipt] = useState<HandoffReceipt | null>(null);
	const [upstreamReports, setUpstreamReports] = useState<UpstreamReports | null>(null);
	const [reportForm, setReportForm] = useState<ReportFormProfile | null>(null);
	const [receiptExceptionType, setReceiptExceptionType] = useState("NONE");
  const [pending, setPending] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
	const [pendingSyncCount, setPendingSyncCount] = useState(() => pendingReportCount(user.employeeCode));
	const [historyDetail, setHistoryDetail] = useState<ReportLedgerItem | null>(null);
	const state = useAsyncData(async () => {
    const tasks = await api.tasks.list(undefined, user.employeeCode);
    const claimable = user.permissions.includes("TASK_SELF_CLAIM") ? await api.tasks.claimable(user.employeeCode) : [];
    return { tasks, claimable };
  }, [user.employeeCode, user.permissions]);
	const reportHistory = useAsyncData(
		() => api.execution.reportLedger({ viewerCode: user.employeeCode, workerCode: user.employeeCode }),
		[user.employeeCode]
	);

  const tasks = state.data?.tasks ?? [];
  const claimableTasks = state.data?.claimable ?? [];
  const activeTasks = useMemo(() => tasks.filter((task) => task.status !== "COMPLETED"), [tasks]);
  const completedTasks = useMemo(() => tasks.filter((task) => task.status === "COMPLETED"), [tasks]);
  const selectedTask = activeTasks.find((task) => task.id === selectedId) ?? activeTasks[0] ?? null;

  useEffect(() => {
    const scannedTaskId = searchParams.get("task");
    if (scannedTaskId && activeTasks.some((task) => task.id === scannedTaskId)) setSelectedId(scannedTaskId);
  }, [activeTasks, searchParams]);

  useEffect(() => {
    if (!selectedTask) return;
    let current = true;
    setSop(null); setSopError(null);
    api.sops.get(selectedTask.operationCode).then((value) => current && setSop(value)).catch((error) => current && setSopError(error));
    return () => { current = false; };
  }, [selectedTask?.id, selectedTask?.operationCode]);

	useEffect(() => {
		if (!selectedTask || selectedTask.status === "COMPLETED" || selectedTask.sequenceNo <= 1) { setReceipt(null); return; }
		let current = true;
		api.tasks.handoffReceipt(selectedTask.id).then((value) => current && setReceipt(value)).catch(() => current && setReceipt(null));
		return () => { current = false; };
	}, [selectedTask?.id, selectedTask?.sequenceNo, selectedTask?.status]);

	useEffect(() => {
		if (!selectedTask || selectedTask.status === "COMPLETED") { setUpstreamReports(null); return; }
		let current = true;
		api.tasks.upstreamReports(selectedTask.id).then((value) => current && setUpstreamReports(value)).catch(() => current && setUpstreamReports(null));
		return () => { current = false; };
	}, [selectedTask?.id, selectedTask?.status]);

	useEffect(() => {
		if (!reportTask) { setReportForm(null); return; }
		let current = true;
		api.reportFormProfiles.get(reportTask.operationCode).then((profile) => current && setReportForm(profile)).catch(() => current && setReportForm(null));
		return () => { current = false; };
	}, [reportTask?.id, reportTask?.operationCode]);

	useEffect(() => {
		let current = true;
		setPendingSyncCount(pendingReportCount(user.employeeCode));
		const flush = async () => {
			if (pendingReportCount(user.employeeCode) === 0) {
				setPendingSyncCount(0);
				return;
			}
			const remaining = await flushPendingReports(user.employeeCode);
			if (!current) return;
			setPendingSyncCount(remaining);
			if (remaining === 0) await Promise.all([state.reload(), reportHistory.reload()]);
		};
		void flush();
		window.addEventListener("online", flush);
		return () => { current = false; window.removeEventListener("online", flush); };
	}, [user.employeeCode, state.reload, reportHistory.reload]);

  async function startTask() {
    if (!selectedTask) return;
		if (receipt?.status === "PENDING_RECEIPT") { setReceiptExceptionType("NONE"); setShowReceipt(true); return; }
    setPending(true); setActionError(null);
    try { await api.tasks.start(selectedTask.id, user.employeeCode); await state.reload(); }
    catch (error) { setActionError(error); }
    finally { setPending(false); }
  }

  async function claim(task: Task) {
    setPending(true); setActionError(null);
    try { await api.tasks.claim(task.id, user.employeeCode); setSelectedId(task.id); await state.reload(); }
    catch (error) { setActionError(error); }
    finally { setPending(false); }
  }

  async function handoff(task: Task) {
    setPending(true); setActionError(null);
    try { await api.tasks.handoffWithoutCount(task.id, user.employeeCode); await state.reload(); }
    catch (error) { setActionError(error); }
    finally { setPending(false); }
  }

	async function acceptReceipt(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!selectedTask) return;
		const form = new FormData(event.currentTarget);
		setPending(true); setActionError(null);
		try {
			const photo = form.get("photo");
			const photoUrl = photo instanceof File && photo.size > 0 ? (await api.files.upload("EXECUTION_PHOTO", photo)).url : undefined;
			const accepted = await api.tasks.acceptHandoffReceipt(selectedTask.id, user.employeeCode, {
				receivedQuantity: Number(form.get("receivedQuantity")),
				exceptionType: receiptExceptionType === "NONE" ? undefined : receiptExceptionType,
				exceptionReason: String(form.get("exceptionReason") || "") || undefined,
				photoUrl,
				deviceCode: deviceCode(), workstationCode: user.unitCode
			});
			setReceipt(accepted); setShowReceipt(false); await state.reload();
		} catch (error) { setActionError(error); }
		finally { setPending(false); }
	}

  async function openJobCard() {
    if (!selectedTask) return;
    setPending(true); setActionError(null);
    try {
      setJobCard(await api.documents.preview({
        documentType: "PROCESS_CARD",
        entityId: selectedTask.id,
        actorCode: user.employeeCode,
        selectedFields: ["TASK_NO", "WORK_ORDER_NO", "PRODUCT", "OPERATION", "BATCH_NO", "PLANNED_QUANTITY", "SOP_KEY_PARAMETERS", "SOP", "PROCESS_IMAGE_URLS", "SAFETY", "QUALITY_POINTS"]
      }));
    } catch (error) { setActionError(error); }
    finally { setPending(false); }
  }

	async function report(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!reportTask) return;
    const form = new FormData(event.currentTarget);
    setPending(true); setActionError(null);
		const operationId = crypto.randomUUID();
		const context = { deviceCode: String(form.get("deviceCode") || deviceCode()), workstationCode: String(form.get("workstationCode") || user.unitCode) };
		const partialFlowQuantity = Number(form.get("partialFlowQuantity") || 0);
		try {
			const photo = form.get("photo");
			const photoUrl = photo instanceof File && photo.size > 0 ? (await api.files.upload("EXECUTION_PHOTO", photo)).url : undefined;
		  if (reportTask.reportingMode === "TREE_COUNT") {
        await api.tasks.reportTree(reportTask.id, user.employeeCode, {
          operationId,
          treeCount: Number(form.get("treeCount")),
          piecesPerTree: Number(form.get("piecesPerTree")),
			  scrapQuantity: Number(form.get("scrapQuantity")), photoUrl, ...context
        });
      } else {
        await api.tasks.report(reportTask.id, user.employeeCode, {
          operationId,
          goodQuantity: Number(form.get("goodQuantity")),
			  scrapQuantity: Number(form.get("scrapQuantity")), photoUrl, ...context
        });
			if (partialFlowQuantity > 0) {
				await api.tasks.releasePartialFlow(reportTask.id, partialFlowQuantity, user.employeeCode, photoUrl);
			}
      }
			setReportTask(null); await Promise.all([state.reload(), reportHistory.reload()]);
    } catch (error) {
		if ((!navigator.onLine || error instanceof TypeError) && partialFlowQuantity <= 0) {
			const payload = reportTask.reportingMode === "TREE_COUNT"
				? { operationId, treeCount: Number(form.get("treeCount")), piecesPerTree: Number(form.get("piecesPerTree")), scrapQuantity: Number(form.get("scrapQuantity")), ...context }
				: { operationId, goodQuantity: Number(form.get("goodQuantity")), scrapQuantity: Number(form.get("scrapQuantity")), ...context };
			queueReport({ id: crypto.randomUUID(), kind: reportTask.reportingMode === "TREE_COUNT" ? "TREE_REPORT" : "REPORT", taskId: reportTask.id, operatorCode: user.employeeCode, payload, createdAt: new Date().toISOString() });
			setPendingSyncCount(pendingReportCount(user.employeeCode)); setReportTask(null);
		} else setActionError(error);
	}
    finally { setPending(false); }
  }

	async function reportManualShellScan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true); setActionError(null);
		try {
			const photo = form.get("photo");
			const photoUrl = photo instanceof File && photo.size > 0 ? (await api.files.upload("EXECUTION_PHOTO", photo)).url : undefined;
		  await api.labor.reportManualShellByScan({
        scannedValue: String(form.get("scannedValue")), layerCount: Number(form.get("layerCount")),
        dryingMinutes: Number(form.get("dryingMinutes")), quantity: Number(form.get("quantity")),
			note: String(form.get("note") || "") || undefined, operatorCode: user.employeeCode,
			nextAction: String(form.get("nextAction")) as "WAIT_NEXT_LAYER" | "FLOW_TO_NEXT", photoUrl
      });
			setShowShellScan(false); await Promise.all([state.reload(), reportHistory.reload()]);
    } catch (error) { setActionError(error); }
    finally { setPending(false); }
  }

	async function releasePartialFlow(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!partialFlowTask) return;
		const quantity = Number(new FormData(event.currentTarget).get("quantity"));
		setPending(true); setActionError(null);
		try {
			const form = new FormData(event.currentTarget);
			const photo = form.get("photo");
			const photoUrl = photo instanceof File && photo.size > 0 ? (await api.files.upload("EXECUTION_PHOTO", photo)).url : undefined;
			await api.tasks.releasePartialFlow(partialFlowTask.id, quantity, user.employeeCode, photoUrl);
			setPartialFlowTask(null); await Promise.all([state.reload(), reportHistory.reload()]);
		} catch (error) { setActionError(error); }
		finally { setPending(false); }
	}

  if (state.loading) return <LoadingState label="正在准备您的工作任务" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const remaining = selectedTask ? selectedTask.plannedQuantity - selectedTask.goodQuantity - selectedTask.scrapQuantity : 0;
  const reportedQuantity = selectedTask ? Math.min(selectedTask.plannedQuantity, selectedTask.goodQuantity + selectedTask.scrapQuantity) : 0;
  const hasReceiptAction = receipt != null && receipt.status !== "NOT_REQUIRED";
  const operationFocus = sop == null
    ? []
    : sop.keyParameters?.length
      ? sop.keyParameters.slice(0, 5).map((parameter) => ({ label: parameter.name, value: parameter.value }))
      : [
          { label: "工前准备", value: sop.preparationNote },
          ...sop.steps.slice(0, 2).map((step) => ({ label: step.title, value: step.instruction })),
          ...(sop.qualityPoints[0] ? [{ label: "质量重点", value: sop.qualityPoints[0] }] : [])
        ];

  return <>
    <section className={`role-workspace-hero role-tone-${profile.tone} worker-role-hero`} aria-label={`${profile.roleLabel}工作台`}>
      <div><span>{profile.roleLabel}</span><h1>{profile.title}</h1><p>{profile.focus}</p></div>
      <div className="role-workspace-identity"><strong>{user.name}</strong><small>{user.employeeCode} · {user.unitName}</small></div>
    </section>
    <PageHeader title="我的任务" description="按任务卡逐项完成现场作业；仅显示本账号已获派的电子工单。" action={<div className="header-actions"><Link className="button button-secondary" to="/scan"><ScanLine aria-hidden="true" />扫一扫</Link><button className="icon-button" type="button" onClick={state.reload} aria-label="刷新我的任务"><RefreshCw aria-hidden="true" /></button></div>} />
    <section className="worker-mobile-quick-actions" aria-label="现场快捷入口">
      <Link to="/scan"><ScanLine aria-hidden="true" /><span>扫码核对</span></Link>
      <button type="button" disabled={!selectedTask || pending} onClick={() => void openJobCard()}><FileText aria-hidden="true" /><span>本工序卡</span></button>
      <Link to="/guide"><ClipboardCheck aria-hidden="true" /><span>工序指引</span></Link>
    </section>
    {actionError && <ErrorNotice error={actionError} />}
		{pendingSyncCount > 0 && <div className="error-notice"><strong>待同步报工 {pendingSyncCount} 条</strong><span>网络恢复后自动补传；原操作号会保留，不会重复入账。</span></div>}
    {claimableTasks.length > 0 && <section className="worker-claim-panel" aria-label="可自主领取任务"><header><div><span>可自主开工</span><h2>已流转到本工序的任务</h2><p>先领取任务，再核对上游实物、数量和异常，确认接收后开始作业。射蜡必须由主管领模派工，不在此列表。</p></div></header><div>{claimableTasks.slice(0, 5).map((task) => <article key={task.id}><div><strong>{task.operationName}</strong><span>{task.taskNo} / {task.batchNo}</span><small>{formatQuantity(task.plannedQuantity)} 件待处理</small></div><button className="button button-primary button-small" type="button" disabled={pending} onClick={() => void claim(task)}><ClipboardCheck aria-hidden="true" />领取并核对</button></article>)}</div></section>}
    <section className="worker-summary" aria-label="我的任务概况"><div><span>待处理</span><strong>{activeTasks.length}</strong></div><div><span>今日完成</span><strong>{completedTasks.length}</strong></div><div><span>当前工号</span><strong>{user.employeeCode}</strong></div></section>
    {activeTasks.length === 0 ? <section className="section-block worker-empty"><EmptyState title="当前没有待处理任务" description="新派发任务会显示在这里。" /></section> : <div className="worker-layout">
      <aside className="worker-task-list" aria-label="待处理任务"><h2>待处理任务</h2>{activeTasks.map((task) => <button type="button" key={task.id} className={selectedTask?.id === task.id ? "active" : ""} onClick={() => setSelectedId(task.id)}><span><strong>{task.operationName}</strong><small>{task.productName} · {task.productMaterial ?? "材质待确认"}</small><small>{task.taskNo} · {task.batchNo}</small></span><StatusBadge value={task.status} /><ChevronRight aria-hidden="true" /></button>)}</aside>
      {selectedTask && <section className="worker-job" aria-labelledby="current-job-title">
        <header className="worker-job-header"><div><span className="eyebrow">当前工序 / 第 {selectedTask.sequenceNo} 道</span><h2 id="current-job-title">{selectedTask.operationName}</h2><p className="worker-product-identity"><strong>{selectedTask.productName}</strong><span>{selectedTask.productCode} · {selectedTask.productMaterial ?? "材质待确认"}</span></p><p>{selectedTask.taskNo} / {selectedTask.batchNo}</p></div><div className="worker-quantity"><span>待处理数量</span><strong>{formatQuantity(remaining)}</strong><button className="button button-secondary button-small" type="button" disabled={pending} onClick={() => void openJobCard()}><ClipboardCheck aria-hidden="true" />电子工单</button></div></header>
        <section className="worker-job-progress" aria-label="本工序进度">
          <div><span>本工序已报</span><strong>{formatQuantity(reportedQuantity)} / {formatQuantity(selectedTask.plannedQuantity)}</strong></div>
          <progress value={reportedQuantity} max={selectedTask.plannedQuantity} aria-label={`${selectedTask.operationName}已报数量`} />
          <small>合格 {formatQuantity(selectedTask.goodQuantity)} · 报废 {formatQuantity(selectedTask.scrapQuantity)} · 余量 {formatQuantity(remaining)}</small>
        </section>
        <div className="sop-preparation"><ClipboardCheck aria-hidden="true" /><div><strong>本任务报工方式</strong><p>{reportingHint(selectedTask)}</p></div></div>
        {sopError ? <ErrorNotice error={sopError} /> : !sop ? <LoadingState label="正在加载作业指导" /> : <>
		  <section className="worker-operation-focus" aria-label="本工序关键要点"><header><div><h3>{sop.keyParameters?.length ? "本工序关键参数" : "本工序关键要点"}</h3><p>仅显示当前作业需要优先核对的信息。</p></div><span>SOP {sop.version}</span></header><div className="worker-operation-focus-grid">{operationFocus.map((item) => <article key={`${item.label}-${item.value}`}><strong>{item.label}</strong><p>{item.value}</p></article>)}</div></section>
		  <details className="worker-sop-details"><summary><ClipboardCheck aria-hidden="true" />查看完整 SOP 与质量要求</summary><div className="safety-band"><ShieldAlert aria-hidden="true" /><div><strong>安全提示</strong><p>{sop.safetyNotice}</p></div><span>SOP {sop.version}</span></div><div className="sop-section"><h3><ClipboardCheck aria-hidden="true" />按步骤作业</h3><ol className="sop-steps">{sop.steps.map((step) => <li key={step.stepNo}><span className="sop-step-number">{step.stepNo}</span><div><strong>{step.title}</strong><p>{step.instruction}</p></div></li>)}</ol></div><div className="sop-section quality-checks"><h3><CheckCircle2 aria-hidden="true" />质量要点</h3>{sop.qualityPoints.map((point) => <p key={point}>{point}</p>)}</div></details>
        </>}
		{hasReceiptAction && selectedTask.sequenceNo > 1 && <div className={`worker-receipt-band ${receipt.status === "EXCEPTION" ? "has-exception" : ""}`}><ClipboardCheck aria-hidden="true" /><div><strong>上游交接核对</strong><p>{receipt.sourceOperationName} / {receipt.sourceTaskNo}，应收 {formatQuantity(receipt.expectedQuantity)}；{receipt.receivedQuantity == null ? "尚未确认接收" : `实收 ${formatQuantity(receipt.receivedQuantity)}`}</p>{receipt.exceptionReason && <small>{receipt.exceptionReason}</small>}</div><StatusBadge value={receipt.status} />{receipt.status === "PENDING_RECEIPT" && <button className="button button-primary button-small worker-receipt-action" type="button" onClick={() => { setReceiptExceptionType("NONE"); setShowReceipt(true); }}>确认接收</button>}</div>}
		{upstreamReports?.sourceTaskId && <section className="upstream-reports" aria-label="上游报工留痕"><header><div><span>上游报工留痕</span><strong>{upstreamReports.sourceOperationName} / {upstreamReports.sourceTaskNo}</strong></div><small>照片、数量与操作记录</small></header>{upstreamReports.reports.length === 0 ? <p className="upstream-reports-empty">上游尚未上传报工照片或报工记录。</p> : <div className="upstream-report-list">{upstreamReports.reports.map((item) => <article key={item.id}><div><strong>合格 {formatQuantity(item.goodQuantity)} / 报废 {formatQuantity(item.scrapQuantity)}</strong><small>{item.operatorCode} · {new Date(item.occurredAt).toLocaleString("zh-CN", { hour12: false })}</small>{item.workstationCode && <small>工位：{item.workstationCode}</small>}</div>{item.photoUrl ? <a href={item.photoUrl} target="_blank" rel="noreferrer" aria-label="查看上游报工照片"><img src={item.photoUrl} alt={`${upstreamReports.sourceOperationName}报工现场`} /></a> : <span className="upstream-report-no-photo">未上传照片</span>}</article>)}</div>}</section>}
			<footer className="worker-action-bar"><div><strong>{selectedTask.operationName} · {selectedTask.taskNo}</strong><span>{selectedTask.status === "ASSIGNED" ? "核对电子工单后即可开工" : `本次可处理余量 ${formatQuantity(remaining)}，提交前请核对数量。`}</span></div>{isManualShellTask(selectedTask) && selectedTask.status === "IN_PROGRESS" ? <button className="button button-primary worker-primary-action" type="button" disabled={pending} onClick={() => setShowShellScan(true)}><ScanLine aria-hidden="true" />扫码报制壳进度</button> : selectedTask.status === "ASSIGNED" ? <button className="button button-primary worker-primary-action" type="button" disabled={pending} onClick={startTask}><Play aria-hidden="true" />开始作业</button> : (selectedTask.reportingMode === "HANDOFF_TO_TREE" || selectedTask.reportingMode === "HANDOFF_TO_NEXT") ? <button className="button button-primary worker-primary-action" type="button" disabled={pending} onClick={() => handoff(selectedTask)}><Send aria-hidden="true" />确认交接</button> : <button className="button button-primary worker-primary-action" type="button" disabled={pending} onClick={() => setReportTask(selectedTask)}><Send aria-hidden="true" />完成并报工</button>}</footer>
		{selectedTask.status === "IN_PROGRESS" && selectedTask.goodQuantity > 0 && selectedTask.operationCode !== "FINAL_COUNT" && <div className="worker-secondary-action"><button className="button button-secondary button-small" type="button" disabled={pending} onClick={() => setPartialFlowTask(selectedTask)}><Send aria-hidden="true" />部分合格件先行流转</button><small>已报合格件可先送下一工序，当前任务继续处理余量。</small></div>}
		{hasReceiptAction && selectedTask.sequenceNo === 1 && <div className={`worker-receipt-band ${receipt.status === "EXCEPTION" ? "has-exception" : ""}`}><ClipboardCheck aria-hidden="true" /><div><strong>先行批交接核对</strong><p>{receipt.sourceOperationName} / {receipt.sourceTaskNo}，应收 {formatQuantity(receipt.expectedQuantity)}；{receipt.receivedQuantity == null ? "尚未确认接收" : `实收 ${formatQuantity(receipt.receivedQuantity)}`}</p>{receipt.exceptionReason && <small>{receipt.exceptionReason}</small>}</div><StatusBadge value={receipt.status} />{receipt.status === "PENDING_RECEIPT" && <button className="button button-primary button-small worker-receipt-action" type="button" onClick={() => { setReceiptExceptionType("NONE"); setShowReceipt(true); }}>确认接收</button>}</div>}
		</section>}
    </div>}
    {completedTasks.length > 0 && <section className="worker-completed"><h2>已完成任务</h2><div>{completedTasks.slice(0, 6).map((task) => <span key={task.id}><CheckCircle2 aria-hidden="true" />{task.operationName} / {formatQuantity(task.goodQuantity)}</span>)}</div></section>}
		<section className="worker-completed" aria-label="我的历史报工"><h2>我的历史报工</h2>{reportHistory.loading ? <p>正在加载历史报工...</p> : reportHistory.data?.length ? <div>{reportHistory.data.slice(0, 8).map((item) => <button className="worker-history-entry" type="button" key={item.id} onClick={() => setHistoryDetail(item)}><CheckCircle2 aria-hidden="true" />{item.operationName} / 合格 {formatQuantity(item.goodQuantity)} / 报废 {formatQuantity(item.scrapQuantity)} / {new Date(item.occurredAt).toLocaleDateString("zh-CN")}</button>)}</div> : <p>暂无已入账的报工记录。</p>}</section>
		{historyDetail && <Modal title={`${historyDetail.operationName} · 报工详情`} description="该记录已入账，保留订单、产品、设备、工位和现场照片等事实信息。" width="small" onClose={() => setHistoryDetail(null)}><dl className="document-preview-fields"><div><dt>订单 / 产品</dt><dd>{historyDetail.orderNo} · {historyDetail.productCode} · {historyDetail.productName}</dd></div><div><dt>任务 / 工序</dt><dd>{historyDetail.taskNo} · {historyDetail.operationName}</dd></div><div><dt>报工结果</dt><dd>合格 {formatQuantity(historyDetail.goodQuantity)}；报废 {formatQuantity(historyDetail.scrapQuantity)}</dd></div><div><dt>操作信息</dt><dd>{historyDetail.operatorCode} · {new Date(historyDetail.occurredAt).toLocaleString("zh-CN", { hour12: false })}</dd></div><div><dt>设备 / 工位</dt><dd>{historyDetail.deviceCode ?? "未登记"} · {historyDetail.workstationCode ?? "未登记"}</dd></div><div><dt>现场照片</dt><dd>{historyDetail.photoUrl ? <a className="text-link" href={historyDetail.photoUrl} target="_blank" rel="noreferrer">查看报工照片</a> : "未上传"}</dd></div></dl></Modal>}
		{reportTask && <Modal title={`完成 / ${reportTask.operationName}`} description={reportTask.reportingMode === "TREE_COUNT" ? "树数乘以每树件数，加报废数必须等于待组树数量。" : `本次最大可报 ${formatQuantity(reportTask.plannedQuantity - reportTask.goodQuantity - reportTask.scrapQuantity)}`} width="small" onClose={() => !pending && setReportTask(null)}>{actionError != null && <ErrorNotice error={actionError} />}<form onSubmit={report}><div className="form-grid">{reportTask.reportingMode === "TREE_COUNT" ? <><Field label="最终树量" required><input name="treeCount" type="number" min="1" step="1" required autoFocus /></Field><Field label="每树件数" required><input name="piecesPerTree" type="number" min="1" step="1" required /></Field><Field label="报废件数" required><input name="scrapQuantity" type="number" min="0" step="1" defaultValue="0" required /></Field></> : <><Field label="本次合格数" required><input name="goodQuantity" type="number" min="0" step="0.001" defaultValue={remaining} required autoFocus /></Field><Field label="本次报废数" required><input name="scrapQuantity" type="number" min="0" step="0.001" defaultValue="0" required /></Field></>}{reportForm?.showDevice && <Field label="设备编号" required={reportForm.requireDevice}><input name="deviceCode" maxLength={64} defaultValue={deviceCode()} required={reportForm.requireDevice} /></Field>}{reportForm?.showWorkstation && <Field label="工位编号" required={reportForm.requireWorkstation}><input name="workstationCode" maxLength={64} defaultValue={user.unitCode} required={reportForm.requireWorkstation} /></Field>}{(reportForm == null || reportForm.showPhoto) && <Field label={reportForm?.requirePhoto ? "现场照片" : "现场照片（可选）"} required={reportForm?.requirePhoto} hint="用于留痕，支持 JPG、PNG、WEBP，最大 20MB"><input name="photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" required={reportForm?.requirePhoto} /></Field>}</div><SubmitActions pending={pending} submitLabel="确认提交" onCancel={() => setReportTask(null)} /></form></Modal>}
    {jobCard && <Modal title="电子工单与工艺卡" description="仅展示本任务执行所需信息，不包含客户联系方式与订单敏感字段。" width="small" onClose={() => setJobCard(null)}><dl className="document-preview-fields">{jobCard.fields.map((field) => <div key={field.code}><dt>{field.label}</dt><dd>{field.code === "PROCESS_IMAGE_URLS" ? <div className="worker-process-images">{field.value.split("\n").filter(Boolean).map((url, index) => <a key={url} href={url} target="_blank" rel="noreferrer"><img src={url} alt={`本工序工艺图 ${index + 1}`} /></a>)}</div> : field.value || "-"}</dd></div>)}</dl></Modal>}
		{showShellScan && selectedTask && <Modal title="扫码报制壳进度" description="每次只报下一层。等待下一层会保持任务生产中；选择流转下工序会完成制壳并解锁后续任务。" width="small" onClose={() => !pending && setShowShellScan(false)}><form onSubmit={reportManualShellScan}><div className="form-grid"><Field label="工单二维码或批次号" required><input name="scannedValue" defaultValue={`MES:TASK:${selectedTask.id}`} required autoFocus /></Field><Field label="本次完成层号" required><input name="layerCount" type="number" min="1" step="1" defaultValue="1" required /></Field><Field label="本次干燥分钟" required><input name="dryingMinutes" type="number" min="0" step="1" defaultValue="0" required /></Field><Field label="本次数量" required><input name="quantity" type="number" min="0.001" step="0.001" defaultValue={selectedTask.plannedQuantity - selectedTask.goodQuantity - selectedTask.scrapQuantity} required /></Field><Field label="本层后续动作" required><select name="nextAction" defaultValue="WAIT_NEXT_LAYER"><option value="WAIT_NEXT_LAYER">等待下一层制壳</option><option value="FLOW_TO_NEXT">制壳完成，流转下工序</option></select></Field><Field label="现场照片（可选）"><input name="photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" /></Field><Field label="备注"><input name="note" maxLength={500} /></Field></div><SubmitActions pending={pending} submitLabel="提交制壳进度" onCancel={() => setShowShellScan(false)} /></form></Modal>}
		{showReceipt && selectedTask && receipt && <Modal title={`接收上游流转 / ${selectedTask.operationName}`} description={`请现场核对 ${receipt.sourceOperationName} 交来的实物。差异会自动进入主管交接异常待办，不会阻断本工序。`} width="small" onClose={() => !pending && setShowReceipt(false)}><form onSubmit={acceptReceipt}><div className="form-grid"><Field label="上游应交数量"><strong>{formatQuantity(receipt.expectedQuantity)}</strong></Field><Field label="实际接收数量" required><input name="receivedQuantity" type="number" min="0" step="0.001" defaultValue={receipt.expectedQuantity} required autoFocus /></Field><Field label="异常类别"><select value={receiptExceptionType} onChange={(event) => setReceiptExceptionType(event.target.value)}><option value="NONE">无异常</option><option value="QUANTITY_MISMATCH">数量不符</option><option value="MISSING_ITEMS">缺件</option><option value="QUALITY_SUSPECT">质量待判</option><option value="DAMAGED">破损</option><option value="OTHER">其他</option></select></Field><Field label="异常说明（可选）" hint="不填写时系统会按异常类别自动生成说明"><textarea name="exceptionReason" rows={3} maxLength={500} placeholder="例如：缺少 2 件，已放入待判区" /></Field><Field label="现场照片（可选）"><input name="photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" /></Field></div><SubmitActions pending={pending} submitLabel="确认接收" onCancel={() => setShowReceipt(false)} /></form></Modal>}
    {partialFlowTask && <Modal title="部分合格件先行流转" description={`当前已报合格 ${formatQuantity(partialFlowTask.goodQuantity)}。系统将为本次数量建立独立先行批，下一工序可以立即领取；本任务仍保留未完成余量。`} width="small" onClose={() => !pending && setPartialFlowTask(null)}>{actionError != null && <ErrorNotice error={actionError} />}<form onSubmit={releasePartialFlow}><div className="form-grid"><Field label="提前流转合格数" required hint="不可超过尚未先行流转的已报合格数"><input name="quantity" type="number" min="0.001" max={partialFlowTask.goodQuantity} step="0.001" defaultValue={partialFlowTask.goodQuantity} required autoFocus /></Field><Field label="交接照片（可选）" hint="随本次先行流转留档"><input name="photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" /></Field></div><SubmitActions pending={pending} submitLabel="生成先行批并流转" onCancel={() => setPartialFlowTask(null)} /></form></Modal>}
  </>;
}
