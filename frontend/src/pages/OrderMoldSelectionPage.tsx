import { FormEvent, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Boxes, CheckCircle2, RefreshCw } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, Order, OrderLine } from "../types";

type OrderProduct = { order: Order; line: OrderLine };

export function OrderMoldSelectionPage({ user }: { user: AccessUser }) {
	const [searchParams, setSearchParams] = useSearchParams();
	const navigate = useNavigate();
  const state = useAsyncData(async () => {
    const [orders, selections, assets] = await Promise.all([api.orders.list(), api.molds.orderSelections(), api.resources.list()]);
    return { orders, selections, assets };
  }, []);
  const [target, setTarget] = useState<OrderProduct | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

	const products = useMemo(() => (state.data?.orders ?? []).flatMap((order) =>
		order.lines.filter((line) => line.routeType === "MID_TEMP_WAX" || line.routeType === "LOW_TEMP_WAX").map((line) => ({ order, line }))
	), [state.data]);

	useEffect(() => {
		const orderId = searchParams.get("orderId");
		const orderLineId = searchParams.get("orderLineId");
		if (!orderId || !orderLineId || target) return;
		const requested = products.find((item) => item.order.id === orderId && item.line.id === orderLineId);
		if (requested) {
			setTarget(requested);
			const next = new URLSearchParams(searchParams);
			next.delete("orderId");
			next.delete("orderLineId");
			setSearchParams(next, { replace: true });
		}
	}, [products, searchParams, setSearchParams, target]);
  const selections = new Map((state.data?.selections ?? []).map((selection) => [selection.orderLineId, selection]));
  const molds = (state.data?.assets ?? []).filter((asset) => asset.assetType === "MOLD" && asset.status === "AVAILABLE" && asset.moldCustodyStatus === "IN_STOCK");

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!target) return;
    const form = new FormData(event.currentTarget);
    setPending(true); setError(null);
	try {
		await api.molds.selectForOrderLine({ orderId: target.order.id, orderLineId: target.line.id, moldAssetId: String(form.get("moldAssetId")), selectedBy: user.employeeCode });
		setTarget(null); await state.reload();
		const resumeTaskId = searchParams.get("resumeTaskId");
		if (resumeTaskId) {
			const params = new URLSearchParams({ resumeTaskId });
			const productionQuantity = searchParams.get("productionQuantity");
			if (productionQuantity) params.set("productionQuantity", productionQuantity);
			navigate(`/tasks?${params}`);
		}
    } catch (caught) { setError(caught); }
    finally { setPending(false); }
  }

  if (state.loading) return <LoadingState label="正在加载订单模具选择" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  return <>
    <PageHeader title="订单模具选定" description="前台、工程师或生产主管可为订单的每个产品行安排在库模具，或标记为待定制入库/待客户送模；仓管入库时将自动绑定对应产品行。" action={<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新订单模具"><RefreshCw aria-hidden="true" /></button>} />
    {error && <ErrorNotice error={error} />}
    <section className="mold-selection-summary" aria-label="订单模具选择说明">
      <Boxes aria-hidden="true" /><div><strong>一个订单产品行对应一个模具</strong><span>订单可有多个产品行，因此可分别绑定多个模具；模具只有处于在库状态才可选，已在内部使用、对外出库或维修中的模具不会出现在选择列表。</span></div>
    </section>
    <section className="section-block">
      {products.length === 0 ? <EmptyState title="暂无蜡模生产订单" description="前台创建订单并完成工程确认后，可在这里为订单产品选定模具。" /> : <div className="table-scroll"><table><thead><tr><th>订单 / 产品</th><th>产线</th><th>状态</th><th>选定模具</th><th>权属</th><th>选定人</th><th className="actions-cell">操作</th></tr></thead><tbody>
        {products.map(({ order, line }) => {
          const selection = selections.get(line.id);
          const pending = selection && selection.selectionStatus !== "SELECTED";
          return <tr key={line.id}><td><strong>{order.orderNo}</strong><small>{line.productCode} / {line.productName}</small></td><td><StatusBadge value={line.routeType} /></td><td><StatusBadge value={order.status} /></td><td>{pending ? <><StatusBadge value={selection.selectionStatus} /><small>{selection.pendingReason ?? "等待模具入库后自动绑定"}</small></> : selection ? <><strong>{selection.moldAssetCode}</strong><small>{selection.moldAssetName}</small></> : "未安排"}</td><td>{selection?.selectionStatus === "SELECTED" ? selection.moldOwnershipType === "CUSTOMER_OWNED" ? `客户寄存${selection.moldOwnerName ? ` · ${selection.moldOwnerName}` : ""}` : "企业自有" : "-"}</td><td>{selection?.selectedBy ?? "-"}</td><td className="actions-cell"><button className="button button-secondary button-small" type="button" onClick={() => setTarget({ order, line })}>{selection ? "调整模具" : "选定模具"}</button></td></tr>;
        })}
      </tbody></table></div>}
    </section>
    {target && <Modal title={`选定模具 / ${target.line.productName}`} description="可选择企业自有模具或客户寄存模具。选定不会立即出库；射蜡主管派工时再一键出库并交接给射蜡工。" width="small" onClose={() => !pending && setTarget(null)}>
      <form onSubmit={save}><Field label="订单产品"><input value={`${target.order.orderNo} / ${target.line.productCode} / ${target.line.productName}`} readOnly /></Field><Field label="在库模具" required><select name="moldAssetId" required defaultValue={selections.get(target.line.id)?.moldAssetId ?? ""} autoFocus><option value="" disabled>请选择在库模具</option>{molds.map((mold) => <option key={mold.id} value={mold.id}>{mold.assetCode} · {mold.assetName} · 库位 {mold.locationCode ?? "未登记"} · {mold.ownershipType === "CUSTOMER_OWNED" ? `客户寄存${mold.ownerName ? `(${mold.ownerName})` : ""}` : "企业自有"}</option>)}</select></Field><SubmitActions pending={pending} submitLabel="确认选定" onCancel={() => setTarget(null)} /></form>
    </Modal>}
  </>;
}
