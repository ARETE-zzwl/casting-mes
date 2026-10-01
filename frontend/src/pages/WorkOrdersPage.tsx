import { Gauge, Layers3, Play, RefreshCw } from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "../api";
import {
  EmptyState,
  ErrorNotice,
  formatDate,
  formatQuantity,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions,
  Field
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { RouteType, WorkOrder } from "../types";

const routeNames: Record<string, string> = {
  MID_TEMP_WAX: "中温蜡",
  LOW_TEMP_WAX: "低温蜡",
  SAND_OUTSOURCE: "砂型外协"
};

const routes: Array<{ value: RouteType; label: string }> = [
  { value: "MID_TEMP_WAX", label: "中温蜡线" },
  { value: "LOW_TEMP_WAX", label: "低温蜡线" },
  { value: "SAND_OUTSOURCE", label: "砂型外协" }
];

export function productionMargin(plannedQuantity: number, orderQuantity: number) {
  const quantity = Math.max(0, plannedQuantity - orderQuantity);
  const rate = orderQuantity > 0 ? (quantity / orderQuantity) * 100 : 0;
  return { quantity, rate };
}

export function batchProgressStatus(workOrder: Pick<WorkOrder, "taskCount" | "completedTaskCount" | "currentOperationName" | "currentTaskStatus">) {
  if (workOrder.taskCount > 0 && workOrder.completedTaskCount === workOrder.taskCount) {
    return { status: "COMPLETED", operation: "全部工序已完成" };
  }
  return { status: workOrder.currentTaskStatus ?? "READY", operation: workOrder.currentOperationName ?? "首道待派工" };
}

export function WorkOrdersPage({ operatorCode }: { operatorCode: string }) {
	const [searchParams, setSearchParams] = useSearchParams();
	const [routeType, setRouteType] = useState<RouteType>(() => (searchParams.get("route") as RouteType) || "MID_TEMP_WAX");
  const [batchTarget, setBatchTarget] = useState<WorkOrder | null>(null);
  const [marginTarget, setMarginTarget] = useState<WorkOrder | null>(null);
	const [launchTarget, setLaunchTarget] = useState<WorkOrder | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(() => api.workOrders.list(routeType), [routeType]);

	useEffect(() => {
		const workOrderId = searchParams.get("split");
		if (!workOrderId || batchTarget) return;
		const target = state.data?.find((workOrder) => workOrder.id === workOrderId);
		if (target) {
			setBatchTarget(target);
			setSearchParams({});
		}
	}, [batchTarget, searchParams, setSearchParams, state.data]);

  async function configureBatches(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!batchTarget) return;
    const raw = String(new FormData(event.currentTarget).get("batchQuantities") ?? "");
    const quantities = raw.split(/[，,\s]+/).filter(Boolean).map(Number);
    if (quantities.length === 0 || quantities.some((quantity) => !Number.isInteger(quantity) || quantity <= 0)) {
	  setError(new Error("请输入以逗号或换行分隔的正整数批次数量"));
      return;
    }
    const total = quantities.reduce((sum, quantity) => sum + quantity, 0);
    if (Math.abs(total - batchTarget.plannedQuantity) > 0.0001) {
	  setError(new Error(`批次数量合计必须等于实际投产数量 ${formatQuantity(batchTarget.plannedQuantity)}`));
      return;
    }
    setPending(true); setError(null);
    try {
      await api.workOrders.configureBatches(batchTarget.id, quantities, operatorCode);
      setBatchTarget(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  async function applyMargin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!marginTarget) return;
    const productionQuantity = Number(new FormData(event.currentTarget).get("productionQuantity"));
    if (!Number.isInteger(productionQuantity) || productionQuantity < marginTarget.orderQuantity) {
      setError(new Error("实际投产量必须是不小于订单数量的整数"));
      return;
    }
    setPending(true); setError(null);
    try {
      await api.workOrders.applyInitialProductionQuantity(marginTarget.id, productionQuantity, operatorCode);
      setMarginTarget(null); await state.reload();
    } catch (caught) { setError(caught); } finally { setPending(false); }
  }

	async function launchBatch(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!launchTarget) return;
		const productionQuantity = Number(new FormData(event.currentTarget).get("productionQuantity"));
		if (!Number.isInteger(productionQuantity) || productionQuantity <= 0) {
			setError(new Error("实际投产数量必须为正整数"));
			return;
		}
		setPending(true); setError(null);
		try {
			await api.workOrders.launchBatch(launchTarget.batchId, productionQuantity, operatorCode);
			setLaunchTarget(null);
			await state.reload();
		} catch (caught) {
			setError(caught);
		} finally {
			setPending(false);
		}
	}

  if (state.loading) return <LoadingState label="正在加载工单批次" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const workOrders = state.data ?? [];
	const plannedTotals = new Map<string, number>();
	workOrders.forEach((workOrder) => plannedTotals.set(
		workOrder.id,
		(plannedTotals.get(workOrder.id) ?? 0) + workOrder.plannedQuantity
	));
  return (
    <>
      <PageHeader
        title="工单批次"
        description="订单产品可在派工前按数量拆为多个独立生产批次；每个批次拥有完整任务链和独立批次号。"
        action={
          <button className="icon-button" onClick={state.reload} aria-label="刷新">
            <RefreshCw aria-hidden="true" />
          </button>
        }
      />
	  {error && <ErrorNotice error={error} />}
	  <section className="filter-bar" aria-label="工单生产线">
		<div className="segmented-control" role="group" aria-label="选择工单生产线">
			{routes.map((route) => <button key={route.value} type="button" className={routeType === route.value ? "active" : ""} aria-pressed={routeType === route.value} onClick={() => setRouteType(route.value)}>{route.label}</button>)}
		</div>
		<span className="muted">当前仅显示{routes.find((route) => route.value === routeType)?.label}工单与生产批次。</span>
	  </section>
      <section className="section-block">
        {workOrders.length === 0 ? (
          <EmptyState title="暂无工单" description="审批并放行订单后，工单和批次会显示在这里。" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>工单号</th>
                  <th>生产批次</th>
                  <th>产品</th>
                  <th>路线</th>
                  <th>批次投产 / 订单需求</th>
                  <th>任务进度</th>
                  <th>状态</th>
				  <th className="actions-cell">操作</th>
                  <th>创建时间</th>
                </tr>
              </thead>
              <tbody>
                {workOrders.map((workOrder) => {
				  const isSingleInitialBatch = workOrders.filter((item) => item.id === workOrder.id).length === 1;
				  const pendingLaunch = workOrder.batchStatus === "PENDING_LAUNCH";
				  const plannedTotal = plannedTotals.get(workOrder.id) ?? workOrder.plannedQuantity;
				  const margin = productionMargin(plannedTotal, workOrder.orderQuantity);
				  const batchProgress = batchProgressStatus(workOrder);
                  const progress =
                    workOrder.taskCount === 0
                      ? 0
                      : Math.round(
                          (workOrder.completedTaskCount / workOrder.taskCount) * 100
                        );
                  return (
                    <tr key={workOrder.batchId}>
                      <td className="primary-cell">{workOrder.workOrderNo}</td>
						<td><strong>{workOrder.batchNo}</strong><small>{workOrder.batchType === "FLOW_SPLIT" ? "流转拆分批" : isSingleInitialBatch ? "默认计划批" : "计划生产批"}</small></td>
                      <td>
                        <strong>{workOrder.productName}</strong>
                        <small>{workOrder.productCode} · 材质 {workOrder.productMaterial ?? "未登记"}</small>
                      </td>
                      <td>
                        {routeNames[workOrder.routeType]}
                        <small>{workOrder.routeVersion}</small>
                      </td>
                      <td>
                        <strong>本批投产 {formatQuantity(workOrder.plannedQuantity)} PCS</strong>
                        <small>工单累计 {formatQuantity(plannedTotal)} PCS · 订单需求 {formatQuantity(workOrder.orderQuantity)} PCS</small>
                        <small>{margin.quantity > 0 ? `投产余量 +${formatQuantity(margin.quantity)} PCS（${margin.rate.toFixed(1)}%）` : "按订单数量投产"}</small>
                      </td>
                      <td>
                        <div className="inline-progress">
                          <div className="progress-track"><span style={{ width: `${progress}%` }} /></div>
                          <span>{workOrder.completedTaskCount}/{workOrder.taskCount}</span>
                        </div>
                      </td>
                      <td>{pendingLaunch ? <span className="status-badge">待投产</span> : <StatusBadge value={batchProgress.status} />}<small>当前：{pendingLaunch ? "等待主管确认投产" : batchProgress.operation}</small></td>
					  <td className="actions-cell">{pendingLaunch && <>{isSingleInitialBatch && <button type="button" className="button button-secondary button-small" onClick={() => setMarginTarget(workOrder)}><Gauge aria-hidden="true" />投产余量</button>}{isSingleInitialBatch && <button type="button" className="button button-secondary button-small" onClick={() => setBatchTarget(workOrder)}><Layers3 aria-hidden="true" />设置批次</button>}<button type="button" className="button button-primary button-small" onClick={() => setLaunchTarget(workOrder)}><Play aria-hidden="true" />确认投产</button></>}</td>
                      <td>{formatDate(workOrder.createdAt, true)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
	  {marginTarget && <Modal title={`确认实际投产量 / ${marginTarget.productName}`} description={`订单交付基数为 ${formatQuantity(marginTarget.orderQuantity)} PCS。系统建议按 3% 生产余量投产；只允许在首道派工前调整，订单数量不会改变。`} width="small" onClose={() => !pending && setMarginTarget(null)}><form onSubmit={applyMargin}><Field label="实际投产量" required hint="整数；建议值已按订单数量向上取整"><input name="productionQuantity" type="number" min={marginTarget.orderQuantity} step="1" required defaultValue={Math.ceil(marginTarget.orderQuantity * 1.03)} autoFocus /></Field><SubmitActions pending={pending} submitLabel="确认投产余量" onCancel={() => setMarginTarget(null)} /></form></Modal>}
	  {batchTarget && <Modal title={`设置生产批次 / ${batchTarget.productName}`} description={`实际投产总数 ${formatQuantity(batchTarget.plannedQuantity)} PCS，订单交付基数 ${formatQuantity(batchTarget.orderQuantity)} PCS。拆分后，每个批次必须先确认投产，才会进入首道待派工；任何批次派工、开工或完工后均不可重拆。`} width="small" onClose={() => !pending && setBatchTarget(null)}><form onSubmit={configureBatches}><Field label="各批次数量" required hint="仅支持正整数；以逗号或换行分隔，例如：30, 30, 40"><textarea name="batchQuantities" required rows={4} defaultValue={workOrders.filter((item) => item.id === batchTarget.id).map((item) => item.plannedQuantity).join(", ")} autoFocus /></Field><SubmitActions pending={pending} submitLabel="确认拆分批次" onCancel={() => setBatchTarget(null)} /></form></Modal>}
	  {launchTarget && <Modal title={`确认投产 / ${launchTarget.productName}`} description={`本次确认后，批次 ${launchTarget.batchNo} 才会进入首道待派工；射蜡派工将仅处理员工、模具和派工单，不再改变投产数量。`} width="small" onClose={() => !pending && setLaunchTarget(null)}><form onSubmit={launchBatch}><Field label="本批实际投产数量" required hint={`当前计划 ${formatQuantity(launchTarget.plannedQuantity)} PCS；必须为正整数`}><input name="productionQuantity" type="number" min="1" step="1" required defaultValue={launchTarget.plannedQuantity} autoFocus /></Field>{(plannedTotals.get(launchTarget.id) ?? launchTarget.plannedQuantity) <= launchTarget.orderQuantity && <p className="form-hint"><strong>提醒：</strong>当前全部批次合计未预留生产余量，请确认是否按 1:1 投产。</p>}<SubmitActions pending={pending} submitLabel="确认投产并进入待派工" onCancel={() => setLaunchTarget(null)} /></form></Modal>}
    </>
  );
}
