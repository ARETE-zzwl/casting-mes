import { FormEvent, useState } from "react";
import { Check, ClipboardPenLine, RefreshCw, RotateCcw } from "lucide-react";
import { api } from "../api";
import { ErrorNotice, Field, formatDate, formatQuantity, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { PaperReportSheet } from "../types";

export function PaperReportsPage({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [sheets, tasks] = await Promise.all([api.labor.paperSheets(operatorCode), api.tasks.list(undefined, undefined, undefined, operatorCode)]);
    return { sheets, tasks };
  }, [operatorCode]);
  const [creating, setCreating] = useState(false);
  const [rejecting, setRejecting] = useState<PaperReportSheet | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    const task = state.data?.tasks.find((item) => item.id === String(form.get("taskId")));
    if (!task?.assignedTo) { setError(new Error("请选择已派工的在制任务")); return; }
    const kind = String(form.get("reportKind")) as "QUANTITY" | "HOURS";
    setSaving(true); setError(null);
    try {
      await api.labor.createPaperSheet({ taskId: task.id, workerCode: task.assignedTo, reportKind: kind, goodQuantity: kind === "QUANTITY" ? Number(form.get("goodQuantity")) : undefined, scrapQuantity: kind === "QUANTITY" ? Number(form.get("scrapQuantity")) : undefined, hours: kind === "HOURS" ? Number(form.get("hours")) : undefined, note: String(form.get("note") || "") || undefined, enteredBy: operatorCode, paperFormNo: String(form.get("paperFormNo") || "") || undefined });
      setCreating(false); await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function approve(sheet: PaperReportSheet) {
    setSaving(true); setError(null);
    try { await api.labor.approvePaperSheet(sheet.id, operatorCode); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function reject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!rejecting) return;
    const reason = String(new FormData(event.currentTarget).get("reason")); setSaving(true); setError(null);
    try { await api.labor.rejectPaperSheet(rejecting.id, operatorCode, reason); setRejecting(null); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  if (state.loading) return <LoadingState label="正在加载纸质报工台账" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const sheets = state.data?.sheets ?? [];
  const tasks = (state.data?.tasks ?? []).filter((task) => task.assignedTo && ["ASSIGNED", "IN_PROGRESS"].includes(task.status));
  const pending = sheets.filter((sheet) => sheet.status === "PENDING");

  return <>
    <PageHeader title="纸质报工回收" description="主管派发纸质单，回收后按纸单号录入；审核入账或退回，全程保留录入与审核人。" action={<div className="header-actions"><button className="button button-primary" type="button" onClick={() => setCreating(true)}><ClipboardPenLine aria-hidden="true" />录入回收纸单</button><button className="icon-button" type="button" aria-label="刷新" onClick={state.reload}><RefreshCw aria-hidden="true" /></button></div>} />
    {error != null && <ErrorNotice error={error} />}
    <section className="cart-summary"><div><span>待审核纸单</span><strong>{pending.length}</strong></div><div><span>已入账</span><strong>{sheets.filter((sheet) => sheet.status === "APPROVED").length}</strong></div><div><span>已退回</span><strong>{sheets.filter((sheet) => sheet.status === "REJECTED").length}</strong></div></section>
    <section className="section-block"><div className="table-scroll"><table><thead><tr><th>纸单 / 系统单</th><th>任务 / 员工</th><th>结果</th><th>录入 / 审核</th><th>状态</th><th>操作</th></tr></thead><tbody>{sheets.map((sheet) => <tr key={sheet.id}><td><strong>{sheet.paperFormNo ?? "未编号"}</strong><small>{sheet.sheetNo}</small></td><td>{taskName(sheet.taskId, state.data?.tasks ?? [])}<small>{sheet.workerCode}</small></td><td>{sheet.reportKind === "HOURS" ? `${sheet.hours} 小时` : `${formatQuantity(sheet.goodQuantity ?? 0)} 合格 / ${formatQuantity(sheet.scrapQuantity ?? 0)} 报废`}<small>{sheet.note ?? "-"}</small></td><td>{sheet.enteredBy}<small>{formatDate(sheet.enteredAt, true)} · {sheet.reviewedBy ?? sheet.rejectedBy ?? "待审核"}</small></td><td><StatusBadge value={sheet.status} />{sheet.rejectionReason && <small>{sheet.rejectionReason}</small>}</td><td>{sheet.status === "PENDING" ? <><button className="button button-primary button-small" disabled={saving} onClick={() => void approve(sheet)}><Check aria-hidden="true" />审核入账</button> <button className="button button-secondary button-small" disabled={saving} onClick={() => setRejecting(sheet)}><RotateCcw aria-hidden="true" />退回</button></> : "-"}</td></tr>)}</tbody></table></div></section>
    {creating && <Modal title="录入回收纸单" description="纸单结果以原始填写内容为准；系统会按当前主管的数据范围校验任务。" width="small" onClose={() => !saving && setCreating(false)}><form onSubmit={create}><div className="form-grid"><Field label="在制任务" required><select name="taskId" required defaultValue=""><option value="" disabled>选择本主管范围内任务</option>{tasks.map((task) => <option key={task.id} value={task.id}>{task.taskNo} · {task.operationName} · {task.assignedTo}</option>)}</select></Field><Field label="纸单编号"><input name="paperFormNo" maxLength={64} placeholder="派工单或流转卡编号" /></Field><Field label="报工类型" required><select name="reportKind"><option value="QUANTITY">数量</option><option value="HOURS">工时</option></select></Field><Field label="合格数量"><input name="goodQuantity" type="number" min="0" step="0.001" defaultValue="0" /></Field><Field label="报废数量"><input name="scrapQuantity" type="number" min="0" step="0.001" defaultValue="0" /></Field><Field label="工时"><input name="hours" type="number" min="0.001" step="0.25" /></Field><Field label="纸单备注"><textarea name="note" rows={3} maxLength={500} /></Field></div><SubmitActions pending={saving} submitLabel="保存待审核" onCancel={() => setCreating(false)} /></form></Modal>}
    {rejecting && <Modal title="退回纸质报工" description="退回不会写入生产或工资数据，请说明需要更正的内容。" width="small" onClose={() => !saving && setRejecting(null)}><form onSubmit={reject}><Field label="退回原因" required><textarea name="reason" rows={4} maxLength={500} required autoFocus /></Field><SubmitActions pending={saving} submitLabel="确认退回" onCancel={() => setRejecting(null)} /></form></Modal>}
  </>;
}

function taskName(taskId: string, tasks: Array<{ id: string; taskNo: string; operationName: string }>) {
  const task = tasks.find((item) => item.id === taskId);
  return task ? `${task.taskNo} · ${task.operationName}` : "历史任务";
}
