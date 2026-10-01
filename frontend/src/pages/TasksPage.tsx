import { FormEvent, useEffect, useState } from "react";
import { Factory, Play, Printer, RefreshCw, RotateCcw, Send, UserPlus } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import {
  EmptyState,
  ErrorNotice,
  Field,
  formatQuantity,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { CompensationMode, DispatchRecommendation, ShellProgressSummary, Task, TaskReportingMode, TaskStatus } from "../types";

const filters: Array<{ value: "" | TaskStatus; label: string }> = [
	{ value: "BLOCKED", label: "等待前工序" },
  { value: "", label: "全部" },
  { value: "READY", label: "待派工" },
  { value: "ASSIGNED", label: "已派工" },
  { value: "IN_PROGRESS", label: "生产中" },
  { value: "COMPLETED", label: "已完成" }
];

const reportingModes: Array<{ value: TaskReportingMode; label: string }> = [
  { value: "SELF_REPORTED_QUANTITY", label: "员工自主报完工件数" },
  { value: "FIXED_QUANTITY", label: "主管固定派工数量" },
  { value: "HANDOFF_TO_TREE", label: "修蜡免计数，交组树确认" },
	{ value: "HANDOFF_TO_NEXT", label: "仅交接，下一工序确认数量" },
  { value: "TREE_COUNT", label: "组树按树数与每树件数报工" }
];

const postTreatmentProcessOptions = [
	{ code: "SAND_BLASTING", label: "喷砂", rule: "PIECE_KG" },
	{ code: "GRINDING", label: "打磨", rule: "PIECE_KG" },
	{ code: "POLISHING", label: "抛光", rule: "PIECE_KG" },
	{ code: "PICKLING", label: "酸洗", rule: "PIECE_KG" },
	{ code: "HEAT_TREATMENT", label: "热处理", rule: "HOURLY" },
	{ code: "MACHINING", label: "机加工", rule: "HOURLY" },
	{ code: "PRESSURE_TEST", label: "压力试验", rule: "HOURLY" },
	{ code: "OTHER", label: "其他", rule: "HOURLY" }
] as const;

type DispatchDraft = {
  workerCode: string;
  reportingMode: TaskReportingMode;
  fixedQuantity?: number;
  productionQuantity?: number;
  shellLineMode?: "AUTOMATED" | "MANUAL";
};

function isShellTask(task: Task) {
  return task.operationCode === "SHELL_BUILDING" || task.operationCode === "MANUAL_SHELL_BUILDING";
}

function shellLineLabel(mode: Task["shellLineMode"]) {
  return mode === "AUTOMATED" ? "自动化制壳线" : mode === "MANUAL" ? "手动制壳线" : "待选择制壳线";
}

function allowedModes(task: Task) {
  if (task.operationCode === "WAX_REPAIR") {
    return reportingModes.filter((item) => item.value !== "TREE_COUNT");
  }
  if (task.operationCode === "TREE_ASSEMBLY") {
    return reportingModes.filter((item) => item.value === "TREE_COUNT");
  }
	return reportingModes.filter((item) => item.value === "SELF_REPORTED_QUANTITY" || item.value === "FIXED_QUANTITY" || item.value === "HANDOFF_TO_NEXT");
}

function modeLabel(mode: TaskReportingMode) {
  return reportingModes.find((item) => item.value === mode)?.label ?? mode;
}

function compensationRuleLabel(mode: CompensationMode) {
	return ({ PIECE_PCS: "按件计件", PIECE_TREE: "按树计件", HOURLY: "按工时", PIECE_KG: "按公斤计件", HANDOFF_ONLY: "仅交接不计薪" } as Record<CompensationMode, string>)[mode];
}

function postTreatmentRule(codes: string[]) {
	return codes.length > 0 && codes.every((code) => postTreatmentProcessOptions.find((item) => item.code === code)?.rule === "PIECE_KG")
		? "按公斤计件" : "按工时";
}

export function TasksPage({ operatorCode }: { operatorCode: string }) {
  const navigate = useNavigate();
	const [searchParams, setSearchParams] = useSearchParams();
  const [status, setStatus] = useState<"" | TaskStatus>("");
  const [onlyMine, setOnlyMine] = useState(false);
	const [showProcessProgress, setShowProcessProgress] = useState(false);
  const [assignmentTask, setAssignmentTask] = useState<Task | null>(null);
	const [dispatchStep, setDispatchStep] = useState<1 | 2 | 3>(1);
	const [dispatchDraft, setDispatchDraft] = useState<DispatchDraft | null>(null);
	const [assignmentReportingMode, setAssignmentReportingMode] = useState<TaskReportingMode | null>(null);
  const [workerRecommendation, setWorkerRecommendation] = useState<DispatchRecommendation | null>(null);
	const [reportTask, setReportTask] = useState<Task | null>(null);
	const [postTreatmentTask, setPostTreatmentTask] = useState<Task | null>(null);
	const [postTreatmentCodes, setPostTreatmentCodes] = useState<string[]>([]);
  const [pending, setPending] = useState(false);
  const [actionId, setActionId] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(
    async () => {
		const [tasks, orders, moldSelections, assets, postTreatmentDecisions, suppliers] = await Promise.all([
		api.tasks.list(status || undefined, onlyMine ? operatorCode : undefined, undefined, operatorCode),
		api.orders.list(),
		api.molds.orderSelections(), api.resources.list(), api.postTreatment.decisions(operatorCode), api.outsourcing.suppliers()
	]);
		return { tasks, orders, moldSelections, assets, postTreatmentDecisions, suppliers };
    },
    [status, onlyMine, operatorCode]
  );
  const recommendations = useAsyncData(() => api.planning.dispatchRecommendations(undefined, operatorCode), [operatorCode]);
	const moldRequests = useAsyncData(api.molds.list);
	const shellTaskIds = (state.data?.tasks ?? []).filter((task) => task.operationCode === "SHELL_BUILDING" || task.operationCode === "MANUAL_SHELL_BUILDING").map((task) => task.id).join(",");
	const shellProgress = useAsyncData(async () => {
		if (!showProcessProgress || !shellTaskIds) return [] as ShellProgressSummary[];
		return api.labor.shellSummaries(shellTaskIds.split(","));
	}, [showProcessProgress, shellTaskIds]);

  async function openAssignment(task: Task) {
    setAssignmentTask(task);
		setDispatchStep(1);
		setDispatchDraft(null);
		setAssignmentReportingMode(allowedModes(task)[0].value);
    setWorkerRecommendation(null);
    try {
		setWorkerRecommendation(await api.planning.dispatchRecommendations(task.operationCode, operatorCode, task.routeType));
    } catch (caught) {
      setError(caught);
    }
  }

	function openOrderMoldSelection() {
		if (!assignmentTask) return;
		const params = new URLSearchParams({
			orderId: assignmentTask.orderId,
			orderLineId: assignmentTask.orderLineId,
			resumeTaskId: assignmentTask.id
		});
		if (dispatchDraft?.productionQuantity != null) params.set("productionQuantity", String(dispatchDraft.productionQuantity));
		navigate(`/order-molds?${params}`);
		setAssignmentTask(null);
	}

	useEffect(() => {
		const resumeTaskId = searchParams.get("resumeTaskId");
		if (!resumeTaskId || !state.data || assignmentTask) return;
		const task = state.data.tasks.find((item) => item.id === resumeTaskId);
		setSearchParams({}, { replace: true });
		if (!task || task.status !== "READY") {
			setError(new Error("该派工任务已不是待派工状态，请从任务列表重新选择"));
			return;
		}
		const reportingMode = allowedModes(task)[0].value;
		const resumedQuantity = Number(searchParams.get("productionQuantity"));
		setAssignmentTask(task);
		setDispatchStep(2);
		setDispatchDraft({
			workerCode: "",
			reportingMode,
			productionQuantity: task.operationCode === "WAX_INJECTION" && Number.isFinite(resumedQuantity) && resumedQuantity > 0
				? resumedQuantity : undefined,
			shellLineMode: task.shellLineMode ?? undefined
		});
		setAssignmentReportingMode(reportingMode);
		void api.planning.dispatchRecommendations(task.operationCode, operatorCode, task.routeType)
			.then(setWorkerRecommendation)
			.catch(setError);
	}, [assignmentTask, operatorCode, searchParams, setSearchParams, state.data]);

  async function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!assignmentTask) return;
		const form = new FormData(event.currentTarget);
		if (dispatchStep === 1) {
			const productionQuantity = assignmentTask.operationCode === "WAX_INJECTION"
				? Number(form.get("productionQuantity")) : undefined;
			const shellLineMode = assignmentTask.operationCode === "SHELL_BUILDING"
				? String(form.get("shellLineMode") || "") as "AUTOMATED" | "MANUAL" : assignmentTask.shellLineMode ?? undefined;
			if (assignmentTask.operationCode === "SHELL_BUILDING" && !shellLineMode) {
				setError(new Error("请在派工前选择自动化制壳线或手动制壳线"));
				return;
			}
			if (productionQuantity != null && (!Number.isInteger(productionQuantity) || productionQuantity < assignmentTask.plannedQuantity)) {
				setError(new Error("实际投产数量必须是不得小于本批计划数的整数"));
				return;
			}
			setDispatchDraft({
				workerCode: "", reportingMode: allowedModes(assignmentTask)[0].value, productionQuantity, shellLineMode
			});
			setDispatchStep(2); return;
		}
		if (dispatchStep === 2) {
			const existingMoldRequest = (moldRequests.data ?? []).find((item) => item.waxTaskId === assignmentTask.id);
			const selectedMold = (state.data?.moldSelections ?? []).find((selection) =>
				selection.orderLineId === assignmentTask.orderLineId && selection.selectionStatus === "SELECTED"
			);
			const selectedMoldAsset = selectedMold?.moldAssetId
				? (state.data?.assets ?? []).find((asset) => asset.id === selectedMold.moldAssetId)
				: undefined;
			if (assignmentTask.operationCode === "WAX_INJECTION" && !existingMoldRequest?.moldAssetId
				&& selectedMoldAsset && (selectedMoldAsset.status !== "AVAILABLE" || selectedMoldAsset.moldCustodyStatus !== "IN_STOCK")) {
				setError(new Error(`模具 ${selectedMoldAsset.assetCode} 当前${selectedMoldAsset.moldCustodyStatus === "INTERNAL_IN_USE" ? "正在内部使用" : "不在库"}，请重新选择可用模具后再派工`));
				return;
			}
			if (assignmentTask.operationCode === "WAX_INJECTION" && !existingMoldRequest?.moldAssetId && !selectedMold?.moldAssetId) {
				openOrderMoldSelection();
				return;
			}
			const reportingMode = String(form.get("reportingMode")) as TaskReportingMode;
			const fixedQuantity = reportingMode === "FIXED_QUANTITY" ? Number(form.get("fixedQuantity")) : undefined;
			if (fixedQuantity != null && fixedQuantity < assignmentTask.plannedQuantity) {
				navigate(`/work-orders?split=${assignmentTask.workOrderId}&route=${assignmentTask.routeType}`);
				setAssignmentTask(null);
				return;
			}
			setDispatchDraft({
				workerCode: String(form.get("workerCode")), reportingMode,
				fixedQuantity,
				productionQuantity: dispatchDraft?.productionQuantity,
				shellLineMode: dispatchDraft?.shellLineMode,
			});
			setDispatchStep(3);
			return;
		}
		if (!dispatchDraft) return;
    setPending(true);
    setError(null);
    try {
		const { workerCode, reportingMode } = dispatchDraft;
			if (dispatchDraft.shellLineMode && dispatchDraft.shellLineMode !== assignmentTask.shellLineMode) {
				await api.tasks.configureShellLine(assignmentTask.id, dispatchDraft.shellLineMode, operatorCode);
			}
			if (assignmentTask.operationCode === "WAX_INJECTION") {
				const existing = (moldRequests.data ?? []).find((request) => request.waxTaskId === assignmentTask.id);
				if (existing?.status !== "ISSUED") {
						const selectedMold = (state.data?.moldSelections ?? []).find((selection) =>
							selection.orderLineId === assignmentTask.orderLineId && selection.selectionStatus === "SELECTED"
						);
						const selectedMoldAsset = selectedMold?.moldAssetId
							? (state.data?.assets ?? []).find((asset) => asset.id === selectedMold.moldAssetId)
							: undefined;
						if (selectedMoldAsset && (selectedMoldAsset.status !== "AVAILABLE" || selectedMoldAsset.moldCustodyStatus !== "IN_STOCK")) {
							throw new Error(`模具 ${selectedMoldAsset.assetCode} 已被其他批次占用或不在库，请重新选择可用模具`);
						}
						const moldAssetId = existing?.moldAssetId ?? selectedMold?.moldAssetId;
				if (!moldAssetId) throw new Error("请先选择该订单产品的模具");
				await api.tasks.dispatchWaxWithMold(assignmentTask.id, {
					moldAssetId,
					warehouseCode: "MOLD-01",
					warehouseOperatorCode: operatorCode,
					workerCode,
					reportingMode,
						fixedQuantity: dispatchDraft.fixedQuantity,
						supervisorCode: operatorCode
				});
				await moldRequests.reload();
				await state.reload();
				navigate(`/documents?documentType=WORKSHOP_JOB_SHEET&taskId=${assignmentTask.id}&autoPrint=1`);
				return;
			}
		}
      await api.tasks.assign(assignmentTask.id, {
		workerCode,
        reportingMode,
		fixedQuantity: dispatchDraft.fixedQuantity,
		supervisorCode: operatorCode
      });
      await state.reload();
		navigate(`/documents?documentType=WORKSHOP_JOB_SHEET&taskId=${assignmentTask.id}&autoPrint=1`);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  async function start(task: Task) {
    setActionId(task.id);
    setError(null);
    try {
      await api.tasks.start(task.id, operatorCode);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setActionId(null);
    }
  }

  async function returnWaxMold(task: Task) {
    setActionId(task.id);
    setError(null);
    try {
      await api.tasks.returnWaxMold(task.id, operatorCode);
      await Promise.all([state.reload(), moldRequests.reload()]);
    } catch (caught) {
      setError(caught);
    } finally {
      setActionId(null);
    }
  }

	async function report(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!reportTask) return;
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError(null);
	try {
		const photo = form.get("photo");
		const photoUrl = photo instanceof File && photo.size > 0 ? (await api.files.upload("EXECUTION_PHOTO", photo)).url : undefined;
		const input = {
		  operationId: crypto.randomUUID(),
		  goodQuantity: Number(form.get("goodQuantity")),
		  scrapQuantity: Number(form.get("scrapQuantity")),
		  photoUrl
		};
		if (reportTask.assignedTo === operatorCode) await api.tasks.report(reportTask.id, operatorCode, input);
		else await api.tasks.supervisorReport(reportTask.id, { ...input, supervisorCode: operatorCode });
      setReportTask(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
	    } finally {
	      setPending(false);
		}
	}

	async function decidePostTreatment(event: FormEvent<HTMLFormElement>) {
		event.preventDefault(); if (!postTreatmentTask) return;
		const form = new FormData(event.currentTarget); const destination = String(form.get("destination")) as "IN_HOUSE" | "OUTSOURCE" | "DIRECT_FINISHED" | "FINISHED_GOODS_STORAGE";
		const processCodes = postTreatmentCodes;
		setPending(true); setError(null);
		try {
			await api.postTreatment.decide({ sourceTaskId: postTreatmentTask.id, destination, processCodes, processSummary: String(form.get("processSummary") || "") || undefined, supplierId: destination === "OUTSOURCE" ? String(form.get("supplierId") || "") || undefined : undefined, warehouseCode: destination === "DIRECT_FINISHED" || destination === "FINISHED_GOODS_STORAGE" ? String(form.get("warehouseCode") || "FG-01") : undefined, note: String(form.get("note") || "") || undefined, decidedBy: operatorCode });
			setPostTreatmentTask(null); await state.reload();
		} catch (caught) { setError(caught); } finally { setPending(false); }
		}

  if (state.loading) return <LoadingState label="正在加载生产任务" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const tasks = state.data?.tasks ?? [];
	const blockedCount = tasks.filter((task) => task.status === "BLOCKED").length;
	const visibleTasks = showProcessProgress || status === "BLOCKED" ? tasks : tasks.filter((task) => task.status !== "BLOCKED");
  const ordersById = new Map((state.data?.orders ?? []).map((order) => [order.id, order]));
	const selectedMoldForTask = assignmentTask ? (state.data?.moldSelections ?? []).find((selection) =>
    selection.orderLineId === assignmentTask.orderLineId && selection.selectionStatus === "SELECTED"
	) : null;
	const selectedMoldAsset = selectedMoldForTask?.moldAssetId
		? (state.data?.assets ?? []).find((asset) => asset.id === selectedMoldForTask.moldAssetId)
		: undefined;
	const selectedMoldUnavailable = Boolean(selectedMoldAsset && (selectedMoldAsset.status !== "AVAILABLE" || selectedMoldAsset.moldCustodyStatus !== "IN_STOCK"));
	const postTreatmentTaskIds = new Set((state.data?.postTreatmentDecisions ?? []).map((decision) => decision.sourceTaskId));
	const shellProgressByTask = new Map<string, ShellProgressSummary>();
	(shellProgress.data ?? []).forEach((summary) => shellProgressByTask.set(summary.taskId, summary));
  return (
    <>
      <PageHeader
        title="任务派发与执行"
        description="主管派工，员工按任务方式完成报工；组树为最终件数确认点。"
        action={<button className="icon-button" type="button" onClick={() => { void state.reload(); void shellProgress.reload(); }} aria-label="刷新任务"><RefreshCw aria-hidden="true" /></button>}
      />
      {error && <ErrorNotice error={error} />}
      {(recommendations.data?.tasks.length ?? 0) > 0 && (
        <section className="section-block dispatch-recommendations" aria-label="派工推荐">
          <div className="section-heading"><div><h2>优先派工建议</h2><p>按紧急度、样品优先级和交期排序；只显示前序已完成的可开工任务。</p></div></div>
          <div className="recommendation-list">
            {recommendations.data?.tasks.slice(0, 5).map((item) => {
              const task = tasks.find((candidate) => candidate.id === item.taskId);
              return <button key={item.taskId} type="button" className="recommendation-item" disabled={!task} onClick={() => task && void openAssignment(task)}>
                <span><strong>{item.orderNo} · {item.productName}</strong><small>{item.operationName} / {item.taskNo} / {formatQuantity(item.plannedQuantity)}</small></span>
				<StatusBadge value={item.routeType} />
                <StatusBadge value={item.priority} />
              </button>;
            })}
          </div>
        </section>
      )}
      <section className="filter-bar" aria-label="任务筛选">
        <div className="segmented-control" role="group" aria-label="任务状态">
          {filters.map((filter) => <button key={filter.value || "ALL"} type="button" className={status === filter.value ? "active" : ""} aria-pressed={status === filter.value} onClick={() => setStatus(filter.value)}>{filter.label}</button>)}
        </div>
		<label className="toggle-control"><input type="checkbox" checked={onlyMine} onChange={(event) => setOnlyMine(event.target.checked)} /><span aria-hidden="true" />只看我的任务</label>
		<button type="button" className="button button-secondary button-small" onClick={() => setShowProcessProgress((current) => !current)}>{showProcessProgress ? "收起流程进度" : `查看流程进度 (${blockedCount})`}</button>
	  </section>
	  <section className="section-block">
		{visibleTasks.length === 0 ? <EmptyState title="当前没有可动作任务" description={blockedCount > 0 ? `有 ${blockedCount} 道后续工序正在等待前序完成，可通过“查看流程进度”了解详情。` : "调整筛选条件或先创建订单。"} /> : (
          <div className="table-scroll"><table>
            <thead><tr><th>序号</th><th>任务/工单</th><th>工序</th><th>数量</th><th>报工方式</th><th>执行人</th><th>状态</th><th className="actions-cell">操作</th></tr></thead>
			<tbody>{visibleTasks.map((task) => {
              const reported = task.goodQuantity + task.scrapQuantity;
              const remaining = task.plannedQuantity - reported;
				const order = ordersById.get(task.orderId);
              return <tr key={task.id}>
                <td>{task.sequenceNo}</td>
				<td><strong>{task.taskNo}</strong><StatusBadge value={task.routeType} /><small className="task-order-label">订单 {order?.orderNo ?? "订单同步中"} · 客户 {order?.customerName ?? "客户同步中"}</small><small className="task-product-label">产品 {task.productCode} · {task.productName}</small><small>{task.workOrderNo} / {task.batchNo}</small>{order?.remark && <small className="task-order-remark">订单备注：{order.remark}</small>}</td>
				<td className="primary-cell">{task.operationName}{isShellTask(task) && <small className="task-shell-line">{shellLineLabel(task.shellLineMode)}</small>}{showProcessProgress && isShellTask(task) && (() => { const summary = shellProgressByTask.get(task.id); return <details className="shell-progress-detail"><summary>制壳进度 {summary?.recordCount ?? 0} 条</summary>{summary ? <small>最新第 {summary.latestLayer} 层，{summary.flowedToNext ? "已申请流转下工序" : "等待下一层制壳"}；最后记录 {new Date(summary.latestOccurredAt).toLocaleString("zh-CN", { hour12: false })}</small> : <small>暂无层次记录</small>}</details>; })()}</td>
                <td><span className="quantity-cell">{formatQuantity(reported)} / {formatQuantity(task.plannedQuantity)}</span><small>余量 {formatQuantity(remaining)}</small></td>
                <td><strong>{modeLabel(task.reportingMode)}</strong><small>{task.settlementUnit === "TREE" ? "按树结算" : "按件结算"}{task.countingDeferred ? "；数量待组树确认" : ""}</small></td>
                <td>{task.assignedTo || "未派工"}</td>
                <td><StatusBadge value={task.status} /></td>
                <td className="actions-cell">
				  {task.operationCode === "WAX_INJECTION" && task.status === "COMPLETED" && (moldRequests.data ?? []).some((request) => request.waxTaskId === task.id && request.status === "ISSUED") && <button type="button" className="button button-secondary button-small" disabled={actionId === task.id} onClick={() => void returnWaxMold(task)}><RotateCcw aria-hidden="true" />归还模具</button>}
                  {task.status === "READY" && <button className="button button-secondary button-small" disabled={actionId === task.id} onClick={() => void openAssignment(task)}><UserPlus aria-hidden="true" />派发</button>}
                  {task.status === "BLOCKED" && <span className="muted">等待上一工序完成后自动解锁</span>}
                  {task.assignedTo && <Link className="button button-secondary button-small" to={`/documents?documentType=WORKSHOP_JOB_SHEET&taskId=${task.id}&autoPrint=1`}><Printer aria-hidden="true" />{task.status === "ASSIGNED" ? "派工单" : "补打派工单"}</Link>}
				  {task.assignedTo && <Link className="button button-secondary button-small" to={`/documents?documentType=FLOW_CARD&taskId=${task.id}&autoPrint=1`}><Printer aria-hidden="true" />补打流转卡</Link>}
                  {task.status === "ASSIGNED" && task.assignedTo === operatorCode && <button className="button button-primary button-small" disabled={actionId === task.id} onClick={() => start(task)}><Play aria-hidden="true" />开工</button>}
				  {task.status === "IN_PROGRESS" && (task.reportingMode === "SELF_REPORTED_QUANTITY" || task.reportingMode === "FIXED_QUANTITY") && <button className="button button-primary button-small" onClick={() => setReportTask(task)}><Send aria-hidden="true" />{task.assignedTo === operatorCode ? "报工" : "主管代报"}</button>}
			  {task.operationCode === "SEMI_FINISHED_COUNT" && task.status !== "BLOCKED" && !postTreatmentTaskIds.has(task.id) && <button className="button button-secondary button-small" onClick={() => { setPostTreatmentCodes([]); setPostTreatmentTask(task); }}><Factory aria-hidden="true" />后处理去向</button>}
                  {task.status === "IN_PROGRESS" && task.reportingMode === "HANDOFF_TO_TREE" && <span className="muted">由员工工作台免计数交接</span>}
                  {task.status === "IN_PROGRESS" && task.reportingMode === "TREE_COUNT" && <span className="muted">由组树工人报树数</span>}
                </td>
              </tr>;
            })}</tbody>
          </table></div>
        )}
      </section>
	  {assignmentTask && <Modal title={`派发 ${assignmentTask.operationName}`} description={`任务 ${assignmentTask.taskNo} · 订单 ${ordersById.get(assignmentTask.orderId)?.orderNo ?? "订单同步中"} · 客户 ${ordersById.get(assignmentTask.orderId)?.customerName ?? "客户同步中"} · 产品 ${assignmentTask.productCode} ${assignmentTask.productName} · ${assignmentTask.batchNo}；员工按工序能力和当前在制任务数排序。`} width="small" onClose={() => !pending && setAssignmentTask(null)}>
		<form onSubmit={assign}>
		  <div className="dispatch-stepper" aria-label="派工步骤"><span className={dispatchStep === 1 ? "active" : ""}>1 任务确认</span><span className={dispatchStep === 2 ? "active" : ""}>2 选人和资源</span><span className={dispatchStep === 3 ? "active" : ""}>3 核对打印</span></div>
		  {dispatchStep === 1 && <div className="dispatch-task-summary"><strong>{assignmentTask.taskNo} · {assignmentTask.operationName}</strong><span>{assignmentTask.workOrderNo} / {assignmentTask.batchNo} / {formatQuantity(assignmentTask.plannedQuantity)} PCS</span>{assignmentTask.operationCode === "WAX_INJECTION" && <Field label="本批实际投产数量" required hint="默认按 3% 冗余向上取整；主管可按现场计划修改，必须为整数。"><input name="productionQuantity" type="number" min={assignmentTask.plannedQuantity} step="1" required autoFocus defaultValue={Math.ceil(assignmentTask.plannedQuantity * 1.03)} /><small className="form-hint">填写 {formatQuantity(assignmentTask.plannedQuantity)} 即一比一投产，系统将提示本批没有冗余。</small></Field>}{assignmentTask.operationCode === "SHELL_BUILDING" && <Field label="制壳生产线" required hint="本批开工前确定。选择手动线后，操作工逐层扫码报备并受干燥停留预警管理。"><select name="shellLineMode" defaultValue={assignmentTask.shellLineMode ?? ""} required><option value="">请选择制壳线</option><option value="AUTOMATED">自动化制壳线</option><option value="MANUAL">手动制壳线</option></select></Field>}{assignmentTask.operationCode === "MANUAL_SHELL_BUILDING" && <Field label="制壳生产线"><input value="手动制壳线" readOnly /></Field>}<small>确认任务后，系统会按工序能力和在制数量推荐员工；射蜡会同时处理模具领用。</small></div>}
		  {dispatchStep === 2 && <div className="form-grid">
          <Field label="执行员工" required><select name="workerCode" required autoFocus disabled={!workerRecommendation}><option value="">{workerRecommendation ? "请选择员工" : "正在加载可派员工"}</option>{workerRecommendation?.workers.map((worker) => <option key={worker.employeeCode} value={worker.employeeCode}>{worker.recommended ? "推荐 · " : ""}{worker.employeeCode} · {worker.name} · 在制 {worker.activeTaskCount}</option>)}</select></Field>
		  {assignmentTask.operationCode === "WAX_INJECTION" && (() => {
				const request = (moldRequests.data ?? []).find((item) => item.waxTaskId === assignmentTask.id);
				if (request) return <Field label="订单专属模具" required><p>{request.requestNo} · {request.status === "ISSUED" ? `已出库，交接给 ${request.issuedToWorkerCode ?? "射蜡工"}` : "已锁定订单选定模具；确认派发时自动出库并交接给所选射蜡工"}</p></Field>;
				if (selectedMoldForTask?.moldAssetId && selectedMoldUnavailable) return <Field label="订单专属模具" required><p className="form-hint"><strong>{selectedMoldForTask.moldAssetCode} 当前不可领用</strong><small>{selectedMoldAsset?.moldCustodyStatus === "INTERNAL_IN_USE" ? "该模具正在内部生产使用中" : "该模具当前不在库"}；请选择另一副在库可用模具。</small></p><button className="button button-secondary button-small" type="button" onClick={openOrderMoldSelection}>重新选择模具</button></Field>;
				if (selectedMoldForTask?.moldAssetId) return <Field label="订单专属模具" required><p><strong>{selectedMoldForTask.moldAssetCode}</strong> · {selectedMoldForTask.moldAssetName}<small>{selectedMoldForTask.moldOwnershipType === "CUSTOMER_OWNED" ? "客户寄存模具" : "企业自有模具"}；确认派发时自动出库，无需再次选择。</small></p></Field>;
				return <Field label="订单专属模具" required><p className="form-hint">该订单产品尚未绑定在库模具。绑定后会自动回到当前任务继续派工。</p><button className="button button-secondary button-small" type="button" onClick={openOrderMoldSelection}>去绑定模具</button></Field>;
			})()}
		  <Field label="报工方式" required><select name="reportingMode" value={assignmentReportingMode ?? allowedModes(assignmentTask)[0].value} onChange={(event) => setAssignmentReportingMode(event.target.value as TaskReportingMode)}>{allowedModes(assignmentTask).map((mode) => <option key={mode.value} value={mode.value}>{mode.label}</option>)}</select></Field>
		  {assignmentReportingMode === "FIXED_QUANTITY" && <Field label="固定派工数量" required hint="少于本任务数量时将进入拆批派工，不能在原任务里直接修改。"><input name="fixedQuantity" type="number" min="0.001" step="0.001" defaultValue={assignmentTask.plannedQuantity} required /></Field>}
		  <Field label="本工序结算规则"><p className="form-hint">{compensationRuleLabel(assignmentTask.compensationMode)}。由工艺与月度工价表带出，派工时不可修改。</p></Field>
		  </div>}
		  {dispatchStep === 3 && dispatchDraft && <div className="dispatch-task-summary"><strong>请核对后提交</strong><span>员工：{dispatchDraft.workerCode}；报工方式：{modeLabel(dispatchDraft.reportingMode)}；结算规则：{compensationRuleLabel(assignmentTask.compensationMode)}</span>{assignmentTask.operationCode === "WAX_INJECTION" && <small>{dispatchDraft.productionQuantity === assignmentTask.plannedQuantity ? "提醒：当前为一比一投产，本批没有生产冗余。" : `本批实际投产 ${formatQuantity(dispatchDraft.productionQuantity ?? assignmentTask.plannedQuantity)} PCS；确认后系统完成模具出库交接并先生成全流程流转卡。`}</small>}<Link className="text-link" to={`/documents?documentType=WORKSHOP_JOB_SHEET&taskId=${assignmentTask.id}`}>派工后可补打派工单</Link></div>}
		  <SubmitActions pending={pending} submitLabel={dispatchStep === 1 ? "下一步" : dispatchStep === 2 ? "核对派工" : assignmentTask.operationCode === "WAX_INJECTION" ? "确认出库并派工" : "确认派发并打印"} onCancel={() => dispatchStep === 1 ? setAssignmentTask(null) : setDispatchStep((current) => current === 3 ? 2 : 1)} />
		</form>
      </Modal>}
	  {reportTask && <Modal title={`报工 / ${reportTask.operationName}`} description={`本次最大可报 ${formatQuantity(reportTask.plannedQuantity - reportTask.goodQuantity - reportTask.scrapQuantity)}`} width="small" onClose={() => !pending && setReportTask(null)}>
		<form onSubmit={report}><div className="form-grid">
          <Field label="本次合格数" required><input name="goodQuantity" type="number" min="0" step="0.001" defaultValue="0" required autoFocus /></Field>
          <Field label="本次报废数" required><input name="scrapQuantity" type="number" min="0" step="0.001" defaultValue="0" required /></Field>
		  <Field label="现场照片（可选）"><input name="photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" /></Field>
        </div><SubmitActions pending={pending} submitLabel="确认报工" onCancel={() => setReportTask(null)} /></form>
	  </Modal>}
	  {postTreatmentTask && <Modal title="确定后处理去向" description="选择后处理工艺后，系统会自动锁定该批后处理任务的结算规则；主管不需要在派工时再修改。" width="small" onClose={() => !pending && setPostTreatmentTask(null)}><form onSubmit={decidePostTreatment}><div className="form-grid"><Field label="处理去向" required><select name="destination" defaultValue="IN_HOUSE"><option value="IN_HOUSE">本厂后处理</option><option value="OUTSOURCE">外送后处理</option><option value="DIRECT_FINISHED">无需后处理，直入成品清点</option></select></Field><Field label="后处理工艺" required><div className="checkbox-stack">{postTreatmentProcessOptions.map((item) => <label key={item.code}><input type="checkbox" checked={postTreatmentCodes.includes(item.code)} onChange={() => setPostTreatmentCodes((current) => current.includes(item.code) ? current.filter((code) => code !== item.code) : [...current, item.code])} />{item.label} <small>{item.rule === "PIECE_KG" ? "按公斤" : "按工时"}</small></label>)}</div></Field><Field label="自动结算规则"><p className="form-hint">{postTreatmentRule(postTreatmentCodes)}。选择混合工艺时为避免重复计件，统一按工时。</p></Field><Field label="补充说明"><input name="processSummary" maxLength={1000} placeholder="仅填写未列出的特殊工艺或要求" /></Field><Field label="外送供应商"><select name="supplierId" defaultValue=""><option value="">外送时选择，其他去向可留空</option>{state.data?.suppliers.map((supplier) => <option key={supplier.id} value={supplier.id}>{supplier.code} · {supplier.name}</option>)}</select></Field><Field label="决策备注"><textarea name="note" rows={3} maxLength={500} /></Field></div><SubmitActions pending={pending} submitLabel="确认后处理去向" onCancel={() => setPostTreatmentTask(null)} /></form></Modal>}
    </>
  );
}
