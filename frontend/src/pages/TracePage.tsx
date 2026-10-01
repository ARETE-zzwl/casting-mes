import { useEffect, useState } from "react";
import { ClipboardCheck, Factory, Search, Send, Timer } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { api } from "../api";
import {
  EmptyState,
  ErrorNotice,
  formatDate,
  formatQuantity,
  LoadingState,
  PageHeader,
  StatusBadge
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { Order, OrderTrace, Task } from "../types";

const eventIcons: Record<string, typeof Factory> = {
  ORDER_CREATED: Factory,
  ORDER_APPROVED: ClipboardCheck,
  ORDER_RELEASED: Send,
  WORK_ORDER_CREATED: Factory,
  TASK_STARTED: Timer,
  PRODUCTION_REPORTED: ClipboardCheck,
  TASK_COMPLETED: ClipboardCheck
};

export function TracePage() {
  const [params, setParams] = useSearchParams();
  const orderId = params.get("orderId") || "";
  const ordersState = useAsyncData(async () => {
    const [orders, tasks] = await Promise.all([api.orders.list(), api.tasks.list()]);
    return { orders, tasks };
  }, []);
  const [trace, setTrace] = useState<OrderTrace | null>(null);
  const [loadingTrace, setLoadingTrace] = useState(false);
  const [traceError, setTraceError] = useState<unknown>(null);
  const [customerFilter, setCustomerFilter] = useState("");
  const [orderFilter, setOrderFilter] = useState("");

  useEffect(() => {
    if (!orderId) {
      setTrace(null);
      return;
    }
    setLoadingTrace(true);
    setTraceError(null);
    api.trace
      .order(orderId)
      .then(setTrace)
      .catch(setTraceError)
      .finally(() => setLoadingTrace(false));
  }, [orderId]);

  if (ordersState.loading) return <LoadingState label="正在加载订单目录" />;
  if (ordersState.error) {
    return <ErrorNotice error={ordersState.error} onRetry={ordersState.reload} />;
  }

  const orders = ordersState.data?.orders ?? [];
  const completedOrderIds = completedOrders(ordersState.data?.tasks ?? []);
  const customers = Array.from(new Map(orders.map((order) => [order.customerId, {
    id: order.customerId,
    label: `${order.customerCode} · ${order.customerName}`
  }])).values());
  const normalizedOrderFilter = orderFilter.trim().toLowerCase();
  const filteredOrders = orders.filter((order) => {
    const matchesCustomer = !customerFilter || order.customerId === customerFilter;
    const searchable = [order.orderNo, order.customerCode, order.customerName,
      ...order.lines.flatMap((line) => [line.productCode, line.productName])].join(" ").toLowerCase();
    return matchesCustomer && (!normalizedOrderFilter || searchable.includes(normalizedOrderFilter));
  });
  const productTraces = trace ? trace.order.lines.map((line) => ({
    line,
    workOrders: trace.workOrders.filter(({ workOrder }) => workOrder.orderLineId === line.id)
  })) : [];
  return (
    <>
      <PageHeader title="订单追溯" description="从客户订单查看工单、任务与报工事实" />

      <section className="trace-search trace-search-detailed">
        <Search aria-hidden="true" />
        <label>
          <span>客户</span>
          <select value={customerFilter} onChange={(event) => {
            setCustomerFilter(event.target.value);
            const currentOrder = orders.find((order) => order.id === orderId);
            if (currentOrder && event.target.value && currentOrder.customerId !== event.target.value) setParams({});
          }}>
            <option value="">全部客户</option>
            {customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.label}</option>)}
          </select>
        </label>
        <label>
          <span>订单 / 产品</span>
          <input value={orderFilter} onChange={(event) => setOrderFilter(event.target.value)} placeholder="订单号、产品名称或产品编号" />
        </label>
        <label>
          <span>详细选择订单</span>
          <select value={orderId} onChange={(event) => {
            const next = event.target.value;
            setParams(next ? { orderId: next } : {});
          }}>
            <option value="">请选择订单</option>
            {filteredOrders.map((order) => <option key={order.id} value={order.id}>{order.orderNo} · {order.customerName} · {order.lines.map((line) => line.productName).join("、")}</option>)}
          </select>
        </label>
      </section>

      <section className="trace-order-candidates" aria-label="可选择订单">
        <header><div><h2>{customerFilter || normalizedOrderFilter ? "匹配订单" : "最近订单"}</h2><p>{customerFilter || normalizedOrderFilter ? "按当前筛选条件选择订单。" : "不筛选时按最近创建顺序展示，点击即可查看完整追溯。"}</p></div><span>{filteredOrders.length} 张</span></header>
        {filteredOrders.length === 0 ? <EmptyState title="未找到匹配订单" description="请调整客户或订单/产品检索条件。" /> : <div className="trace-order-candidate-list">{filteredOrders.slice(0, 8).map((order) => <button key={order.id} type="button" className={`${orderTone(order, completedOrderIds)}${order.id === orderId ? " active" : ""}`} onClick={() => setParams({ orderId: order.id })}><div><strong>{order.orderNo}</strong><span>{order.customerName} · {order.customerCode}</span><small>{order.lines.map((line) => `${line.productName} · ${line.productMaterial ?? "材质未登记"} x ${formatQuantity(line.orderedQuantity)} ${line.unit}`).join("；")}</small></div><div className="trace-order-candidate-status"><StatusBadge value={order.priority} /><StatusBadge value={completedOrderIds.has(order.id) ? "COMPLETED" : order.status} /></div></button>)}</div>}
      </section>

      {traceError && <ErrorNotice error={traceError} />}
      {loadingTrace && <LoadingState label="正在构建追溯链" />}
      {!orderId && !loadingTrace && (
        <EmptyState
          title="选择一张订单"
          description="系统会展示订单、工单、生产批次、任务和不可变报工流水。"
        />
      )}

      {trace && !loadingTrace && (
        <div className="trace-layout">
          <section className="section-block">
            <header className="trace-order-header">
              <div>
                <span>客户订单</span>
                <h2>{trace.order.orderNo}</h2>
                <p>{trace.order.customerName} · {trace.order.customerCode}</p>
              </div>
              <StatusBadge value={trace.order.status} />
            </header>

            {trace.workOrders.length === 0 ? (
              <EmptyState title="订单尚未放行" description="审批并放行后将生成工单任务链。" />
            ) : (
              <div className="product-trace-tree">
                {productTraces.map(({ line, workOrders }) => {
                  const tasks = workOrders.flatMap((item) => item.tasks);
                  const completed = tasks.filter(({ task }) => task.status === "COMPLETED").length;
                  return <article className="product-trace-branch" key={line.id}>
                    <header>
                      <div><span>订单产品 {line.lineNo}</span><h3>{line.productName}</h3><p>{line.productCode} · 材质：{line.productMaterial ?? "未登记"} · {formatQuantity(line.orderedQuantity)} {line.unit} · {line.routeType === "MID_TEMP_WAX" ? "中温蜡线" : line.routeType === "LOW_TEMP_WAX" ? "低温蜡线" : "砂型外协线"}</p></div>
                      <div className="product-trace-progress"><strong>{completed}/{tasks.length}</strong><small>已完成工序</small></div>
                    </header>
                    {workOrders.length === 0 ? <p className="product-trace-empty">尚未生成生产工单。</p> : <div className="route-list">
                      {workOrders.map(({ workOrder, tasks: batchTasks }) => <details key={workOrder.batchId} open>
                        <summary><div><strong>{workOrder.batchNo}</strong><span>{workOrder.workOrderNo} · 计划 {formatQuantity(workOrder.plannedQuantity)} PCS</span></div><StatusBadge value={workOrder.status} /></summary>
                        <ol>{batchTasks.map(({ task, reports }) => <li key={task.id} className={`route-step route-${task.status.toLowerCase()}`}>
                          <span className="step-index">{task.sequenceNo}</span><div><strong>{task.operationName}</strong><small>{task.taskNo}</small></div>
                          <div className="step-quantity"><span>合格 {formatQuantity(task.goodQuantity)}</span><span>报废 {formatQuantity(task.scrapQuantity)}</span></div>
                          <StatusBadge value={task.status} />{reports.length > 0 && <small className="report-count">{reports.length} 笔报工</small>}
                        </li>)}</ol>
                      </details>)}
                    </div>}
                  </article>;
                })}
              </div>
            )}
          </section>

          <aside className="section-block timeline-panel">
            <header className="section-heading">
              <div><h2>业务时间线</h2><p>{trace.timeline.length} 条已确认事实</p></div>
            </header>
            <ol className="timeline">
              {trace.timeline.map((event, index) => {
                const Icon = eventIcons[event.type] ?? Timer;
                return (
                  <li key={`${event.occurredAt}-${event.type}-${index}`}>
                    <span className="timeline-icon"><Icon aria-hidden="true" /></span>
                    <div>
                      <strong>{event.summary}</strong>
                      <span>{event.reference}</span>
                      <time>{formatDate(event.occurredAt, true)}</time>
                    </div>
                  </li>
                );
              })}
            </ol>
          </aside>
        </div>
      )}
    </>
  );
}

function completedOrders(tasks: Task[]) {
  const byOrder = new Map<string, Task[]>();
  tasks.forEach((task) => byOrder.set(task.orderId, [...(byOrder.get(task.orderId) ?? []), task]));
  return new Set([...byOrder.entries()]
    .filter(([, orderTasks]) => orderTasks.length > 0 && orderTasks.every((task) => task.status === "COMPLETED"))
    .map(([orderId]) => orderId));
}

function orderTone(order: Order, completedOrderIds: Set<string>) {
  if (order.priority === "URGENT") return "order-row-urgent";
  if (completedOrderIds.has(order.id)) return "order-row-completed";
  if (order.status === "RELEASED") return "order-row-released";
  return "";
}
