import { FormEvent, useState } from "react";
import { CheckCircle2, Sparkles, UserRoundCheck } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatDate, formatQuantity, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AiAdvice, HandoffException } from "../types";

export function HandoffExceptionsPage({ operatorCode }: { operatorCode: string }) {
  const [includeResolved, setIncludeResolved] = useState(false);
  const [active, setActive] = useState<HandoffException | null>(null);
  const [advising, setAdvising] = useState<HandoffException | null>(null);
  const [advice, setAdvice] = useState<AiAdvice | null>(null);
  const [mode, setMode] = useState<"assign" | "resolve">("assign");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [keyword, setKeyword] = useState("");
  const [resolutionStatus, setResolutionStatus] = useState("");
  const [page, setPage] = useState(0);
  const state = useAsyncData(() => api.handoffExceptions.page({ includeResolved, keyword: keyword || undefined, resolutionStatus: resolutionStatus || undefined, page }), [includeResolved, keyword, resolutionStatus, page]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!active) return;
    const form = new FormData(event.currentTarget); setSaving(true); setError(null);
    try {
      if (mode === "assign") await api.handoffExceptions.assign(active.sourceType, active.id, String(form.get("ownerCode")), operatorCode);
      else await api.handoffExceptions.resolve(active.sourceType, active.id, String(form.get("resolutionNote")), operatorCode);
      setActive(null); await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function getAdvice(item: HandoffException) {
    setAdvising(item);
    setAdvice(null);
    setError(null);
    try {
      setAdvice(await api.aiAssistant.handoff(item.sourceType, item.id, operatorCode));
    } catch (caught) {
      setAdvising(null);
      setError(caught);
    }
  }

  if (state.loading) return <LoadingState label="正在加载交接异常待办" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const exceptions = state.data?.items ?? [];
  return <>
    <PageHeader title="交接异常待办" description="集中处理周转车与工序交接的数量差异、照片、责任人和关闭记录；关闭异常不会改写原始报工事实。" />
    {error && <ErrorNotice error={error} />}
    <section className="filter-bar"><label className="toggle-control"><input type="checkbox" checked={includeResolved} onChange={(event) => { setIncludeResolved(event.target.checked); setPage(0); }} /><span aria-hidden="true" />显示已关闭</label><input value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(0); }} placeholder="单号、订单、产品或责任人" aria-label="检索交接异常" /><select value={resolutionStatus} onChange={(event) => { setResolutionStatus(event.target.value); setPage(0); }} aria-label="按处理状态筛选"><option value="">全部状态</option><option value="OPEN">待处理</option><option value="IN_PROGRESS">处理中</option><option value="RESOLVED">已关闭</option></select><span className="muted">当前 {state.data?.totalElements ?? 0} 项</span></section>
    <section className="section-block">
      {exceptions.length === 0 ? <EmptyState title="没有待处理交接异常" description="周转车接收或工序交接发生数量差异时会自动进入这里。" /> : <div className="table-scroll"><table><thead><tr><th>异常来源</th><th>订单/工序</th><th>数量差异</th><th>原因与证据</th><th>责任人</th><th>状态</th><th className="actions-cell">操作</th></tr></thead><tbody>{exceptions.map((item) => <tr key={`${item.sourceType}-${item.id}`}><td><strong>{item.referenceNo}</strong><small>{item.sourceType === "CART" ? "周转车交接" : "工序交接"}</small></td><td><strong>{item.orderNo}</strong><small>{item.productName} · {item.operationName} · {item.taskNo}</small></td><td>{formatQuantity(item.expectedQuantity)} / {item.receivedQuantity == null ? "待接收" : formatQuantity(item.receivedQuantity)}</td><td>{item.reason}<small>{item.evidenceUrl ? <a href={item.evidenceUrl} target="_blank" rel="noreferrer">查看现场照片</a> : "无照片"}</small></td><td>{item.ownerCode ?? "未指派"}<small>{item.handedOverBy} {" -> "} {item.receivedBy ?? "待接收"}</small></td><td><StatusBadge value={item.resolutionStatus} /></td><td className="actions-cell">{item.resolutionStatus !== "RESOLVED" && <><button type="button" className="icon-button compact" title="AI 辅助分析" aria-label="AI 辅助分析" onClick={() => getAdvice(item)}><Sparkles /></button><button type="button" className="icon-button compact" title="指派责任人" aria-label="指派责任人" onClick={() => { setMode("assign"); setActive(item); }}><UserRoundCheck /></button><button type="button" className="button button-primary button-small" onClick={() => { setMode("resolve"); setActive(item); }}><CheckCircle2 aria-hidden="true" />关闭</button></>}</td></tr>)}</tbody></table></div>}
    </section>
	{(state.data?.totalPages ?? 0) > 1 && <nav className="warehouse-pagination" aria-label="交接异常分页"><button type="button" className="button button-secondary button-small" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>上一页</button><span>第 {page + 1} / {state.data!.totalPages} 页，共 {state.data!.totalElements} 项</span><button type="button" className="button button-secondary button-small" disabled={page + 1 >= state.data!.totalPages} onClick={() => setPage((current) => current + 1)}>下一页</button></nav>}
    {active && <Modal title={mode === "assign" ? `指派异常责任人 - ${active.referenceNo}` : `关闭异常 - ${active.referenceNo}`} description={mode === "assign" ? "指派后异常保留为处理中，原始数量和照片不可修改。" : "填写现场核对、补产、让步接收或其他处置结果。"} onClose={() => !saving && setActive(null)}><form onSubmit={submit}>{mode === "assign" ? <Field label="责任人/跟进人" required><input name="ownerCode" required defaultValue={active.ownerCode ?? operatorCode} maxLength={64} autoFocus /></Field> : <Field label="处置结论" required><textarea name="resolutionNote" required rows={5} maxLength={1000} autoFocus placeholder="例如：复核后确认运输遗失 1 件，已建立补产批次；接收方按实收数量继续生产。" /></Field>}<SubmitActions pending={saving} submitLabel={mode === "assign" ? "确认指派" : "确认关闭"} onCancel={() => setActive(null)} /></form></Modal>}
    {advising && <Modal title={`AI 辅助分析 - ${advising.referenceNo}`} description="建议只用于整理待办，数量、责任和关闭结论仍须由主管核对后手动处理。" onClose={() => setAdvising(null)}>{advice ? <AdviceContent advice={advice} /> : <LoadingState label="正在整理异常信息" />}</Modal>}
  </>;
}

function AdviceContent({ advice }: { advice: AiAdvice }) {
  return <div className="ai-advice"><p>{advice.summary}</p><h3>建议待办</h3><ol>{advice.actions.map((action) => <li key={action}>{action}</li>)}</ol><p className="ai-caution"><strong>人工核对：</strong>{advice.caution}</p></div>;
}
