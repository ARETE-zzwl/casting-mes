import { ArrowUpRight, RefreshCw, RotateCcw, Wrench } from "lucide-react";
import { FormEvent, useState } from "react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions, formatDate } from "../components/ui";
import { useAsyncData } from "../hooks";
import { ProductMoldRelationsPanel } from "../components/ProductMoldRelationsPanel";
import { MoldLocationPanel } from "../components/MoldLocationPanel";
import type { AccessUser, MoldLifecycle, MoldMaintenanceRecord } from "../types";

export function MoldRequestsPage({ operatorCode, user }: { operatorCode: string; user: AccessUser }) {
	const canMaintainWarehouse = user.permissions.some((permission) => ["MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE"].includes(permission));
	const state = useAsyncData(async () => {
		const [requests, assets, externalMovements, overdue, lifecycle, locations] = await Promise.all([api.molds.list(), api.resources.list(), canMaintainWarehouse ? api.molds.externalMovements() : Promise.resolve([]), canMaintainWarehouse ? api.molds.maintenanceOverdue() : Promise.resolve([]), canMaintainWarehouse ? api.molds.lifecycle() : Promise.resolve([]), canMaintainWarehouse ? api.molds.locationSuggestions() : Promise.resolve([])]);
		return { requests, assets, externalMovements, overdue, lifecycle, locations };
	}, [operatorCode, canMaintainWarehouse]);
	const [pendingId, setPendingId] = useState<string | null>(null);
	const [actionError, setActionError] = useState<unknown>(null);
	const [externalOpen, setExternalOpen] = useState(false);
	const [savingExternal, setSavingExternal] = useState(false);
	const [receiptOpen, setReceiptOpen] = useState(false);
	const [savingReceipt, setSavingReceipt] = useState(false);
	const [receiptLocationMode, setReceiptLocationMode] = useState<"AUTO" | "MANUAL">("AUTO");
	const [receiptLocation, setReceiptLocation] = useState("");
	const [lifecycleMold, setLifecycleMold] = useState<MoldLifecycle | null>(null);
	const [lifecycleHistory, setLifecycleHistory] = useState<MoldMaintenanceRecord[]>([]);
	const [lifecycleMode, setLifecycleMode] = useState<"CONFIGURE" | "RECORD" | "LOCK" | "UNLOCK">("CONFIGURE");
	const [savingLifecycle, setSavingLifecycle] = useState(false);

	async function issue(requestId: string, recipientWorkerCode: string | null) {
		setPendingId(requestId);
		setActionError(null);
		try {
			await api.molds.issue(requestId, "MOLD-01", operatorCode, recipientWorkerCode ?? undefined);
			await state.reload();
		} catch (caught) {
			setActionError(caught);
		} finally {
			setPendingId(null);
		}
	}

	async function checkOutExternally(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!user.permissions.includes("MOLD_ISSUE")) { setActionError(new Error("当前角色仅可办理模具入库")); return; }
		const form = new FormData(event.currentTarget);
		setSavingExternal(true); setActionError(null);
		try {
			await api.molds.checkOutExternally({ moldAssetId: String(form.get("moldAssetId")), reasonCode: String(form.get("reasonCode")) as "CUSTOMER_RECALL" | "MAINTENANCE" | "OTHER", reasonNote: String(form.get("reasonNote") || ""), counterpartyName: String(form.get("counterpartyName") || ""), expectedReturnDate: String(form.get("expectedReturnDate") || "") || undefined, operatorCode });
			setExternalOpen(false); await state.reload();
		} catch (caught) { setActionError(caught); } finally { setSavingExternal(false); }
	}

	async function returnFromExternal(id: string) {
		setPendingId(id); setActionError(null);
		try { await api.molds.returnFromExternal(id, operatorCode); await state.reload(); }
		catch (caught) { setActionError(caught); } finally { setPendingId(null); }
	}

	async function receiveMold(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const form = new FormData(event.currentTarget);
		const ownershipType = String(form.get("ownershipType")) as "COMPANY_OWNED" | "CUSTOMER_OWNED";
		setSavingReceipt(true); setActionError(null);
		try {
			await api.molds.receive({
				assetCode: String(form.get("assetCode") || "") || undefined,
				assetName: String(form.get("assetName")),
				locationCode: receiptLocationMode === "AUTO" ? undefined : String(form.get("locationCode")),
				ownershipType,
				ownerName: ownershipType === "CUSTOMER_OWNED" ? String(form.get("ownerName") || "") || undefined : undefined,
				operatorCode
			});
			setReceiptOpen(false); await state.reload();
		} catch (caught) { setActionError(caught); } finally { setSavingReceipt(false); }
	}

	async function uploadMoldImage(moldId: string, file: File) {
		setPendingId(moldId); setActionError(null);
		try {
			const uploaded = await api.files.upload("MOLD_IMAGE", file);
			await api.resources.updateMoldImage(moldId, uploaded.url);
			await state.reload();
		} catch (caught) { setActionError(caught); } finally { setPendingId(null); }
	}

	async function openLifecycle(mold: MoldLifecycle, mode: "CONFIGURE" | "RECORD" | "LOCK" | "UNLOCK") {
		if (!user.permissions.includes("MOLD_ISSUE")) { setActionError(new Error("当前角色仅可办理模具入库")); return; }
		setLifecycleMold(mold); setLifecycleMode(mode); setActionError(null);
		try { setLifecycleHistory(await api.molds.history(mold.id)); } catch (caught) { setActionError(caught); }
	}

	async function saveLifecycle(event: FormEvent<HTMLFormElement>) {
		event.preventDefault(); if (!lifecycleMold) return;
		const form = new FormData(event.currentTarget); setSavingLifecycle(true); setActionError(null);
		try {
			if (lifecycleMode === "CONFIGURE") await api.molds.configure(lifecycleMold.id, { maintenanceIntervalDays: Number(form.get("maintenanceIntervalDays")) || undefined, nextMaintenanceDate: String(form.get("nextMaintenanceDate") || "") || undefined });
			if (lifecycleMode === "RECORD") await api.molds.recordMaintenance(lifecycleMold.id, { recordType: String(form.get("recordType")) as "REPAIR" | "MAINTENANCE" | "INSPECTION", description: String(form.get("description")), serviceProvider: String(form.get("serviceProvider") || "") || undefined, performedBy: operatorCode, nextMaintenanceDate: String(form.get("nextMaintenanceDate") || "") || undefined });
			if (lifecycleMode === "LOCK") await api.molds.lock(lifecycleMold.id, String(form.get("reason")), operatorCode);
			if (lifecycleMode === "UNLOCK") await api.molds.unlock(lifecycleMold.id, String(form.get("resolutionNote")), operatorCode);
			setLifecycleMold(null); await state.reload();
		} catch (caught) { setActionError(caught); } finally { setSavingLifecycle(false); }
	}
  if (state.loading) return <LoadingState label="正在加载模具申请" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
	const requests = state.data?.requests ?? [];
	const canReceive = user.permissions.includes("MOLD_RECEIVE") || user.permissions.includes("MOLD_ISSUE");
	const canIssue = user.permissions.includes("MOLD_ISSUE");
	const canManageMoldWarehouse = user.permissions.includes("MOLD_WAREHOUSE_MANAGE");
	const inStockMolds = (state.data?.assets ?? []).filter((asset) => asset.assetType === "MOLD" && asset.status === "AVAILABLE" && asset.moldCustodyStatus === "IN_STOCK");
	const moldsById = new Map((state.data?.assets ?? []).filter((asset) => asset.assetType === "MOLD").map((asset) => [asset.id, asset]));
	const locations = state.data?.locations ?? [];

  return (
    <>
      <PageHeader
        title="模具申请与领用"
        description="主管申请，工程确认，模具仓出入库；自有模具与客户寄存模具分别记录并全程追溯。"
		action={<div className="header-actions">{canReceive && <button className="button button-primary" type="button" onClick={() => setReceiptOpen(true)}>模具入库</button>}{canIssue && <button className="button button-secondary" type="button" onClick={() => setExternalOpen(true)}><ArrowUpRight aria-hidden="true" />对外出库</button>}<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新模具申请"><RefreshCw aria-hidden="true" /></button></div>}
      />
			{actionError && <ErrorNotice error={actionError} onRetry={() => setActionError(null)} />}
      <section className="section-block">
        {requests.length === 0 ? (
          <EmptyState title="暂无模具申请" description="低温蜡订单投产前，请先由主管提交对应产品的模具申请。" />
        ) : (
          <div className="table-scroll"><table><thead><tr>
            <th>申请单</th><th>产品</th><th>模具资产</th><th>权属</th><th>申请人</th><th>接收射蜡工</th><th>工程确认</th><th>模具仓</th><th>状态</th><th>申请时间</th><th>操作</th>
          </tr></thead><tbody>{requests.map((request) => (
            <tr key={request.id}>
              <td><strong>{request.requestNo}</strong></td><td>{request.productCode}</td>
					<td>{request.moldAssetId ? <><strong>{moldsById.get(request.moldAssetId)?.assetCode ?? request.moldAssetId}</strong><small>{moldsById.get(request.moldAssetId)?.assetName ?? "模具资产"} · 库位 {moldsById.get(request.moldAssetId)?.locationCode ?? "未登记"}</small></> : "待定制"}</td>
              <td>{request.moldOwnershipType === "CUSTOMER_OWNED" ? `客户寄存：${request.moldOwnerName}` : request.moldOwnershipType === "COMPANY_OWNED" ? "企业自有" : "定制时确认"}</td>
              <td>{request.requestedBy}</td>
					<td>{request.issuedToWorkerCode ?? "-"}</td>
					<td>{request.engineerCode?.startsWith("DEFAULT_PROCESS:") ? `默认工艺受控放行 · ${request.engineerCode.replace("DEFAULT_PROCESS:", "")}` : request.engineerCode ?? "待确认"}</td><td>{request.warehouseCode ?? "未出库"}</td>
              <td><StatusBadge value={request.status} /></td><td>{formatDate(request.requestedAt, true)}</td>
					<td>{canIssue && request.status === "APPROVED" && request.waxTaskId ? <button className="button secondary" type="button" disabled={pendingId === request.id} onClick={() => issue(request.id, request.issuedToWorkerCode)}>{pendingId === request.id ? "出库中" : "出库交接"}</button> : "-"}</td>
            </tr>
          ))}</tbody></table></div>
        )}
      </section>
		<section className="section-block">
			<header className="section-heading"><div><h2>模具保养与异常锁定</h2><p>保养、维修和异常锁定均保留履历；异常锁定后不可领用、出库或派工。</p></div><Wrench aria-hidden="true" /></header>
			<div className="table-scroll"><table><thead><tr><th>模具名称</th><th>模具图</th><th>保养计划</th><th>异常</th><th>操作</th></tr></thead><tbody>{(state.data?.lifecycle ?? []).map((mold) => { const asset = moldsById.get(mold.id); return <tr key={mold.id}><td className="mold-name-cell"><strong>{mold.assetName}</strong><small>{mold.assetCode} · 库位 {mold.locationCode ?? "未登记"}</small></td><td>{asset?.moldImageUrl ? <a className="text-link" href={asset.moldImageUrl} target="_blank" rel="noreferrer">查看</a> : "未上传"}<label className="text-link"><input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" disabled={pendingId === mold.id} onChange={(event) => { const file = event.target.files?.[0]; if (file) void uploadMoldImage(mold.id, file); }} />{pendingId === mold.id ? "上传中" : "补录图片"}</label></td><td>{mold.maintenanceIntervalDays ? `每 ${mold.maintenanceIntervalDays} 天` : "未设周期"}<small>{mold.nextMaintenanceDate ? `下次 ${formatDate(mold.nextMaintenanceDate)}` : "未设日期"}</small></td><td>{mold.lockReason ?? "正常"}</td><td><button className="button button-secondary button-small" type="button" onClick={() => void openLifecycle(mold, "CONFIGURE")}>保养设置</button> <button className="button button-secondary button-small" type="button" onClick={() => void openLifecycle(mold, "RECORD")}>维修/保养</button> <button className="button button-secondary button-small" type="button" onClick={() => void openLifecycle(mold, mold.lockReason ? "UNLOCK" : "LOCK")}>{mold.lockReason ? "解除锁定" : "异常锁定"}</button></td></tr>; })}</tbody></table></div>
		</section>
		<section className="section-block">
			<header className="section-heading"><div><h2>对外出库与维修跟进</h2><p>客户召回、维修保养均保留预计返库日期；维修逾期会标记提醒。</p></div><Wrench aria-hidden="true" /></header>
			{state.data && state.data.overdue.length > 0 && <div className="error-notice"><strong>维修逾期 {state.data.overdue.length} 件</strong><span>{state.data.overdue.map((item) => `${item.moldAssetCode} 应于 ${formatDate(item.expectedReturnDate!)}`).join("；")}</span></div>}
			{(state.data?.externalMovements ?? []).length === 0 ? <EmptyState title="暂无对外出库" description="客户召回或维修保养时，从右上角登记对外出库。" /> : <div className="table-scroll"><table><thead><tr><th>出库单 / 模具</th><th>原因</th><th>对方</th><th>预计返库</th><th>状态</th><th>操作</th></tr></thead><tbody>{state.data?.externalMovements.map((movement) => <tr key={movement.id}><td><strong>{movement.movementNo}</strong><small>{movement.moldAssetCode} · {movement.moldAssetName}</small></td><td>{reasonLabel(movement.reasonCode)}<small>{movement.reasonNote ?? "-"}</small></td><td>{movement.counterpartyName ?? "-"}</td><td>{movement.expectedReturnDate ? formatDate(movement.expectedReturnDate) : "未约定"}</td><td><StatusBadge value={movement.status} /></td><td>{movement.status === "OPEN" ? <button className="button button-secondary button-small" disabled={pendingId === movement.id} onClick={() => returnFromExternal(movement.id)}><RotateCcw aria-hidden="true" />确认返库</button> : "-"}</td></tr>)}</tbody></table></div>}
		</section>
		{externalOpen && <Modal title="模具对外出库" description="仅在库模具可以出库。维修保养请填写预计返库日期，系统会对逾期未返库的模具提示。" width="small" onClose={() => !savingExternal && setExternalOpen(false)}><form onSubmit={checkOutExternally}><div className="form-grid"><Field label="在库模具" required><select name="moldAssetId" required defaultValue=""><option value="" disabled>选择在库模具</option>{inStockMolds.map((mold) => <option key={mold.id} value={mold.id}>{mold.assetCode} · {mold.assetName} · 库位 {mold.locationCode ?? "未登记"}</option>)}</select></Field><Field label="出库原因" required><select name="reasonCode" defaultValue="MAINTENANCE"><option value="CUSTOMER_RECALL">客户召回</option><option value="MAINTENANCE">维修保养</option><option value="OTHER">其他</option></select></Field><Field label="对方单位 / 联系方"><input name="counterpartyName" maxLength={160} /></Field><Field label="预计返库日期"><input name="expectedReturnDate" type="date" /></Field></div><Field label="原因说明"><textarea name="reasonNote" rows={3} maxLength={500} placeholder="例如：客户要求返还检查、模具裂纹返厂维修" /></Field><SubmitActions pending={savingExternal} submitLabel="确认对外出库" onCancel={() => setExternalOpen(false)} /></form></Modal>}
    {lifecycleMold && <Modal title={`${lifecycleMold.assetName} · 模具保养与异常`} description="保养、维修和异常锁定均保留履历。" width="small" onClose={() => !savingLifecycle && setLifecycleMold(null)}><form onSubmit={saveLifecycle}>{lifecycleMode === "CONFIGURE" ? <div className="form-grid"><Field label="保养周期（天）"><input name="maintenanceIntervalDays" type="number" min="1" defaultValue={lifecycleMold.maintenanceIntervalDays ?? ""} /></Field><Field label="下次保养日期"><input name="nextMaintenanceDate" type="date" defaultValue={lifecycleMold.nextMaintenanceDate ?? ""} /></Field></div> : lifecycleMode === "RECORD" ? <div className="form-grid"><Field label="记录类型" required><select name="recordType"><option value="MAINTENANCE">保养</option><option value="REPAIR">维修</option><option value="INSPECTION">点检</option></select></Field><Field label="服务单位"><input name="serviceProvider" maxLength={160} /></Field><Field label="下次保养日期"><input name="nextMaintenanceDate" type="date" /></Field><Field label="处理说明" required><textarea name="description" required rows={4} maxLength={1000} autoFocus /></Field></div> : <Field label={lifecycleMode === "LOCK" ? "锁定原因" : "解除结论"} required><textarea name={lifecycleMode === "LOCK" ? "reason" : "resolutionNote"} required rows={4} maxLength={1000} autoFocus /></Field>}<h3>最近履历</h3>{lifecycleHistory.length === 0 ? <p className="muted">暂无内部维修或保养记录。</p> : <div className="table-scroll"><table><thead><tr><th>时间</th><th>类型</th><th>说明</th></tr></thead><tbody>{lifecycleHistory.slice(0, 5).map((item) => <tr key={item.id}><td>{formatDate(item.occurredAt, true)}</td><td>{item.recordType}</td><td>{item.description}</td></tr>)}</tbody></table></div>}<SubmitActions pending={savingLifecycle} submitLabel="确认保存" onCancel={() => setLifecycleMold(null)} /></form></Modal>}
    {receiptOpen && <Modal title="模具入库" description="入库不要求拍照；模具图可在本页的模具台账中后续补录。自动分配只使用空库位，手动选择会校验冲突。" width="small" onClose={() => !savingReceipt && setReceiptOpen(false)}><form onSubmit={receiveMold}><div className="form-grid"><Field label="模具编码" hint="留空自动生成"><input name="assetCode" maxLength={64} autoFocus /></Field><Field label="模具名称" required><input name="assetName" required maxLength={160} /></Field><Field label="库位分配"><select value={receiptLocationMode} onChange={(event) => setReceiptLocationMode(event.target.value as "AUTO" | "MANUAL")}><option value="AUTO">自动分配空库位</option><option value="MANUAL">手动选择库位</option></select></Field>{receiptLocationMode === "AUTO" ? <Field label="系统推荐"><input value={locations.find((location) => location.occupiedCount === 0)?.locationCode ?? "暂无空库位"} readOnly /></Field> : <Field label="入库库位" required><select name="locationCode" required value={receiptLocation} onChange={(event) => setReceiptLocation(event.target.value)}><option value="" disabled>选择空库位</option>{locations.filter((location) => location.occupiedCount === 0).map((location) => <option key={location.locationCode} value={location.locationCode}>{location.locationCode} · 空</option>)}</select></Field>}<Field label="模具权属" required><select name="ownershipType" defaultValue="COMPANY_OWNED"><option value="COMPANY_OWNED">企业自有</option><option value="CUSTOMER_OWNED">客户寄存</option></select></Field><Field label="寄存客户"><input name="ownerName" maxLength={160} placeholder="客户寄存时填写" /></Field></div><SubmitActions pending={savingReceipt} submitLabel="确认入库" onCancel={() => setReceiptOpen(false)} /></form></Modal>}
      {canManageMoldWarehouse && <MoldLocationPanel operatorCode={operatorCode} />}
      {canManageMoldWarehouse && <ProductMoldRelationsPanel operatorCode={operatorCode} />}
    </>
  );
}

function reasonLabel(value: string) {
	return value === "CUSTOMER_RECALL" ? "客户召回" : value === "MAINTENANCE" ? "维修保养" : "其他";
}
