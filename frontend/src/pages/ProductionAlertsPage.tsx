import { AlertTriangle, RefreshCw } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, formatDate, formatQuantity, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, ManualShellDryingAlert } from "../types";

export function ProductionAlertsPage({ user }: { user: AccessUser }) {
  const state = useAsyncData(async () => {
    const [shortages, shellDrying] = await Promise.all([api.productionAlerts.list(user.employeeCode), api.manualShellDryingAlerts.list(user.employeeCode)]);
    return { shortages, shellDrying };
  }, [user.employeeCode]);
  if (state.loading) return <LoadingState label="正在计算生产预警" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const alerts = state.data?.shortages ?? [];
  const shellDryingAlerts = state.data?.shellDrying ?? [];

  return <>
    <PageHeader title="生产预警" description="集中查看订单生产缺口与人工制壳干燥停留。交接数量异常不阻断生产，但会参与缺口风险判断。" action={<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新生产预警"><RefreshCw aria-hidden="true" /></button>} />
    <section className="kpi-grid" aria-label="生产缺口概览">
      <article className="kpi-card alert"><span>待处理订单行</span><strong>{alerts.length}</strong><AlertTriangle aria-hidden="true" /></article>
      <article className="kpi-card"><span>累计预计缺口</span><strong>{formatQuantity(alerts.reduce((sum, item) => sum + item.shortageQuantity, 0))}</strong><small>件</small></article>
      <article className={`kpi-card ${shellDryingAlerts.length ? "alert" : ""}`}><span>人工制壳超时</span><strong>{shellDryingAlerts.length}</strong><small>超过 48 小时未进入下一层</small></article>
    </section>
    <section className="section-block">
      <header className="section-heading"><div><h2>人工制壳干燥停留预警</h2><p>仅监测选择手动制壳线、且最近一层仍等待下一层制壳的批次；继续报层或流转后自动关闭。</p></div></header>
      {shellDryingAlerts.length === 0 ? <EmptyState title="没有超时制壳批次" description="人工制壳等待时间未超过 48 小时。" /> : <ManualShellDryingTable alerts={shellDryingAlerts} />}
    </section>
    <section className="section-block">
      {alerts.length === 0 ? <EmptyState title="当前没有订单生产缺口" description="投产余量可以覆盖已发生的损耗，或已完成补投处理。" /> : <div className="table-scroll"><table><thead><tr><th>订单 / 工单</th><th>产品 / 产线</th><th>订单需求</th><th>可交付上限</th><th>预计缺口</th><th>首次预警</th><th>最近评估</th><th>状态</th></tr></thead><tbody>{alerts.map((alert) => <tr key={alert.id}><td><strong>{alert.orderNo}</strong><small>{alert.workOrderNo}</small></td><td><strong>{alert.productName}</strong><small>{alert.routeType === "MID_TEMP_WAX" ? "中温蜡线" : alert.routeType === "LOW_TEMP_WAX" ? "低温蜡线" : "砂型线"}</small></td><td>{formatQuantity(alert.demandQuantity)}</td><td>{formatQuantity(alert.projectedQuantity)}</td><td><strong className="danger-text">{formatQuantity(alert.shortageQuantity)}</strong></td><td>{formatDate(alert.detectedAt, true)}</td><td>{formatDate(alert.lastEvaluatedAt, true)}</td><td><StatusBadge value={alert.status} /></td></tr>)}</tbody></table></div>}
    </section>
  </>;
}

function ManualShellDryingTable({ alerts }: { alerts: ManualShellDryingAlert[] }) {
  return <div className="table-scroll"><table><thead><tr><th>订单 / 任务</th><th>产品 / 材质</th><th>制壳层次</th><th>等待时长</th><th>开始等待</th><th>状态</th></tr></thead><tbody>{alerts.map((alert) => <tr key={alert.id}><td><strong>{alert.orderNo}</strong><small>{alert.taskNo} · {alert.operationName}</small></td><td><strong>{alert.productName}</strong><small>{alert.productMaterial ?? "材质未登记"} · {alert.routeType === "MID_TEMP_WAX" ? "中温蜡" : "低温蜡"}</small></td><td>第 {alert.layerCount} 层后等待</td><td><strong className="danger-text">{alert.waitingHours} 小时</strong></td><td>{formatDate(alert.waitingSince, true)}</td><td><StatusBadge value="OPEN" /></td></tr>)}</tbody></table></div>;
}
