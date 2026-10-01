import { FormEvent, useMemo, useState } from "react";
import {
  CheckCircle2,
  ClipboardCheck,
  PackageCheck,
  Plus,
  RefreshCw,
  Send,
  Truck
} from "lucide-react";
import { api } from "../api";
import {
  EmptyState,
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
import type { DeliveryOrder } from "../types";

type Dialog =
  | { kind: "lot" }
  | { kind: "delivery" }
  | { kind: "ship"; delivery: DeliveryOrder }
  | null;

export function FulfillmentPage({ operatorCode }: { operatorCode: string }) {
  const [view, setView] = useState<"deliveries" | "lots">("deliveries");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [lotKeyword, setLotKeyword] = useState("");
  const [deliveryKeyword, setDeliveryKeyword] = useState("");
  const [deliveryStatus, setDeliveryStatus] = useState("");
  const [lotPage, setLotPage] = useState(0);
  const [deliveryPage, setDeliveryPage] = useState(0);
  const [lotPickerKeyword, setLotPickerKeyword] = useState("");
	const [receiptTaskId, setReceiptTaskId] = useState("");
	const [receiptQuantity, setReceiptQuantity] = useState("");
  const lotsState = useAsyncData(() => api.fulfillment.lotPage({ viewerCode: operatorCode, keyword: lotKeyword || undefined, page: lotPage }), [operatorCode, lotKeyword, lotPage]);
  const deliveriesState = useAsyncData(() => api.fulfillment.deliveryPage({ viewerCode: operatorCode, keyword: deliveryKeyword || undefined, status: deliveryStatus || undefined, page: deliveryPage }), [operatorCode, deliveryKeyword, deliveryStatus, deliveryPage]);
  const summaryState = useAsyncData(() => api.fulfillment.summary(operatorCode), [operatorCode]);
	const receiptsState = useAsyncData(() => api.fulfillment.pendingReceipts(operatorCode), [operatorCode]);
  const lotChoicesState = useAsyncData(() => dialog?.kind === "delivery"
    ? api.fulfillment.lotPage({ viewerCode: operatorCode, keyword: lotPickerKeyword || undefined, availability: "AVAILABLE", page: 0, size: 50 })
    : Promise.resolve({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }), [dialog?.kind, operatorCode, lotPickerKeyword]);
  const lots = lotsState.data?.items ?? [];
  const deliveries = deliveriesState.data?.items ?? [];
	const pendingReceipts = receiptsState.data ?? [];
	const selectedReceipt = useMemo(
		() => pendingReceipts.find((receipt) => receipt.taskId === receiptTaskId) ?? pendingReceipts[0] ?? null,
		[pendingReceipts, receiptTaskId]
	);
  const metrics = summaryState.data;

  async function reload() {
		await Promise.all([lotsState.reload(), deliveriesState.reload(), summaryState.reload(), receiptsState.reload(), lotChoicesState.reload()]);
  }

	function openReceiptDialog() {
		const firstReceipt = pendingReceipts[0];
		setReceiptTaskId(firstReceipt?.taskId ?? "");
		setReceiptQuantity(firstReceipt ? String(firstReceipt.receivableQuantity) : "");
		setDialog({ kind: "lot" });
	}

  async function execute(action: () => Promise<unknown>) {
    setSaving(true);
    setError(null);
    try {
      await action();
      setDialog(null);
      await reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  function registerLot(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
		if (!selectedReceipt) {
			setError(new Error("暂无可登记的成品批次"));
			return;
		}
    const form = new FormData(event.currentTarget);
    return execute(() =>
      api.fulfillment.registerLot({
        operationId: crypto.randomUUID(),
			taskId: String(form.get("taskId")),
			quantity: Number(form.get("quantity")),
        warehouseCode: String(form.get("warehouseCode")),
        registeredBy: operatorCode
      })
    );
  }

  function createDelivery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const lot = (lotChoicesState.data?.items ?? []).find((item) => item.id === form.get("lotId"));
    if (!lot) return;
    return execute(() =>
      api.fulfillment.createDelivery({
        orderId: lot.orderId,
        lotId: lot.id,
        quantity: Number(form.get("quantity")),
        recipientName: String(form.get("recipientName")),
        deliveryAddress: String(form.get("deliveryAddress")),
        createdBy: operatorCode
      })
    );
  }

  function ship(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!dialog || dialog.kind !== "ship") return;
    const form = new FormData(event.currentTarget);
    return execute(() =>
      api.fulfillment.transition(dialog.delivery.id, {
        nextStatus: "SHIPPED",
        operatorCode,
        carrier: String(form.get("carrier")),
        trackingNo: String(form.get("trackingNo")),
        note: String(form.get("note") || "")
      })
    );
  }

  function transition(delivery: DeliveryOrder, nextStatus: string) {
    return execute(() =>
      api.fulfillment.transition(delivery.id, {
        nextStatus,
        operatorCode
      })
    );
  }

	if (lotsState.loading || deliveriesState.loading || summaryState.loading || receiptsState.loading) return <LoadingState label="正在加载成品交付数据" />;
	if (lotsState.error || deliveriesState.error || summaryState.error || receiptsState.error) return <ErrorNotice error={lotsState.error ?? deliveriesState.error ?? summaryState.error ?? receiptsState.error} onRetry={() => void reload()} />;
  if (!metrics) return null;

  return (
    <>
      <PageHeader
        title="成品交付"
		description="成品清点完成后登记入库；如已有终检记录，入库数量受终检合格数约束。"
        action={
          <div className="header-actions">
            <button
              className="button button-secondary"
              type="button"
				onClick={openReceiptDialog}
            >
              <PackageCheck aria-hidden="true" />
              成品入库
            </button>
            <button
              className="button button-primary"
              type="button"
              onClick={() => setDialog({ kind: "delivery" })}
              disabled={metrics.availableQuantity <= 0}
            >
              <Plus aria-hidden="true" />
              新建发货单
            </button>
            <button
              className="icon-button"
              type="button"
              onClick={() => void reload()}
              aria-label="刷新交付数据"
              title="刷新"
            >
              <RefreshCw aria-hidden="true" />
            </button>
          </div>
        }
      />
      {error != null && <ErrorNotice error={error} />}

      <section className="fulfillment-metrics" aria-label="交付概况">
        <div>
          <PackageCheck aria-hidden="true" />
          <span>可发库存</span>
          <strong>{formatQuantity(metrics.availableQuantity)}</strong>
        </div>
        <div>
          <ClipboardCheck aria-hidden="true" />
          <span>待发货</span>
          <strong>{metrics.pendingCount}</strong>
        </div>
        <div>
          <Truck aria-hidden="true" />
          <span>运输中</span>
          <strong>{metrics.inTransitCount}</strong>
        </div>
        <div>
          <CheckCircle2 aria-hidden="true" />
          <span>已签收</span>
          <strong>{metrics.deliveredCount}</strong>
        </div>
      </section>

      <div className="filter-bar">
        <div className="segmented-control" role="group" aria-label="交付视图">
          <button
            type="button"
            className={view === "deliveries" ? "active" : ""}
            onClick={() => setView("deliveries")}
          >
            发货单
          </button>
          <button
            type="button"
            className={view === "lots" ? "active" : ""}
            onClick={() => setView("lots")}
          >
            成品批次
          </button>
        </div>
		{view === "deliveries" ? <><input value={deliveryKeyword} onChange={(event) => { setDeliveryKeyword(event.target.value); setDeliveryPage(0); }} placeholder="检索发货单、订单、客户或产品" aria-label="检索发货单" /><select value={deliveryStatus} onChange={(event) => { setDeliveryStatus(event.target.value); setDeliveryPage(0); }} aria-label="按发货状态筛选"><option value="">全部状态</option><option value="DRAFT">待备货</option><option value="PICKED">已备货</option><option value="SHIPPED">运输中</option><option value="DELIVERED">已签收</option></select></> : <input value={lotKeyword} onChange={(event) => { setLotKeyword(event.target.value); setLotPage(0); }} placeholder="检索批次、订单或产品" aria-label="检索成品批次" />}
      </div>

      <section className="section-block">
        {view === "deliveries" ? (
          deliveries.length === 0 ? (
            <EmptyState title="暂无发货单" description="先登记成品批次，再创建客户发货单。" />
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>发货单 / 订单</th>
                    <th>客户 / 收货人</th>
                    <th>产品批次</th>
                    <th>数量</th>
                    <th>物流</th>
                    <th>状态</th>
                    <th className="actions-cell">下一步</th>
                  </tr>
                </thead>
                <tbody>
                  {deliveries.map((delivery) => (
                    <tr key={delivery.id}>
                      <td>
                        <strong>{delivery.deliveryNo}</strong>
                        <small>{delivery.orderNo}</small>
                      </td>
                      <td>
                        <span>{delivery.customerName}</span>
                        <small>{delivery.recipientName}</small>
                      </td>
                      <td>
                        <span>{delivery.productName}</span>
                        <small>{delivery.lotNo}</small>
                      </td>
                      <td>{formatQuantity(delivery.quantity)}</td>
                      <td>
                        {delivery.carrier || "—"}
                        <small>{delivery.trackingNo || "尚未发运"}</small>
                      </td>
                      <td>
                        <StatusBadge value={delivery.status} />
                      </td>
                      <td className="actions-cell">
                        {delivery.status === "DRAFT" && (
                          <button
                            className="button button-secondary button-small"
                            type="button"
                            disabled={saving}
                            onClick={() => transition(delivery, "PICKED")}
                          >
                            <ClipboardCheck aria-hidden="true" />
                            确认备货
                          </button>
                        )}
                        {delivery.status === "PICKED" && (
                          <button
                            className="button button-primary button-small"
                            type="button"
                            onClick={() => setDialog({ kind: "ship", delivery })}
                          >
                            <Send aria-hidden="true" />
                            登记发运
                          </button>
                        )}
                        {delivery.status === "SHIPPED" && (
                          <button
                            className="button button-primary button-small"
                            type="button"
                            disabled={saving}
                            onClick={() => transition(delivery, "DELIVERED")}
                          >
                            <CheckCircle2 aria-hidden="true" />
                            确认签收
                          </button>
                        )}
                        {delivery.status === "DELIVERED" && (
                          <span className="muted">{formatDate(delivery.deliveredAt, true)}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        ) : lots.length === 0 ? (
				  <EmptyState title="暂无成品批次" description="成品清点完成后可在待入库清单中登记成品入库。" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>批次号</th>
                  <th>订单</th>
                  <th>产品</th>
                  <th>入库 / 可发</th>
                  <th>仓库</th>
                  <th>登记信息</th>
                </tr>
              </thead>
              <tbody>
                {lots.map((lot) => (
                  <tr key={lot.id}>
                    <td className="primary-cell">{lot.lotNo}</td>
                    <td>{lot.orderNo}</td>
                    <td>
                      <span>{lot.productName}</span>
                      <small>{lot.productCode}</small>
                    </td>
                    <td>
                      {formatQuantity(lot.quantity)} / {formatQuantity(lot.availableQuantity)}
                    </td>
                    <td>{lot.warehouseCode}</td>
                    <td>
                      {lot.registeredBy}
                      <small>{formatDate(lot.registeredAt, true)}</small>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
	  {view === "deliveries" && (deliveriesState.data?.totalPages ?? 0) > 1 && <nav className="warehouse-pagination" aria-label="发货单分页"><button type="button" className="button button-secondary button-small" disabled={deliveryPage === 0} onClick={() => setDeliveryPage((current) => current - 1)}>上一页</button><span>第 {deliveryPage + 1} / {deliveriesState.data!.totalPages} 页，共 {deliveriesState.data!.totalElements} 单</span><button type="button" className="button button-secondary button-small" disabled={deliveryPage + 1 >= deliveriesState.data!.totalPages} onClick={() => setDeliveryPage((current) => current + 1)}>下一页</button></nav>}
	  {view === "lots" && (lotsState.data?.totalPages ?? 0) > 1 && <nav className="warehouse-pagination" aria-label="成品批次分页"><button type="button" className="button button-secondary button-small" disabled={lotPage === 0} onClick={() => setLotPage((current) => current - 1)}>上一页</button><span>第 {lotPage + 1} / {lotsState.data!.totalPages} 页，共 {lotsState.data!.totalElements} 批</span><button type="button" className="button button-secondary button-small" disabled={lotPage + 1 >= lotsState.data!.totalPages} onClick={() => setLotPage((current) => current + 1)}>下一页</button></nav>}

      {dialog?.kind === "lot" && (
        <Modal
          title="登记成品入库"
			  description="选择已完成成品清点的批次；有终检记录时，数量按终检合格数控制。"
          width="small"
          onClose={() => !saving && setDialog(null)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={registerLot}>
            <div className="form-grid">
				  <Field label="待入库批次" required>
					<select name="taskId" required autoFocus value={selectedReceipt?.taskId ?? ""} onChange={(event) => {
						const receipt = pendingReceipts.find((item) => item.taskId === event.target.value);
						setReceiptTaskId(event.target.value);
						setReceiptQuantity(receipt ? String(receipt.receivableQuantity) : "");
					}}>
					  {pendingReceipts.length === 0 && <option value="">暂无待入库批次</option>}
					  {pendingReceipts.map((receipt) => (
						<option key={receipt.taskId} value={receipt.taskId}>
						  {receipt.orderNo} · {receipt.productName} · {receipt.batchNo} · 待入库 {formatQuantity(receipt.receivableQuantity)}
						</option>
					  ))}
					</select>
				  </Field>
				  {selectedReceipt && <div className="field-readonly" aria-label="待入库批次信息">
					<strong>{selectedReceipt.productName}</strong>
					<span>{selectedReceipt.orderNo} · {selectedReceipt.productCode}{selectedReceipt.productMaterial ? ` · ${selectedReceipt.productMaterial}` : ""}</span>
					<small>{selectedReceipt.qualityInspected ? `终检合格 ${formatQuantity(selectedReceipt.allowedQuantity)}` : `成品清点合格 ${formatQuantity(selectedReceipt.finalCountQuantity)}`} · 已入库 {formatQuantity(selectedReceipt.registeredQuantity)}</small>
				  </div>}
				  <Field label="入库数量" required>
					<input name="quantity" type="number" min="0.001" max={selectedReceipt?.receivableQuantity} step="0.001" value={receiptQuantity || (selectedReceipt ? String(selectedReceipt.receivableQuantity) : "")} onChange={(event) => setReceiptQuantity(event.target.value)} required />
				  </Field>
              <Field label="成品仓库" required>
                <input name="warehouseCode" defaultValue="FG-01" maxLength={64} required />
              </Field>
            </div>
            <SubmitActions
              pending={saving}
              submitLabel="确认入库"
              onCancel={() => setDialog(null)}
            />
          </form>
        </Modal>
      )}

      {dialog?.kind === "delivery" && (
        <Modal
          title="新建发货单"
          description="创建后依次完成备货、发运和签收。"
          width="small"
          onClose={() => !saving && setDialog(null)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={createDelivery}>
            <div className="form-grid">
			  <Field label="检索可发批次"><input value={lotPickerKeyword} onChange={(event) => setLotPickerKeyword(event.target.value)} placeholder="订单、批次或产品" autoFocus /></Field>
              <Field label="成品批次" required>
                <select name="lotId" required autoFocus>
                  <option value="">请选择</option>
				  {(lotChoicesState.data?.items ?? [])
                    .map((lot) => (
                      <option key={lot.id} value={lot.id}>
                        {lot.lotNo} · {lot.orderNo} · 可发 {formatQuantity(lot.availableQuantity)}
                      </option>
                    ))}
                </select>
              </Field>
              <Field label="发货数量" required>
                <input name="quantity" type="number" min="0.001" step="0.001" required />
              </Field>
              <Field label="收货人" required>
                <input name="recipientName" maxLength={100} required />
              </Field>
              <Field label="收货地址" required>
                <textarea name="deliveryAddress" rows={3} maxLength={500} required />
              </Field>
            </div>
            <SubmitActions
              pending={saving}
              submitLabel="创建发货单"
              onCancel={() => setDialog(null)}
            />
          </form>
        </Modal>
      )}

      {dialog?.kind === "ship" && (
        <Modal
          title={`登记发运 · ${dialog.delivery.deliveryNo}`}
          description="填写承运商和运单号后，发货单进入运输中。"
          width="small"
          onClose={() => !saving && setDialog(null)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={ship}>
            <div className="form-grid">
              <Field label="承运商" required>
                <input name="carrier" maxLength={160} required autoFocus />
              </Field>
              <Field label="运单号" required>
                <input name="trackingNo" maxLength={120} required />
              </Field>
              <Field label="备注">
                <textarea name="note" rows={2} maxLength={500} />
              </Field>
            </div>
            <SubmitActions
              pending={saving}
              submitLabel="确认发运"
              onCancel={() => setDialog(null)}
            />
          </form>
        </Modal>
      )}
    </>
  );
}
