import { FormEvent, useState } from "react";
import { ClipboardList, PackageCheck, Plus, RefreshCw, Send, Truck } from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  formatDate,
  formatQuantity,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { OutsourcingOrder, Task } from "../types";

export function OutsourcingPage({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [suppliers, orders, readyTasks] = await Promise.all([
      api.outsourcing.suppliers(),
      api.outsourcing.orders(),
      api.outsourcing.readyProductionTasks(operatorCode)
    ]);
    return { suppliers, orders, readyTasks };
  }, []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [receiving, setReceiving] = useState<OutsourcingOrder | null>(null);
  const [linkedTaskId, setLinkedTaskId] = useState("");

  async function createSupplier(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    setError(null);
    try {
      await api.outsourcing.createSupplier({
        code: String(form.get("code")),
        name: String(form.get("name")),
        contactName: String(form.get("contactName") || ""),
        contactPhone: String(form.get("contactPhone") || "")
      });
      formElement.reset();
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function createOrder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    setError(null);
    try {
      const linkedTask = state.data?.readyTasks.find((task) => task.id === linkedTaskId);
      await api.outsourcing.createOrder({
        orderNo: String(form.get("orderNo")),
        supplierId: String(form.get("supplierId")),
        itemCode: linkedTask?.productCode ?? String(form.get("itemCode")),
        itemName: linkedTask?.productName ?? String(form.get("itemName")),
        quantity: linkedTask?.plannedQuantity ?? Number(form.get("quantity")),
        unit: String(form.get("unit")),
        dueDate: String(form.get("dueDate") || "") || undefined,
        remark: String(form.get("remark") || ""),
        planningTaskId: linkedTask?.id
      });
      formElement.reset();
		setLinkedTaskId("");
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function transition(order: OutsourcingOrder, nextStatus: string, receivedQuantity?: number) {
    setSaving(true);
    setError(null);
    try {
      await api.outsourcing.transition(order.id, {
        nextStatus,
        receivedQuantity,
        operatorCode
      });
      setReceiving(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function receive(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!receiving) return;
    const form = new FormData(event.currentTarget);
    await transition(receiving, "RECEIVED", Number(form.get("receivedQuantity")));
  }

  if (state.loading) return <LoadingState label="正在加载外协台账" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const suppliers = state.data?.suppliers ?? [];
  const orders = state.data?.orders ?? [];
  const readyTasks = state.data?.readyTasks ?? [];
  const linkedTask = readyTasks.find((task) => task.id === linkedTaskId) as Task | undefined;

  return (
    <>
      <PageHeader
        title="外协管理"
        description="供应商、外协发出、进度、分批收货与关闭"
        action={
          <button className="icon-button" type="button" onClick={state.reload} aria-label="刷新">
            <RefreshCw aria-hidden="true" />
          </button>
        }
      />
      {error != null && <ErrorNotice error={error} />}

      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>新建供应商</h2><p>供应商编码不可重复</p></div>
          </header>
          <form className="compact-form" onSubmit={createSupplier}>
            <div className="form-grid">
              <Field label="供应商编码" required><input name="code" required maxLength={64} /></Field>
              <Field label="供应商名称" required><input name="name" required maxLength={160} /></Field>
              <Field label="联系人"><input name="contactName" maxLength={100} /></Field>
              <Field label="联系电话"><input name="contactPhone" maxLength={40} /></Field>
            </div>
            <button className="button button-primary" disabled={saving}><Plus aria-hidden="true" />创建供应商</button>
          </form>
        </div>
        <div className="table-pane">
          <header className="section-heading">
            <div><h2>供应商目录</h2><p>{suppliers.length} 个有效供应商</p></div>
          </header>
          <div className="table-scroll">
            <table>
              <thead><tr><th>编码</th><th>名称</th><th>联系人</th><th>电话</th><th>状态</th></tr></thead>
              <tbody>
                {suppliers.map((supplier) => (
                  <tr key={supplier.id}>
                    <td className="primary-cell">{supplier.code}</td>
                    <td>{supplier.name}</td>
                    <td>{supplier.contactName || "—"}</td>
                    <td>{supplier.contactPhone || "—"}</td>
                    <td><StatusBadge value={supplier.active ? "ACTIVE" : "INACTIVE"} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>新建外协单</h2><p>关联砂型任务后，外协状态会同步推进生产追溯</p></div>
            <Truck aria-hidden="true" />
          </header>
          <form className="compact-form" onSubmit={createOrder}>
            <div className="form-grid">
              <Field label="外协单号" required><input name="orderNo" required maxLength={64} /></Field>
              <Field label="供应商" required>
                <select name="supplierId" required defaultValue="">
                  <option value="" disabled>选择供应商</option>
                  {suppliers.map((supplier) => (
                    <option key={supplier.id} value={supplier.id}>{supplier.code} · {supplier.name}</option>
                  ))}
                </select>
              </Field>
              <Field label="关联砂型生产任务">
                <select value={linkedTaskId} onChange={(event) => setLinkedTaskId(event.target.value)}>
                  <option value="">不关联独立外协单</option>
                  {readyTasks.map((task) => (
                    <option key={task.id} value={task.id}>
                      {task.workOrderNo} · {task.productName} · {task.productMaterial || "未录材质"} · {formatQuantity(task.plannedQuantity)} {task.settlementUnit}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="物料编码" required><input key={`item-code-${linkedTask?.id ?? "manual"}`} name="itemCode" defaultValue={linkedTask?.productCode ?? ""} readOnly={Boolean(linkedTask)} required maxLength={64} /></Field>
              <Field label="物料名称" required><input key={`item-name-${linkedTask?.id ?? "manual"}`} name="itemName" defaultValue={linkedTask?.productName ?? ""} readOnly={Boolean(linkedTask)} required maxLength={160} /></Field>
              <Field label="数量" required><input key={`quantity-${linkedTask?.id ?? "manual"}`} name="quantity" defaultValue={linkedTask?.plannedQuantity ?? ""} readOnly={Boolean(linkedTask)} type="number" min="0.001" step="0.001" required /></Field>
              <Field label="单位" required><input name="unit" defaultValue="PCS" required maxLength={16} /></Field>
              <Field label="交付日期"><input name="dueDate" type="date" /></Field>
              <Field label="备注"><input name="remark" maxLength={500} /></Field>
            </div>
            {linkedTask && (
              <div className="context-strip" role="status">
                <ClipboardList aria-hidden="true" />
                <span>已关联 {linkedTask.taskNo} · {linkedTask.workOrderNo} / {linkedTask.batchNo}。发出与进度会自动写入该批次的外协工序。</span>
              </div>
            )}
            <button className="button button-primary" disabled={saving || suppliers.length === 0}>
              <Plus aria-hidden="true" />创建外协单
            </button>
          </form>
        </div>
        <div className="table-pane">
          <header className="section-heading">
            <div><h2>外协执行台账</h2><p>{orders.length} 张外协订单</p></div>
          </header>
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>外协单</th><th>供应商</th><th>物料</th><th>生产关联</th><th>数量</th><th>交付</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody>
                {orders.map((order) => (
                  <tr key={order.id}>
                    <td className="primary-cell">{order.orderNo}</td>
                    <td><strong>{order.supplierName}</strong><small>{order.supplierCode}</small></td>
                    <td><strong>{order.itemName}</strong><small>{order.itemCode}</small></td>
                    <td>{order.planningTaskId ? <><strong>已关联生产任务</strong><small>{order.planningTaskId}</small></> : <small>独立外协单</small>}</td>
                    <td>
                      <strong>{formatQuantity(order.receivedQuantity)} / {formatQuantity(order.quantity)} {order.unit}</strong>
                      <small>累计收货</small>
                    </td>
                    <td>{formatDate(order.dueDate)}</td>
                    <td><StatusBadge value={order.status} /></td>
                    <td className="actions-cell">
                      {order.status === "DRAFT" && (
                        <button className="button button-secondary button-small" onClick={() => transition(order, "SENT")}>
                          <Send aria-hidden="true" />发出
                        </button>
                      )}
                      {order.status === "SENT" && (
                        <button className="button button-secondary button-small" onClick={() => transition(order, "IN_PROGRESS")}>
                          <Truck aria-hidden="true" />开始执行
                        </button>
                      )}
                      {order.status === "IN_PROGRESS" && (
                        <button className="button button-primary button-small" onClick={() => setReceiving(order)}>
                          <PackageCheck aria-hidden="true" />收货
                        </button>
                      )}
                      {order.status === "RECEIVED" && (
							<>
								{order.planningTaskId && <small>请先完成来料检验</small>}
								<button className="button button-secondary button-small" onClick={() => transition(order, "CLOSED")}>
									<PackageCheck aria-hidden="true" />关闭
								</button>
							</>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {receiving && (
        <Modal
          title={`外协收货 · ${receiving.orderNo}`}
          description={`剩余 ${formatQuantity(receiving.quantity - receiving.receivedQuantity)} ${receiving.unit}`}
          width="small"
          onClose={() => !saving && setReceiving(null)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={receive}>
            <Field label="本次收货数量" required>
              <input
                name="receivedQuantity"
                type="number"
                min="0.001"
                step="0.001"
                max={receiving.quantity - receiving.receivedQuantity}
                required
                autoFocus
              />
            </Field>
            <SubmitActions pending={saving} submitLabel="确认收货" onCancel={() => setReceiving(null)} />
          </form>
        </Modal>
      )}
    </>
  );
}
