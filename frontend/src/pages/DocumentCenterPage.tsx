import { useEffect, useMemo, useRef, useState } from "react";
import { Download, FileText, Printer, RefreshCw, ShieldAlert } from "lucide-react";
import QRCode from "qrcode";
import { useSearchParams } from "react-router-dom";
import { api } from "../api";
import { EmptyState, ErrorNotice, LoadingState, PageHeader } from "../components/ui";
import { useAsyncData } from "../hooks";
import { roleWorkspaceFor } from "./roleWorkspace";
import type { AccessUser, DocumentOutput, DocumentPreview, DocumentType, Task, TaskStatus } from "../types";

const fieldCatalog: Record<DocumentType, Array<{ code: string; label: string; sensitive?: boolean }>> = {
  PROCESS_CARD: [
    { code: "TASK_NO", label: "任务号" }, { code: "WORK_ORDER_NO", label: "工单号" },
    { code: "PRODUCT", label: "产品" }, { code: "OPERATION", label: "工序" },
    { code: "BATCH_NO", label: "批次号" }, { code: "PLANNED_QUANTITY", label: "计划数量" },
    { code: "PROCESS_REQUIREMENTS", label: "本工序关键确认项" }, { code: "PREPARATION", label: "工艺准备" },
		{ code: "PROCESS_IMAGE_URL", label: "本工序工艺图" },
    { code: "SOP", label: "SOP 步骤" }, { code: "SAFETY", label: "安全提示" },
    { code: "QUALITY_POINTS", label: "质量要点" }
  ],
  WORKSHOP_JOB_SHEET: [
    { code: "TASK_NO", label: "任务号" }, { code: "WORK_ORDER_NO", label: "工单号" },
    { code: "PRODUCT", label: "产品" }, { code: "OPERATION", label: "工序" },
    { code: "BATCH_NO", label: "批次号" }, { code: "PLANNED_QUANTITY", label: "计划数量" },
		{ code: "PROCESS_CARD_VERSION", label: "工艺卡版本" }, { code: "ENGINEERING_PARAMETERS", label: "工程参数与工艺要求" },
		{ code: "ASSIGNED_TO", label: "派发给" }, { code: "TASK_QR_PAYLOAD", label: "任务二维码" }, { code: "QUANTITY_STATUS", label: "当前完成" }
  ],
  FLOW_CARD: [
    { code: "TASK_NO", label: "任务号" }, { code: "WORK_ORDER_NO", label: "工单号" },
    { code: "ORDER_NO", label: "订单号", sensitive: true }, { code: "CUSTOMER_NAME", label: "客户名称", sensitive: true },
    { code: "PRODUCT", label: "产品" }, { code: "SPECIFICATION", label: "产品规格" }, { code: "MATERIAL", label: "材质" },
		{ code: "BATCH_NO", label: "生产批次" }, { code: "PLANNED_QUANTITY", label: "计划数量" },
		{ code: "PROCESS_CARD_VERSION", label: "工艺卡版本" }, { code: "FLOW_STEPS", label: "流转记录" },
		{ code: "FLOW_RECORDS", label: "流转记录表" },
		{ code: "FLOW_QR_PAYLOAD", label: "流转二维码" }
  ],
  ORDER_DETAIL: [
    { code: "ORDER_NO", label: "订单号", sensitive: true }, { code: "CUSTOMER_NAME", label: "客户名称", sensitive: true },
    { code: "CUSTOMER_CODE", label: "客户编码", sensitive: true }, { code: "ORDER_STATUS", label: "订单状态", sensitive: true },
    { code: "PRIORITY", label: "优先级", sensitive: true }, { code: "DELIVERY_DATE", label: "要求交期", sensitive: true },
    { code: "LINES", label: "产品明细", sensitive: true }, { code: "REMARK", label: "订单备注", sensitive: true }
  ],
  STATISTICS: [
    { code: "GENERATED_AT", label: "生成时间" }, { code: "ORDER_COUNT", label: "订单总数" },
    { code: "ACTIVE_TASKS", label: "进行中任务" }, { code: "GOOD_QUANTITY", label: "累计合格数量" },
    { code: "SCRAP_QUANTITY", label: "累计报废数量" }
  ]
};

const requiresEntity = (type: DocumentType) => type !== "STATISTICS";

export function documentFieldsFor(type: DocumentType) {
  return fieldCatalog[type].map((field) => field.code);
}

export function validDocumentFields(type: DocumentType, selectedFields: string[]) {
  const allowed = new Set(documentFieldsFor(type));
  return selectedFields.filter((code) => allowed.has(code));
}

export function taskDocumentStatusLabel(status: TaskStatus) {
  return status === "COMPLETED" ? "已完成 · 仅补打" : "当前任务";
}

function splitPrintableTasks(tasks: Task[]) {
  return {
    current: tasks.filter((task) => task.status !== "COMPLETED"),
    completed: tasks.filter((task) => task.status === "COMPLETED")
  };
}

export function DocumentCenterPage({ user }: { user: AccessUser }) {
	const [searchParams] = useSearchParams();
	const autoPreviewKey = useRef<string | null>(null);
  const workspace = roleWorkspaceFor(user);
  const isOperator = workspace.kind === "operator";
  const isSupervisor = workspace.kind === "supervisor";
  const state = useAsyncData(async () => {
    const canViewAudits = user.permissions.includes("PRINT_AUDIT_VIEW");
    const [options, orders, tasks, audits] = await Promise.all([
      api.documents.options(user.employeeCode),
      user.permissions.includes("PRINT_SENSITIVE_ORDER") ? api.orders.list() : Promise.resolve([]),
      api.tasks.list(undefined, isOperator ? user.employeeCode : undefined, undefined, isSupervisor ? user.employeeCode : undefined),
      canViewAudits ? api.documents.audits(user.employeeCode) : Promise.resolve([])
    ]);
    return { options, orders, tasks, audits };
  }, [user.employeeCode, user.permissions, isOperator, isSupervisor]);
  const [documentType, setDocumentType] = useState<DocumentType | null>(() => searchParams.get("documentType") as DocumentType | null);
  const [entityId, setEntityId] = useState(() => searchParams.get("taskId") ?? "");
	const [operationFilter, setOperationFilter] = useState("");
  const [selectedFields, setSelectedFields] = useState<string[]>([]);
  const [preview, setPreview] = useState<DocumentPreview | null>(null);
  const [pending, setPending] = useState<DocumentOutput | null>(null);
  const [error, setError] = useState<unknown>(null);

  const options = state.data?.options ?? [];
  const activeType = documentType ?? options[0]?.documentType ?? null;
  const fields = activeType ? fieldCatalog[activeType] : [];
  const orders = state.data?.orders ?? [];
  const tasks = state.data?.tasks ?? [];
	const ordersById = new Map(orders.map((order) => [order.id, order]));
	const filteredTasks = operationFilter ? tasks.filter((task) => task.operationCode === operationFilter) : tasks;
	const printableTasks = splitPrintableTasks(filteredTasks);
	const flowCardTasks = [...tasks.reduce((byBatch, task) => {
		const current = byBatch.get(task.batchId);
		if (!current || task.sequenceNo < current.sequenceNo) byBatch.set(task.batchId, task);
		return byBatch;
	}, new Map<string, typeof tasks[number]>()).values()];
	const operations = [...new Map(tasks.map((task) => [task.operationCode, task.operationName])).entries()];
  const audits = state.data?.audits ?? [];
  const canViewAudits = user.permissions.includes("PRINT_AUDIT_VIEW");
  const validSelectedFields = activeType ? validDocumentFields(activeType, selectedFields) : [];

  useEffect(() => {
    if (!activeType) return;
    setDocumentType(activeType);
    setSelectedFields(documentFieldsFor(activeType));
    setPreview(null);
  }, [activeType]);

	useEffect(() => {
		const requestedType = searchParams.get("documentType") as DocumentType | null;
		const requestedTask = searchParams.get("taskId");
		if (requestedType && fieldCatalog[requestedType]) {
			setDocumentType(requestedType);
			setSelectedFields(documentFieldsFor(requestedType));
			setPreview(null);
			setError(null);
		}
		if (requestedTask) setEntityId(requestedTask);
	}, [searchParams]);

  const sensitiveSelected = useMemo(
    () => fields.some((field) => field.sensitive && validSelectedFields.includes(field.code)),
    [fields, validSelectedFields]
  );

  function toggleField(code: string) {
    setSelectedFields((current) =>
      current.includes(code) ? current.filter((item) => item !== code) : [...current, code]
    );
  }

  function changeDocumentType(type: DocumentType) {
    setDocumentType(type);
    setSelectedFields(documentFieldsFor(type));
    setPreview(null);
    setError(null);
  }

  async function generate(outputType: DocumentOutput) {
    if (!activeType || (requiresEntity(activeType) && !entityId)) return;
    const fieldsToPrint = validDocumentFields(activeType, selectedFields);
    if (fieldsToPrint.length === 0) return;
    if (fieldsToPrint.length !== selectedFields.length) setSelectedFields(fieldsToPrint);
    setPending(outputType);
    setError(null);
    try {
      const nextPreview = await api.documents.preview({
        documentType: activeType,
        entityId: requiresEntity(activeType) ? entityId : undefined,
        actorCode: user.employeeCode,
        selectedFields: fieldsToPrint,
        outputType
      });
      setPreview(nextPreview);
      if (outputType === "PRINT") window.setTimeout(() => window.print(), 50);
      if (outputType === "EXPORT") exportCsv(nextPreview);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(null);
    }
  }

  useEffect(() => {
    const taskId = searchParams.get("taskId");
    if (searchParams.get("autoPrint") !== "1" || state.loading || (activeType !== "WORKSHOP_JOB_SHEET" && activeType !== "FLOW_CARD") || !taskId || entityId !== taskId || selectedFields.length === 0) return;
    const key = `${activeType}:${taskId}`;
    if (autoPreviewKey.current === key) return;
    autoPreviewKey.current = key;
    void generate("PREVIEW");
  }, [activeType, entityId, searchParams, selectedFields, state.loading]);

  if (state.loading) return <LoadingState label="正在加载可打印文档" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  if (!activeType) return <EmptyState title="暂无可用文档权限" description="请联系管理员为当前岗位配置工艺卡或受控打印权限。" />;

  return (
    <>
      <PageHeader
        title="文档打印与导出"
        description="按岗位权限选择输出字段；客户和订单敏感信息仅对获授权角色开放，所有生成、打印和导出均留痕。"
        action={<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新文档数据" title="刷新"><RefreshCw aria-hidden="true" /></button>}
      />
      {error && <ErrorNotice error={error} />}
      <section className="section-block document-workspace">
        <form className="document-controls" onSubmit={(event) => { event.preventDefault(); void generate("PREVIEW"); }}>
          <div className="document-control-heading"><FileText aria-hidden="true" /><div><h2>输出设置</h2><p>先选择文档和字段，再生成预览。</p></div></div>
          <label className="field"><span>文档类型</span><select value={activeType} onChange={(event) => changeDocumentType(event.target.value as DocumentType)}>
            {options.map((option) => <option key={option.documentType} value={option.documentType}>{option.title}</option>)}
          </select></label>
          {isOperator && <p className="document-scope-note">仅显示已派发给本人且符合当前岗位的工序任务。</p>}
		  {requiresEntity(activeType) && activeType !== "ORDER_DETAIL" && activeType !== "FLOW_CARD" && <label className="field"><span>工艺筛选</span><select value={operationFilter} onChange={(event) => { setOperationFilter(event.target.value); setEntityId(""); }}><option value="">全部工艺</option>{operations.map(([code, name]) => <option key={code} value={code}>{name}</option>)}</select></label>}
		  {requiresEntity(activeType) && <label className="field"><span>{activeType === "ORDER_DETAIL" ? "订单" : activeType === "FLOW_CARD" ? "生产批次" : "生产任务"}</span><select required value={entityId} onChange={(event) => setEntityId(event.target.value)}>
			<option value="" disabled>{activeType === "ORDER_DETAIL" ? "请选择订单" : activeType === "FLOW_CARD" ? "请选择生产批次" : "请选择任务"}</option>
			{activeType === "ORDER_DETAIL"
			  ? orders.map((order) => <option key={order.id} value={order.id}>{order.orderNo} - {order.customerName}</option>)
		  : activeType === "FLOW_CARD"
				? flowCardTasks.map((task) => <option key={task.id} value={task.id}>{ordersById.get(task.orderId)?.orderNo ?? "订单同步中"} · {ordersById.get(task.orderId)?.customerName ?? "客户同步中"} · {task.productCode} {task.productName} · 批次 {task.batchNo}</option>)
				: <>
					<optgroup label="当前 / 在制任务">{printableTasks.current.map((task) => <option key={task.id} value={task.id}>{ordersById.get(task.orderId)?.orderNo ?? "订单同步中"} · {ordersById.get(task.orderId)?.customerName ?? "客户同步中"} · {task.productCode} {task.productName} · 批次 {task.batchNo} · {task.operationName} · {taskDocumentStatusLabel(task.status)}</option>)}</optgroup>
					{printableTasks.completed.length > 0 && <optgroup label="已完成工序（仅补打）">{printableTasks.completed.map((task) => <option key={task.id} value={task.id}>{ordersById.get(task.orderId)?.orderNo ?? "订单同步中"} · {ordersById.get(task.orderId)?.customerName ?? "客户同步中"} · {task.productCode} {task.productName} · 批次 {task.batchNo} · {task.operationName} · {taskDocumentStatusLabel(task.status)}</option>)}</optgroup>}
				</>
		}
          </select></label>}
          <fieldset className="document-field-list"><legend>输出字段</legend>
            {fields.map((field) => <label key={field.code} className="document-field-choice"><input type="checkbox" checked={selectedFields.includes(field.code)} onChange={() => toggleField(field.code)} /><span>{field.label}</span>{field.sensitive && <small>敏感</small>}</label>)}
          </fieldset>
          {sensitiveSelected && <div className="document-sensitive-note"><ShieldAlert aria-hidden="true" /><span>已选择敏感订单字段，打印或导出会记录操作人和字段范围。</span></div>}
          <button className="button button-primary" type="submit" disabled={pending !== null || validSelectedFields.length === 0 || (requiresEntity(activeType) && !entityId)}><FileText aria-hidden="true" />{pending === "PREVIEW" ? "正在生成" : "生成预览"}</button>
        </form>
        <div className="document-output">
          {preview ? preview.documentType === "WORKSHOP_JOB_SHEET" ? <WorkshopJobSheet preview={preview} /> : preview.documentType === "FLOW_CARD" ? <FlowCard preview={preview} /> : <article id="document-print-preview" className="document-preview"><header><div><span className="eyebrow">受控文档</span><h2>{preview.title}</h2><p>生成于 {new Date(preview.generatedAt).toLocaleString("zh-CN")}</p></div>{preview.sensitiveIncluded && <span className="status-badge status-urgent">含敏感信息</span>}</header><dl>{preview.fields.map((field) => <div key={field.code}><dt>{field.label}</dt><dd>{field.code === "PROCESS_IMAGE_URL" && field.value ? <img className="process-card-print-image" src={field.value} alt="本工序工艺图" /> : field.value || "-"}</dd></div>)}</dl></article> : <EmptyState title="尚未生成预览" description="选择文档、业务对象和字段后，即可查看可打印内容。" />}
          {preview && <div className="document-actions"><button className="button button-secondary" type="button" onClick={() => void generate("PRINT")} disabled={pending !== null}><Printer aria-hidden="true" />{pending === "PRINT" ? "正在准备" : "打印"}</button><button className="button button-secondary" type="button" onClick={() => void generate("EXPORT")} disabled={pending !== null}><Download aria-hidden="true" />{pending === "EXPORT" ? "正在导出" : "导出 CSV"}</button></div>}
        </div>
      </section>
      {canViewAudits && (
        <section className="section-block document-audit-section">
          <div className="section-heading"><div><h2>打印与导出审计</h2><p>记录文档、操作人、输出方式和敏感字段范围。</p></div></div>
          {audits.length === 0 ? <EmptyState title="暂无审计记录" description="生成、打印或导出文档后会在此留痕。" /> : <div className="table-scroll"><table><thead><tr><th>时间</th><th>文档</th><th>操作人</th><th>方式</th><th>字段</th><th>敏感信息</th></tr></thead><tbody>{audits.slice(0, 20).map((audit) => <tr key={audit.id}><td>{new Date(audit.occurredAt).toLocaleString("zh-CN")}</td><td>{audit.documentType}</td><td>{audit.actorCode}</td><td>{audit.outputType}</td><td>{audit.selectedFields.join(", ")}</td><td>{audit.sensitiveIncluded ? "是" : "否"}</td></tr>)}</tbody></table></div>}
        </section>
      )}
    </>
  );
}

function WorkshopJobSheet({ preview }: { preview: DocumentPreview }) {
	const values = new Map(preview.fields.map((field) => [field.code, field.value]));
	const header = (code: string) => values.get(code) || "-";
	return <article id="document-print-preview" className="workshop-job-sheet">
		<header className="job-sheet-header">
			<div><span className="eyebrow">受控车间文件</span><h2>生产派工单</h2><p>工艺卡版本：{header("PROCESS_CARD_VERSION")}</p></div>
			<TaskQr payload={header("TASK_QR_PAYLOAD")} />
		</header>
		<section className="job-sheet-identification" aria-label="派工单基础信息">
			<SheetField label="任务号" value={header("TASK_NO")} /><SheetField label="工单号" value={header("WORK_ORDER_NO")} />
			<SheetField label="产品" value={header("PRODUCT")} /><SheetField label="工序" value={header("OPERATION")} />
			<SheetField label="派发给" value={header("ASSIGNED_TO")} /><SheetField label="批次号" value={header("BATCH_NO")} />
			<SheetField label="计划数量" value={header("PLANNED_QUANTITY")} />
		</section>
		<section className="job-sheet-requirements"><h3>工程参数与工艺要求</h3><p>{header("ENGINEERING_PARAMETERS")}</p></section>
		<table className="job-sheet-log"><thead><tr><th>序号</th><th>报工数量</th><th>废品数量</th><th>开始时间</th><th>完成时间</th><th>操作工签字</th><th>主管确认</th></tr></thead><tbody>{Array.from({ length: 6 }, (_, index) => <tr key={index}><td>{index + 1}</td><td /><td /><td /><td /><td /><td /></tr>)}</tbody></table>
		<footer><span>当前累计：{header("QUANTITY_STATUS")}</span><span>制单时间：{new Date(preview.generatedAt).toLocaleString("zh-CN")}</span></footer>
	</article>;
}

function FlowCard({ preview }: { preview: DocumentPreview }) {
	const values = new Map(preview.fields.map((field) => [field.code, field.value]));
	const header = (code: string) => values.get(code) || "-";
	const rows = parseFlowRecords(header("FLOW_RECORDS"));
	return <article id="document-print-preview" className="flow-card">
		<header className="job-sheet-header">
			<div><span className="eyebrow">受控生产文件</span><h2>生产流转卡</h2><p>工艺卡版本：{header("PROCESS_CARD_VERSION")}</p></div>
			<TaskQr payload={header("FLOW_QR_PAYLOAD")} />
		</header>
		<section className="job-sheet-identification" aria-label="流转卡基础信息">
			<SheetField label="订单号" value={header("ORDER_NO")} /><SheetField label="客户" value={header("CUSTOMER_NAME")} />
			<SheetField label="产品" value={header("PRODUCT")} /><SheetField label="规格 / 材质" value={`${header("SPECIFICATION")} / ${header("MATERIAL")}`} />
			<SheetField label="工单号" value={header("WORK_ORDER_NO")} /><SheetField label="生产批次" value={header("BATCH_NO")} />
			<SheetField label="计划数量" value={header("PLANNED_QUANTITY")} /><SheetField label="当前任务" value={header("TASK_NO")} />
		</section>
		<section className="flow-card-log"><div><h3>工序流转与现场记录</h3><p>系统已填入已有报工事实；“实收 / 异常”和“签名”由现场交接人员填写。补打时会重新带入最新记录。</p></div>{rows.length === 0 ? <p className="flow-card-empty">未选择流转记录表字段。</p> : <table><thead><tr><th>序号</th><th>工序</th><th>计划 / 状态</th><th>合格</th><th>报废</th><th>实收 / 异常说明</th><th>责任人 / 完成时间</th><th>签名</th></tr></thead><tbody>{rows.map((row) => <tr key={`${row.sequenceNo}-${row.operationName}`}><td>{row.sequenceNo}</td><td>{row.operationName}</td><td>{row.plannedQuantity}<small>{flowStatus(row.status)}</small></td><td>{row.goodQuantity}</td><td>{row.scrapQuantity}</td><td className="flow-card-handwrite">&nbsp;</td><td>{row.assignedTo === "-" ? "" : row.assignedTo}<small>{formatFlowTime(row.completedAt)}</small></td><td className="flow-card-signature">&nbsp;</td></tr>)}</tbody></table>}</section>
		<footer><span>扫码打开当前生产批次</span><span>补打时间：{new Date(preview.generatedAt).toLocaleString("zh-CN")}</span></footer>
	</article>;
}

type FlowCardRecord = { sequenceNo: number; operationName: string; status: string; plannedQuantity: string; goodQuantity: string; scrapQuantity: string; assignedTo: string; completedAt: string };

function parseFlowRecords(value: string): FlowCardRecord[] {
	if (!value || value === "-") return [];
	try {
		const parsed: unknown = JSON.parse(value);
		return Array.isArray(parsed) ? parsed.filter((item): item is FlowCardRecord => typeof item === "object" && item !== null && "sequenceNo" in item && "operationName" in item) : [];
	} catch {
		return [];
	}
}

function flowStatus(status: string) {
	return ({ BLOCKED: "等待前序", READY: "待派工", ASSIGNED: "已派工", IN_PROGRESS: "生产中", COMPLETED: "已完成" } as Record<string, string>)[status] ?? status;
}

function formatFlowTime(value: string) {
	return value ? new Date(value).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
}

function SheetField({ label, value }: { label: string; value: string }) {
	return <div><span>{label}</span><strong>{value}</strong></div>;
}

function TaskQr({ payload }: { payload: string }) {
	const [src, setSrc] = useState("");
	useEffect(() => {
		if (!payload || payload === "-") return;
		void QRCode.toDataURL(payload, { width: 116, margin: 1, errorCorrectionLevel: "M" }).then(setSrc);
	}, [payload]);
	return <div className="job-sheet-qr">{src ? <img src={src} alt="扫描打开电子工单" /> : <span aria-live="polite">二维码生成中</span>}<small>扫描电子工单</small></div>;
}

function exportCsv(preview: DocumentPreview) {
  const escape = (value: string) => `"${value.replaceAll('"', '""').replaceAll("\n", " ")}"`;
  const csv = ["字段,内容", ...preview.fields.map((field) => `${escape(field.label)},${escape(field.value)}`)].join("\n");
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob(["\ufeff", csv], { type: "text/csv;charset=utf-8" }));
  link.download = `${preview.title}-${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
}
