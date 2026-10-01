import { FormEvent, useMemo, useState } from "react";
import { Check, Flame, Sparkles } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatDate, formatQuantity, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AiAdvice, FurnaceBatch } from "../types";

export function FurnaceBatchesPage({ operatorCode }: { operatorCode: string }) {
  const [operation, setOperation] = useState<"DEWAX" | "POURING">("DEWAX");
  const [completing, setCompleting] = useState<FurnaceBatch | null>(null);
  const [reviewing, setReviewing] = useState<FurnaceBatch | null>(null);
  const [advice, setAdvice] = useState<AiAdvice | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(async () => {
    const [batches, tasks, resources] = await Promise.all([api.furnaceBatches.list(), api.tasks.list(), api.resources.list()]);
    return { batches, tasks, furnaces: resources.filter((asset) => asset.assetType === "FURNACE") };
  }, []);
  const readyTasks = useMemo(() => (state.data?.tasks ?? []).filter((task) => task.operationCode === operation && ["ASSIGNED", "IN_PROGRESS"].includes(task.status)), [state.data, operation]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const formElement = event.currentTarget; const form = new FormData(formElement);
    const selectedTaskIds = form.getAll("taskIds").map(String);
    setSaving(true); setError(null);
    try {
      await api.furnaceBatches.create({ operationCode: operation, furnaceAssetId: String(form.get("furnaceAssetId") || "") || undefined,
        materialBatch: String(form.get("materialBatch") || "") || undefined, chargeQuantity: Number(form.get("chargeQuantity")),
        targetTemperature: numberOrUndefined(form.get("targetTemperature")), actualTemperature: numberOrUndefined(form.get("actualTemperature")),
        pressureMpa: numberOrUndefined(form.get("pressureMpa")), taskIds: selectedTaskIds, note: String(form.get("note") || "") || undefined, createdBy: operatorCode });
      formElement.reset(); await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function complete(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!completing) return; const form = new FormData(event.currentTarget);
    setSaving(true); setError(null);
    try { await api.furnaceBatches.complete(completing.id, { actualTemperature: numberOrUndefined(form.get("actualTemperature")), pressureMpa: numberOrUndefined(form.get("pressureMpa")), note: String(form.get("note") || "") || undefined, completedBy: operatorCode }); setCompleting(null); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function review(batch: FurnaceBatch) {
    setReviewing(batch);
    setAdvice(null);
    setError(null);
    try {
      setAdvice(await api.aiAssistant.furnace(batch.id, operatorCode));
    } catch (caught) {
      setReviewing(null);
      setError(caught);
    }
  }

  if (state.loading) return <LoadingState label="正在加载炉次台账" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const data = state.data!;
  return <>
    <PageHeader title="脱蜡与浇筑炉次台账" description="一个炉次可关联同工序的多个订单任务，统一记录炉/釜、材质批次、装炉量、温度、压力和完成结果。" />
    {error && <ErrorNotice error={error} />}
    <section className="filter-bar"><div className="segmented-control"><button type="button" className={operation === "DEWAX" ? "active" : ""} onClick={() => setOperation("DEWAX")}>脱蜡炉次</button><button type="button" className={operation === "POURING" ? "active" : ""} onClick={() => setOperation("POURING")}>浇筑炉次</button></div></section>
    <section className="section-block"><form onSubmit={create}><div className="form-grid form-grid-3"><Field label="炉/釜设备"><select name="furnaceAssetId" defaultValue=""><option value="">未绑定设备</option>{data.furnaces.map((furnace) => <option key={furnace.id} value={furnace.id}>{furnace.assetCode} · {furnace.assetName}</option>)}</select></Field><Field label="材质批次"><input name="materialBatch" maxLength={128} placeholder={operation === "POURING" ? "例如 304-HEAT-01" : "脱蜡介质/程序批次"} /></Field><Field label="装炉/浇筑数量" required><input name="chargeQuantity" type="number" min="0.001" step="0.001" required /></Field><Field label="目标温度"><input name="targetTemperature" type="number" step="0.01" /></Field><Field label="实际温度"><input name="actualTemperature" type="number" step="0.01" /></Field><Field label="压力 MPa"><input name="pressureMpa" type="number" step="0.001" /></Field><Field label="关联任务" required><select name="taskIds" multiple required size={Math.min(Math.max(readyTasks.length, 3), 7)}>{readyTasks.map((task) => <option key={task.id} value={task.id}>{task.taskNo} · {task.workOrderNo} · {formatQuantity(task.plannedQuantity)} PCS · {task.status}</option>)}</select></Field><Field label="备注"><textarea name="note" rows={3} maxLength={1000} placeholder="炉前检查、异常或现场说明" /></Field></div><SubmitActions pending={saving} submitLabel={`建立${operation === "DEWAX" ? "脱蜡" : "浇筑"}炉次`} onCancel={() => undefined} /></form></section>
    <section className="section-block"><div className="section-heading"><div><h2>炉次记录</h2><p>任务完工报工与炉次关闭分别留痕，避免用一条任务记录替代多个订单的炉前事实。</p></div></div>{data.batches.length === 0 ? <EmptyState title="暂无炉次记录" description="建立脱蜡或浇筑炉次后将在此显示。" /> : <div className="table-scroll"><table><thead><tr><th>炉次</th><th>设备/材质</th><th>数量与参数</th><th>关联任务</th><th>状态</th><th className="actions-cell">操作</th></tr></thead><tbody>{data.batches.map((batch) => <tr key={batch.id}><td><strong>{batch.furnaceBatchNo}</strong><small>{batch.operationCode === "DEWAX" ? "脱蜡" : "浇筑"} · {formatDate(batch.createdAt, true)}</small></td><td>{batch.furnaceCode ?? "未绑定设备"}<small>{batch.materialBatch ?? "未登记材质批次"}</small></td><td>{formatQuantity(batch.chargeQuantity)}<small>目标 {batch.targetTemperature ?? "-"}C / 实际 {batch.actualTemperature ?? "-"}C / 压力 {batch.pressureMpa ?? "-"} MPa</small></td><td>{batch.taskReferences}</td><td><StatusBadge value={batch.status} /></td><td className="actions-cell"><button type="button" className="icon-button compact" title="AI 复盘" aria-label="AI 复盘" onClick={() => review(batch)}><Sparkles /></button>{batch.status === "OPEN" && <button type="button" className="button button-primary button-small" onClick={() => setCompleting(batch)}><Check aria-hidden="true" />关闭炉次</button>}</td></tr>)}</tbody></table></div>}</section>
    {completing && <Modal title={`关闭炉次 ${completing.furnaceBatchNo}`} description="核对实际温度、压力和现场结论；此操作不替代各任务的报工。" onClose={() => !saving && setCompleting(null)}><form onSubmit={complete}><div className="form-grid"><Field label="实际温度"><input name="actualTemperature" type="number" step="0.01" defaultValue={completing.actualTemperature ?? ""} /></Field><Field label="压力 MPa"><input name="pressureMpa" type="number" step="0.001" defaultValue={completing.pressureMpa ?? ""} /></Field><Field label="完成说明"><textarea name="note" rows={4} maxLength={1000} defaultValue={completing.note ?? ""} /></Field></div><SubmitActions pending={saving} submitLabel="确认关闭炉次" onCancel={() => setCompleting(null)} /></form></Modal>}
    {reviewing && <Modal title={`AI 炉次复盘 - ${reviewing.furnaceBatchNo}`} description="AI 只解释参数和报废数据的可能关注点，不替代质量判定与炉次关闭。" onClose={() => setReviewing(null)}>{advice ? <AdviceContent advice={advice} /> : <LoadingState label="正在整理炉次数据" />}</Modal>}
  </>;
}

function numberOrUndefined(value: FormDataEntryValue | null) { const number = Number(value); return Number.isFinite(number) && number !== 0 ? number : undefined; }

function AdviceContent({ advice }: { advice: AiAdvice }) {
  return <div className="ai-advice"><p>{advice.summary}</p><h3>建议待办</h3><ol>{advice.actions.map((action) => <li key={action}>{action}</li>)}</ol><p className="ai-caution"><strong>人工核对：</strong>{advice.caution}</p></div>;
}
