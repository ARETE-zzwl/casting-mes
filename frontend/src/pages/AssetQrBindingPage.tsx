import { FormEvent, useMemo, useState } from "react";
import { Link2, PackagePlus, ScanLine } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser } from "../types";

export function AssetQrBindingPage({ operatorCode, user }: { operatorCode: string; user: AccessUser }) {
  const canReceiveMold = user.permissions.some((permission) => ["MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE"].includes(permission));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState<string | null>(null);
	const [receiving, setReceiving] = useState(false);
	const [receiptOrderLineId, setReceiptOrderLineId] = useState("");
	const [receiptOwnership, setReceiptOwnership] = useState<"COMPANY_OWNED" | "CUSTOMER_OWNED">("COMPANY_OWNED");
	const [receiptScannedValue, setReceiptScannedValue] = useState("");
  const state = useAsyncData(async () => {
    const [labels, assets, moldSelections, locations] = await Promise.all([
      api.assetQrs.list(), api.resources.list(), canReceiveMold ? api.molds.orderSelections() : Promise.resolve([]), canReceiveMold ? api.molds.locations() : Promise.resolve([])
    ]);
    return { labels, assets: assets.filter((asset) => asset.assetType === "CARRIER" || canReceiveMold && asset.assetType === "MOLD"), moldSelections, locations };
  }, [operatorCode, canReceiveMold]);
	const unbound = useMemo(() => (state.data?.labels ?? []).filter((label) => label.status === "UNBOUND" && (canReceiveMold || label.intendedAssetType === "CARRIER")), [state.data, canReceiveMold]);
	const pendingMoldPlans = useMemo(() => (state.data?.moldSelections ?? []).filter((selection) => selection.selectionStatus !== "SELECTED"), [state.data]);
	const receiptPlan = pendingMoldPlans.find((selection) => selection.orderLineId === receiptOrderLineId);
	const receiptLabel = useMemo(() => {
		const value = receiptScannedValue.trim().replace(/^MES:ASSET_QR:/i, "").toUpperCase();
		if (!value) return null;
		return (state.data?.labels ?? []).find((label) => label.qrToken.toUpperCase() === value || label.labelNo.toUpperCase() === value) ?? null;
	}, [receiptScannedValue, state.data]);
	const receiptScanStatus = !receiptScannedValue.trim()
		? null
		: !receiptLabel ? { tone: "error", text: "未识别该预生成标签，请检查扫描内容或先由管理员生成标签。" }
			: receiptLabel.intendedAssetType !== "MOLD" ? { tone: "error", text: "该标签属于周转车，不能绑定到模具。" }
				: receiptLabel.status !== "UNBOUND" ? { tone: "error", text: `该标签已绑定到 ${receiptLabel.assetCode ?? "其他资产"}，请改用补打。` }
					: { tone: "success", text: `${receiptLabel.labelNo} 已识别，保存后将绑定到本次入库模具。` };

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true); setError(null); setSuccess(null);
    try {
      const label = await api.assetQrs.bind({ scannedValue: String(form.get("scannedValue")), assetId: String(form.get("assetId")), boundBy: operatorCode });
      setSuccess(`${label.labelNo} 已绑定到 ${label.assetCode}`); formElement.reset(); await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

	async function receiveMold(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		setReceiving(true); setError(null); setSuccess(null);
		try {
			const mold = await api.molds.receive({ scannedValue: String(form.get("scannedValue") || "") || undefined, assetCode: String(form.get("assetCode") || "") || undefined, assetName: String(form.get("assetName")), locationCode: String(form.get("locationCode")), ownershipType: receiptOwnership, ownerName: String(form.get("ownerName") || "") || undefined, operatorCode, orderLineId: receiptOrderLineId || undefined });
			setSuccess(`${mold.assetCode} 已入库至 ${mold.locationCode ?? "模具仓"}${receiptPlan ? `，并已绑定 ${receiptPlan.orderNo} / ${receiptPlan.productName}` : ""}${form.get("scannedValue") ? "，二维码已绑定" : ""}`); formElement.reset(); setReceiptScannedValue(""); setReceiptOrderLineId(""); setReceiptOwnership("COMPANY_OWNED"); await state.reload();
		} catch (caught) { setError(caught); } finally { setReceiving(false); }
	}

  if (state.loading) return <LoadingState label="正在加载待绑定二维码" />;
  if (state.error || !state.data) return <ErrorNotice error={state.error ?? new Error("二维码数据不可用")} onRetry={state.reload} />;
  return <>
    <PageHeader title={canReceiveMold ? "模具扫码建档与绑定" : "周转车扫码绑定"} description={canReceiveMold ? "模具仓管和系统管理员可直接扫描预生成标签，录入模具资料并一次完成入库、二维码绑定和订单关联。" : "扫描预生成周转车标签并选择对应实物资产。"} />
    {error && <ErrorNotice error={error} />}
    {success && <div className="success-notice" role="status">{success}</div>}
    {canReceiveMold && (
    <section className="section-block qr-bind-panel"><header><PackagePlus aria-hidden="true" /><div><h2>扫码新建模具并绑定</h2><p>先扫描模具预生成标签，再登记模具资料；系统自动分配空库位，也可手动指定。</p></div></header><form onSubmit={receiveMold}><div className="form-grid"><Field label="模具二维码" hint="扫描预生成模具标签；未贴码时可先入库，后续再补绑"><input name="scannedValue" value={receiptScannedValue} onChange={(event) => setReceiptScannedValue(event.target.value)} placeholder="扫描 MES:ASSET_QR:... 或 QRL- 标签号" autoFocus /></Field><Field label="模具编码" hint="留空自动生成"><input name="assetCode" maxLength={64} /></Field><Field label="模具名称" required><input name="assetName" required maxLength={160} /></Field><Field label="入库库位" hint="默认分配当前空库位；可改为指定可用库位"><select name="locationCode" defaultValue=""><option value="">自动分配空库位</option>{state.data.locations.filter((location) => location.active && location.occupiedCount < location.capacity).map((location) => <option key={location.locationCode} value={location.locationCode}>{location.locationCode} · {location.locationName}（余 {location.capacity - location.occupiedCount}）</option>)}</select></Field><Field label="模具权属" required><select value={receiptOwnership} disabled={receiptPlan?.selectionStatus === "CUSTOMER_DELIVERY_PENDING"} onChange={(event) => setReceiptOwnership(event.target.value as "COMPANY_OWNED" | "CUSTOMER_OWNED")}><option value="COMPANY_OWNED">企业自有</option><option value="CUSTOMER_OWNED">客户寄存</option></select></Field><Field label="客户名称 / 所有方"><input name="ownerName" maxLength={160} value={receiptPlan?.selectionStatus === "CUSTOMER_DELIVERY_PENDING" ? receiptPlan.customerName : undefined} readOnly={receiptPlan?.selectionStatus === "CUSTOMER_DELIVERY_PENDING"} /></Field><Field label="关联待入库订单产品" hint="选择后保存即完成该订单产品的模具绑定。"><select value={receiptOrderLineId} onChange={(event) => { const next = event.target.value; const plan = pendingMoldPlans.find((item) => item.orderLineId === next); setReceiptOrderLineId(next); setReceiptOwnership(plan?.selectionStatus === "CUSTOMER_DELIVERY_PENDING" ? "CUSTOMER_OWNED" : "COMPANY_OWNED"); }}><option value="">普通入库，不关联订单</option>{pendingMoldPlans.map((plan) => <option key={plan.orderLineId} value={plan.orderLineId}>{plan.selectionStatus === "CUSTOMER_DELIVERY_PENDING" ? "待客户送模" : "待定制入库"} · {plan.orderNo} · {plan.productCode} {plan.productName}</option>)}</select></Field></div>{receiptScanStatus && <div className={receiptScanStatus.tone === "success" ? "success-notice" : "error-notice"} role="status">{receiptScanStatus.text}</div>}<SubmitActions pending={receiving} submitLabel="入库并绑定二维码" onCancel={() => history.back()} /></form></section>
    )}
    <section className="section-block qr-bind-panel"><header><ScanLine aria-hidden="true" /><div><h2>已有资产扫码绑定</h2><p>绑定后请再扫一次做现场核对。</p></div></header>{unbound.length === 0 ? <EmptyState title="没有待绑定标签" description="请由管理员先批量预生成二维码。" /> : <form onSubmit={submit}><div className="form-grid"><Field label="扫描结果" required hint="支持 MES:ASSET_QR: 二维码内容或 QRL- 开头的标签号"><input name="scannedValue" placeholder="扫描二维码或输入标签号" required /></Field><Field label="绑定资产" required><select name="assetId" defaultValue=""><option value="" disabled>请选择实物资产</option>{state.data.assets.map((asset) => <option key={asset.id} value={asset.id}>{asset.assetType === "MOLD" ? "模具" : "周转车"} · {asset.assetCode} · {asset.assetName}</option>)}</select></Field></div><SubmitActions pending={saving} submitLabel="确认绑定" onCancel={() => history.back()} /></form>}</section>
    <section className="section-block"><header className="section-heading"><div><h2>待绑定标签</h2><p>请按类型选择相同资产，避免模具与周转车标签混用。</p></div></header><div className="label-chip-list">{unbound.map((label) => <span key={label.id}><StatusBadge value={label.intendedAssetType} /> {label.labelNo}</span>)}</div></section>
  </>;
}
