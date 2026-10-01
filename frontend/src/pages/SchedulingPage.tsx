import { RefreshCw, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api";
import { EmptyState, ErrorNotice, LoadingState, PageHeader, StatusBadge, formatDate, formatQuantity } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AiAdvice, ScheduleQueueItem } from "../types";

const lineLabels: Record<ScheduleQueueItem["lineCode"], string> = {
  MID_WAX: "中温蜡线",
  LOW_WAX: "低温蜡线",
  SAND_OUTSOURCE: "砂型外协"
};

const priorityWeight: Record<ScheduleQueueItem["priority"], number> = {
  SAMPLE: 3,
  URGENT: 2,
  NORMAL: 1
};

export function SchedulingPage({ operatorCode }: { operatorCode: string }) {
	const [lineCode, setLineCode] = useState<ScheduleQueueItem["lineCode"]>("MID_WAX");
  const [risk, setRisk] = useState<AiAdvice | null>(null);
  const [riskLoading, setRiskLoading] = useState(false);
  const [riskError, setRiskError] = useState<unknown>(null);
  const state = useAsyncData(() => api.scheduling.queue());

  useEffect(() => { setRisk(null); }, [lineCode]);

  async function analyzeRisk() {
    setRiskLoading(true);
    setRiskError(null);
    try { setRisk(await api.aiAssistant.scheduleRisk(lineCode, operatorCode)); }
    catch (caught) { setRiskError(caught); }
    finally { setRiskLoading(false); }
  }

  if (state.loading) return <LoadingState label="正在加载三线排产队列" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const queue = state.data ?? [];
  const lineQueue = queue.filter((item) => item.lineCode === lineCode);
  const activeCount = lineQueue.filter((item) => item.taskStatus === "IN_PROGRESS").length;
  const priorityCount = lineQueue.filter((item) => priorityWeight[item.priority] > 1).length;

  return (
    <>
      <PageHeader
        title="三线排产"
        description="先选择一条生产线再排产；样品、急单和普通单仅在本线未开工队列中排序，已开工任务保持资源独占。"
        action={<div className="header-actions"><button className="button button-secondary" type="button" onClick={analyzeRisk} disabled={riskLoading}><Sparkles aria-hidden="true" />{riskLoading ? "分析中" : "AI 排产风险"}</button><button className="icon-button" type="button" onClick={state.reload} aria-label="刷新排产队列"><RefreshCw aria-hidden="true" /></button></div>}
      />
      <section className="schedule-summary" aria-label="排产概览">
        <div><span>待排任务</span><strong>{lineQueue.length}</strong></div>
        <div><span>生产中</span><strong>{activeCount}</strong></div>
        <div><span>样品与急单</span><strong>{priorityCount}</strong></div>
      </section>
      <section className="filter-bar" aria-label="排产生产线">
		<div className="segmented-control" role="group" aria-label="选择排产生产线">
			{(Object.keys(lineLabels) as ScheduleQueueItem["lineCode"][]).map((line) => <button key={line} type="button" className={lineCode === line ? "active" : ""} aria-pressed={lineCode === line} onClick={() => setLineCode(line)}>{lineLabels[line]}</button>)}
		</div>
	  </section>
      {riskError && <ErrorNotice error={riskError} />}
      {risk && <section className="ai-advice-card"><div><span className="eyebrow">{lineLabels[lineCode]} · AI 辅助</span><h2>{risk.title}</h2></div><p>{risk.summary}</p><ol>{risk.actions.map((action) => <li key={action}>{action}</li>)}</ol><p className="ai-caution"><strong>人工核对：</strong>{risk.caution}</p></section>}
      {lineQueue.length === 0 ? (
        <EmptyState title="当前没有已放行的排产任务" description="审批并放行订单后，系统会按路线自动进入对应生产线队列。" />
      ) : (
        <div className="schedule-lines">
            <section className="schedule-line" aria-labelledby={`line-${lineCode}`}>
              <header>
                <div>
                  <h2 id={`line-${lineCode}`}>{lineLabels[lineCode]}</h2>
                  <p>{lineQueue.length} 个工序任务，已开工任务不可插队</p>
                </div>
              </header>
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr><th>优先级</th><th>订单/工单</th><th>工序</th><th>数量</th><th>交期</th><th>责任人</th><th>状态</th></tr>
                    </thead>
                    <tbody>
                      {lineQueue.map((item) => (
                        <tr key={item.id} className={item.taskStatus === "IN_PROGRESS" ? "schedule-locked" : undefined}>
                          <td><StatusBadge value={item.priority} /></td>
                          <td><strong>{item.orderNo}</strong><small>{item.taskNo}</small></td>
                          <td className="primary-cell">{item.operationName}</td>
                          <td>{formatQuantity(item.plannedQuantity)}</td>
                          <td>{formatDate(item.requestedDeliveryDate)}</td>
                          <td>{item.assignedTo ?? "待派工"}</td>
                          <td><StatusBadge value={item.taskStatus} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
            </section>
        </div>
      )}
    </>
  );
}
