import { FormEvent, useState } from "react";
import { Camera, CheckCircle2, ScanLine, Truck, Upload } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatQuantity, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import { deviceCode } from "../offlineQueue";
import type { CartTransfer } from "../types";

export function CartTransfersPage({ operatorCode }: { operatorCode: string }) {
  const [receiving, setReceiving] = useState<CartTransfer | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(async () => {
    const [transfers, resources, sources] = await Promise.all([api.cartTransfers.list(), api.resources.list(), api.cartTransfers.readySources()]);
    return { transfers, carts: resources.filter((asset) => asset.assetType === "CARRIER"), sources };
  }, []);

  const readySources = state.data?.sources ?? [];

  async function load(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true); setError(null);
    try {
      await api.cartTransfers.load({
        sourceTaskId: String(form.get("sourceTaskId")),
        cartCode: String(form.get("cartCode")),
        quantity: Number(form.get("quantity")),
        loadPhotoUrl: String(form.get("loadPhotoUrl") || "") || undefined,
        loadedBy: operatorCode,
			operationId: crypto.randomUUID(), deviceCode: deviceCode(), workstationCode: String(form.get("workstationCode") || "") || undefined
      });
      formElement.reset();
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  async function receive(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!receiving) return;
    const form = new FormData(event.currentTarget);
    setSaving(true); setError(null);
    try {
      await api.cartTransfers.receive(receiving.id, {
        receivedQuantity: Number(form.get("receivedQuantity")),
        receivePhotoUrl: String(form.get("receivePhotoUrl") || "") || undefined,
        exceptionReason: String(form.get("exceptionReason") || "") || undefined,
        receivedBy: operatorCode,
			operationId: crypto.randomUUID(), deviceCode: deviceCode(), workstationCode: String(form.get("workstationCode") || "") || undefined
      });
      setReceiving(null); await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  if (state.loading) return <LoadingState label="正在加载周转车与工序流转" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const data = state.data!;
  const pending = data.transfers.filter((transfer) => transfer.status === "LOADED");
  const exceptions = data.transfers.filter((transfer) => transfer.status === "EXCEPTION");

  return <>
    <PageHeader title="周转车流转" description="扫车码装车、到站接收；可混装多个订单，每一件流转均保留来源、去向、数量、人员与可选现场照片。" />
    {error && <ErrorNotice error={error} />}
    <section className="cart-summary" aria-label="周转车流转概况">
      <div><Truck aria-hidden="true" /><span>可用周转车</span><strong>{data.carts.filter((cart) => cart.status === "AVAILABLE").length}</strong></div>
      <div><Upload aria-hidden="true" /><span>运输中</span><strong>{pending.length}</strong></div>
      <div className={exceptions.length ? "attention" : ""}><ScanLine aria-hidden="true" /><span>数量异常</span><strong>{exceptions.length}</strong></div>
    </section>

    <section className="cart-flow-grid">
      <form className="section-block cart-action-form" onSubmit={load}>
        <header><div><span className="eyebrow">步骤 1</span><h2><ScanLine aria-hidden="true" />扫描周转车并装车</h2><p>选择已完成来源工序；数量可小于合格数，支持分批或混装。</p></div></header>
        <div className="form-grid">
          <Field label="周转车编码" required><input name="cartCode" list="cart-codes" placeholder="扫描或输入 CART-MID-01" required autoFocus /></Field>
          <datalist id="cart-codes">{data.carts.map((cart) => <option key={cart.id} value={cart.assetCode}>{cart.assetName} · {cart.status}</option>)}</datalist>
          <Field label="来源工序任务" required><select name="sourceTaskId" required defaultValue=""><option value="" disabled>请选择可流转工序</option>{readySources.map((task) => <option key={task.id} value={task.id}>{task.routeType === "MID_TEMP_WAX" ? "中温蜡" : task.routeType === "LOW_TEMP_WAX" ? "低温蜡" : "砂型"} · {task.orderNo} · {task.productName}{task.productMaterial ? ` · ${task.productMaterial}` : ""} · {task.operationName} · 可装 {formatQuantity(task.availableQuantity)}</option>)}</select></Field>
          <Field label="装车数量" required><input name="quantity" type="number" min="0.001" step="0.001" required /></Field>
				<Field label="操作工位 / 地点" required><input name="workstationCode" maxLength={64} placeholder="例如 MID-WAX-TRANSFER" required /></Field>
          <Field label="现场照片地址"><input name="loadPhotoUrl" type="url" maxLength={1000} placeholder="可选：拍照上传后的地址" /></Field>
        </div>
        <button className="button button-primary" disabled={saving || data.carts.length === 0}><Truck aria-hidden="true" />确认装车并生成流转记录</button>
      </form>

      <section className="section-block cart-receive-panel">
        <header><div><span className="eyebrow">步骤 2</span><h2><CheckCircle2 aria-hidden="true" />到站核对并接收</h2><p>下一工序或主管核对实际数量。数量差异自动标记，生产可继续。</p></div></header>
        {pending.length === 0 ? <EmptyState title="没有待接收的周转车" description="装车后将在这里等待下一工序确认。" /> : <div className="cart-pending-list">{pending.map((transfer) => <article key={transfer.id}>
          <div><strong>{transfer.cartCode}</strong><span>{transfer.sourceOperationName} → {transfer.targetOperationName}</span><small>{transfer.orderNo} · {transfer.productName} · 装载 {formatQuantity(transfer.loadedQuantity)}</small></div>
          <button className="button button-primary button-small" type="button" onClick={() => setReceiving(transfer)}>核对接收</button>
        </article>)}</div>}
      </section>
    </section>

    <section className="section-block">
      <header className="section-heading"><div><h2>物理流转记录</h2><p>每次扫码装卸均保留任务、订单、来源工序与接收结果。</p></div></header>
      {data.transfers.length === 0 ? <EmptyState title="尚无周转车流转记录" description="完成任意一道工序后，使用上方扫码装车即可开始记录。" /> : <div className="table-scroll"><table><thead><tr><th>流转单</th><th>周转车</th><th>产品 / 订单</th><th>工序流向</th><th>装载 / 接收</th><th>状态</th><th>操作</th></tr></thead><tbody>{data.transfers.map((transfer) => <tr key={transfer.id}><td><strong>{transfer.transferNo}</strong><small>{new Date(transfer.loadedAt).toLocaleString()}</small></td><td>{transfer.cartCode}<small>{transfer.cartName}</small></td><td>{transfer.productCode} · {transfer.productName}<small>{transfer.orderNo}</small></td><td>{transfer.sourceOperationName} → {transfer.targetOperationName}</td><td>{formatQuantity(transfer.loadedQuantity)} / {transfer.receivedQuantity == null ? "待核对" : formatQuantity(transfer.receivedQuantity)}</td><td><StatusBadge value={transfer.status} />{transfer.exceptionReason && <small className="exception-note">{transfer.exceptionReason}</small>}</td><td>{transfer.status === "LOADED" ? <button className="button button-secondary button-small" type="button" onClick={() => setReceiving(transfer)}>接收</button> : transfer.loadPhotoUrl || transfer.receivePhotoUrl ? <Camera aria-label="已附现场照片" /> : "-"}</td></tr>)}</tbody></table></div>}
    </section>

    {receiving && <Modal title={`接收 ${receiving.cartCode}`} description={`${receiving.sourceOperationName} → ${receiving.targetOperationName}；装车数量 ${formatQuantity(receiving.loadedQuantity)}。`} width="small" onClose={() => !saving && setReceiving(null)}>
      <form onSubmit={receive}><div className="form-grid"><Field label="实际接收数量" required><input name="receivedQuantity" type="number" min="0" step="0.001" defaultValue={receiving.loadedQuantity} required autoFocus /></Field><Field label="接收工位 / 地点" required><input name="workstationCode" maxLength={64} placeholder="例如 SHELL-RECEIVE" required /></Field><Field label="接收照片地址"><input name="receivePhotoUrl" type="url" maxLength={1000} placeholder="可选：拍照上传后的地址" /></Field><Field label="差异原因"><textarea name="exceptionReason" rows={3} maxLength={500} placeholder="仅数量不一致时填写，例如待判品隔离或现场复点" /></Field></div><SubmitActions pending={saving} submitLabel="确认接收" onCancel={() => setReceiving(null)} /></form>
    </Modal>}
  </>;
}
