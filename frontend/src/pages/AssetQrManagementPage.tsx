import { FormEvent, useEffect, useMemo, useState } from "react";
import { Box, Download, FileCode2, ImageIcon, Printer, QrCode, ScanLine, Search, Tags } from "lucide-react";
import QRCode from "qrcode";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatDate, LoadingState, Modal, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AssetQrLabel } from "../types";
import { createMoldLabelDxf, createMoldLabelPng, MOLD_LABEL_DXF_MAX_LABELS } from "./moldLabelDxf";

export function AssetQrManagementPage({ operatorCode }: { operatorCode: string }) {
  const [printing, setPrinting] = useState<AssetQrLabel | null>(null);
  const [batchOpen, setBatchOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<"ALL" | "MOLD" | "CARRIER">("ALL");
  const [statusFilter, setStatusFilter] = useState<"ALL" | "UNBOUND" | "BOUND">("ALL");
  const [selectedLabelIds, setSelectedLabelIds] = useState<string[]>([]);
  const state = useAsyncData(async () => {
    const [labels, assets] = await Promise.all([api.assetQrs.list(), api.resources.list()]);
    return { labels, assets: assets.filter((asset) => asset.assetType === "MOLD" || asset.assetType === "CARRIER") };
  }, []);
  const data = state.data;
  const missingAssets = useMemo(() => data ? data.assets.filter((asset) => !data.labels.some((label) => label.assetId === asset.id)) : [], [data]);

  async function execute(action: () => Promise<AssetQrLabel | AssetQrLabel[]>) {
    setSaving(true); setError(null);
    try { const result = await action(); setPrinting(Array.isArray(result) ? result[0] ?? null : result); await state.reload(); }
    catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  async function generate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true); setError(null);
    try {
      const labels = await api.assetQrs.generate({
        intendedAssetType: String(form.get("intendedAssetType")) as "MOLD" | "CARRIER",
        count: Number(form.get("count")), createdBy: operatorCode
      });
      setPrinting(labels[0] ?? null);
      setSelectedLabelIds(labels.filter((label) => label.intendedAssetType === "MOLD" && label.status === "UNBOUND").map((label) => label.id));
      await state.reload();
      setBatchOpen(false);
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  if (state.loading) return <LoadingState label="正在加载资产二维码台账" />;
  if (state.error || !data) return <ErrorNotice error={state.error ?? new Error("二维码数据不可用")} onRetry={state.reload} />;
  const bound = data.labels.filter((label) => label.status === "BOUND");
  const unbound = data.labels.filter((label) => label.status === "UNBOUND");
  const normalizedQuery = query.trim().toLowerCase();
  const matchingAssets = data.assets.filter((asset) => !normalizedQuery || `${asset.assetCode} ${asset.assetName}`.toLowerCase().includes(normalizedQuery));
  const matchingLabels = data.labels.filter((label) => {
    const matchesQuery = !normalizedQuery || `${label.labelNo} ${label.qrToken} ${label.assetCode ?? ""} ${label.assetName ?? ""}`.toLowerCase().includes(normalizedQuery);
    return matchesQuery && (typeFilter === "ALL" || label.intendedAssetType === typeFilter) && (statusFilter === "ALL" || label.status === statusFilter);
  });
  const exportableLabels = matchingLabels.filter((label) => label.intendedAssetType === "MOLD" && label.status === "UNBOUND");
  const selectedExportIds = selectedLabelIds.filter((id) => exportableLabels.some((label) => label.id === id));
  const toggleLabel = (id: string) => setSelectedLabelIds((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  async function exportEzcadVariableData() {
    if (selectedExportIds.length === 0) return;
    setSaving(true); setError(null);
    try {
      const file = await api.assetQrs.exportEzcadVariableData(selectedExportIds, operatorCode);
      downloadFile(file, "mes-mold-qr-ezcad-variable-data.txt");
      setSelectedLabelIds([]);
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  async function exportMoldDxf() {
    if (selectedExportIds.length === 0) return;
    if (selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS) {
      setError(new Error(`DXF 一次最多导出 ${MOLD_LABEL_DXF_MAX_LABELS} 张，适配 110 × 110 mm 场镜；请缩小本次选择。`));
      return;
    }
    setSaving(true); setError(null);
    try {
      const labels = await api.assetQrs.recordMoldDxfExport(selectedExportIds, operatorCode);
      downloadFile(new Blob([createMoldLabelDxf(labels)], { type: "application/dxf;charset=utf-8" }), `mes-mold-qr-50x30-${dateStamp()}.dxf`);
      setSelectedLabelIds([]);
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  async function exportMoldPng() {
    if (selectedExportIds.length === 0) return;
    if (selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS) {
      setError(new Error(`PNG 一次最多导出 ${MOLD_LABEL_DXF_MAX_LABELS} 张，适配 110 × 110 mm 场镜；请缩小本次选择。`));
      return;
    }
    setSaving(true); setError(null);
    try {
      const labels = await api.assetQrs.recordMoldPngExport(selectedExportIds, operatorCode);
      downloadFile(await createMoldLabelPng(labels), `mes-mold-qr-50x30-300dpi-${dateStamp()}.png`);
      setSelectedLabelIds([]);
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  return <>
    <PageHeader title="资产二维码管理" description="统一管理模具与周转车二维码。补打保持原二维码不变；任务码与流转卡由文档中心按工单补打。" action={<button className="button button-primary" onClick={() => setBatchOpen(true)}><Tags aria-hidden="true" />批量预生成</button>} />
    {error && <ErrorNotice error={error} />}
    <section className="cart-summary" aria-label="资产二维码概况">
      <div><QrCode aria-hidden="true" /><span>已绑定标签</span><strong>{bound.length}</strong></div>
      <div><Tags aria-hidden="true" /><span>待绑定标签</span><strong>{unbound.length}</strong></div>
      <div className={missingAssets.length ? "attention" : ""}><Box aria-hidden="true" /><span>资产缺少二维码</span><strong>{missingAssets.length}</strong></div>
    </section>

    <section className="info-notice">
      模具标牌默认版式为 50 × 30 mm：二维码外框 24 × 24 mm（含四模块静区），右侧显示人工核对标签号。可导出兼容旧版激光软件的 R12 DXF 矢量标牌或 300 DPI PNG 标牌（每次最多 6 张，适配 110 × 110 mm 场镜），也可继续导出 EZCAD 变量 TXT 供既有 `.ezd` 模板连续打标。
    </section>

    <section className="section-block">
      <header className="section-heading"><div><h2>模具与周转车资产</h2><p>缺失时发放新标签；遗失时补打同一标签，避免现场一物多码。</p></div></header>
      {matchingAssets.length === 0 ? <EmptyState title={query ? "未找到资产" : "没有可管理资产"} description="可按资产编码或名称检索模具、周转车。" /> : <div className="table-scroll"><table><thead><tr><th>资产</th><th>类型</th><th>二维码</th><th>打印记录</th><th>操作</th></tr></thead><tbody>{matchingAssets.map((asset) => {
        const label = data.labels.find((item) => item.assetId === asset.id);
        return <tr key={asset.id}><td><strong>{asset.assetCode}</strong><small>{asset.assetName}</small></td><td><StatusBadge value={asset.assetType} /></td><td>{label ? <><strong>{label.labelNo}</strong><small>已绑定</small></> : <small>缺少二维码</small>}</td><td>{label ? `${label.printCount} 次${label.lastPrintedAt ? ` · ${formatDate(label.lastPrintedAt, true)}` : ""}` : "—"}</td><td><button className="button button-secondary button-small" disabled={saving} onClick={() => execute(() => api.assetQrs.issue(asset.id, operatorCode))}><Printer aria-hidden="true" />{label ? "补打" : "发码并打印"}</button></td></tr>;
      })}</tbody></table></div>}
    </section>

    <section className="section-block">
      <header className="section-heading"><div><h2>预生成标签台账</h2><p>二维码标签可先印制入库，后续由小程序扫码绑定到对应资产；未绑定模具标签可直接导出 DXF、PNG 标牌，或导出 EZCAD 批量变量文件。</p></div><div className="header-actions"><button className="button button-secondary button-small" type="button" disabled={saving || exportableLabels.length === 0} onClick={() => setSelectedLabelIds(exportableLabels.map((label) => label.id))}>选择当前模具标签</button><button className="button button-secondary button-small" type="button" disabled={saving || selectedExportIds.length === 0 || selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS} title={selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS ? `DXF 一次最多 ${MOLD_LABEL_DXF_MAX_LABELS} 张` : undefined} onClick={() => void exportMoldDxf()}><FileCode2 aria-hidden="true" />导出 DXF 标牌 ({selectedExportIds.length}/{MOLD_LABEL_DXF_MAX_LABELS})</button><button className="button button-secondary button-small" type="button" disabled={saving || selectedExportIds.length === 0 || selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS} title={selectedExportIds.length > MOLD_LABEL_DXF_MAX_LABELS ? `PNG 一次最多 ${MOLD_LABEL_DXF_MAX_LABELS} 张` : undefined} onClick={() => void exportMoldPng()}><ImageIcon aria-hidden="true" />导出 PNG 标牌 ({selectedExportIds.length}/{MOLD_LABEL_DXF_MAX_LABELS})</button><button className="button button-primary button-small" type="button" disabled={saving || selectedExportIds.length === 0} onClick={() => void exportEzcadVariableData()}><Download aria-hidden="true" />导出 EZCAD 变量数据 ({selectedExportIds.length})</button></div></header>
      <div className="warehouse-filter-bar" aria-label="二维码标签筛选"><Field label="检索"><div className="field-inline-control"><Search aria-hidden="true" /><input value={query} placeholder="标签号、资产码或名称" onChange={(event) => setQuery(event.target.value)} /></div></Field><Field label="资产类型"><select value={typeFilter} onChange={(event) => setTypeFilter(event.target.value as typeof typeFilter)}><option value="ALL">全部类型</option><option value="MOLD">模具</option><option value="CARRIER">周转车</option></select></Field><Field label="绑定状态"><select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as typeof statusFilter)}><option value="ALL">全部状态</option><option value="UNBOUND">待绑定</option><option value="BOUND">已绑定</option></select></Field></div>
      {matchingLabels.length === 0 ? <EmptyState title={query || typeFilter !== "ALL" || statusFilter !== "ALL" ? "未找到二维码标签" : "还未生成二维码标签"} description="使用“批量预生成”创建首批标签，或调整筛选条件。" /> : <div className="table-scroll"><table><thead><tr><th aria-label="选择导出" /><th>标签号</th><th>适用类型</th><th>绑定资产</th><th>状态</th><th>打印</th><th>操作</th></tr></thead><tbody>{matchingLabels.map((label) => { const exportable = label.intendedAssetType === "MOLD" && label.status === "UNBOUND"; return <tr key={label.id}><td>{exportable && <input type="checkbox" aria-label={`选择 ${label.labelNo} 导出`} checked={selectedLabelIds.includes(label.id)} onChange={() => toggleLabel(label.id)} />}</td><td><strong>{label.labelNo}</strong><small>MES:ASSET_QR:{label.qrToken}</small></td><td><StatusBadge value={label.intendedAssetType} /></td><td>{label.assetCode ? <><strong>{label.assetCode}</strong><small>{label.assetName}</small></> : "待扫码绑定"}</td><td><StatusBadge value={label.status} /></td><td>{label.printCount} 次</td><td><button className="icon-button compact" aria-label={`补打 ${label.labelNo}`} disabled={saving} onClick={() => execute(() => api.assetQrs.reprint(label.id, operatorCode))}><Printer /></button></td></tr>; })}</tbody></table></div>}
    </section>

    {batchOpen && <Modal title="批量预生成资产二维码" description="生成后不绑定资产，可直接打印入库，后续扫码绑定。" width="small" onClose={() => !saving && setBatchOpen(false)}><form onSubmit={generate}><div className="form-grid"><Field label="标签适用资产" required><select name="intendedAssetType" defaultValue="MOLD"><option value="MOLD">模具</option><option value="CARRIER">周转车</option></select></Field><Field label="生成数量" required hint="每批最多 500 张"><input name="count" type="number" min="1" max="500" defaultValue="10" required autoFocus /></Field></div><SubmitActions pending={saving} submitLabel="生成并打开首张标签" onCancel={() => setBatchOpen(false)} /></form></Modal>}
    {printing && <QrPrintLabel label={printing} onClose={() => setPrinting(null)} />}
  </>;
}

function QrPrintLabel({ label, onClose }: { label: AssetQrLabel; onClose: () => void }) {
  const [src, setSrc] = useState("");
  const payload = `MES:ASSET_QR:${label.qrToken}`;
  useEffect(() => { void QRCode.toDataURL(payload, { width: 260, margin: 4, errorCorrectionLevel: "M" }).then(setSrc); }, [payload]);
  function printLabel() {
    if (!src) return;
    const printWindow = window.open("", "mes-asset-label", "popup,width=600,height=360");
    if (!printWindow) return;
    const title = label.assetCode ?? label.labelNo;
    const subtitle = label.assetName ?? (label.intendedAssetType === "MOLD" ? "待绑定模具标签" : "待绑定周转车标签");
    printWindow.addEventListener("load", () => { printWindow.focus(); printWindow.print(); printWindow.close(); }, { once: true });
    printWindow.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>@page{size:50mm 30mm;margin:0}*{box-sizing:border-box}body{margin:0;font-family:Arial,"Microsoft YaHei",sans-serif}.asset-label{width:50mm;height:30mm;padding:2mm;display:grid;grid-template-columns:24mm 1fr;gap:2mm;border:.2mm solid #111;overflow:hidden}.asset-label img{width:24mm;height:24mm;align-self:center}.asset-label__text{display:grid;align-content:center;gap:1.3mm;min-width:0}.asset-label__type{font-size:2.3mm;font-weight:700}.asset-label__code{font-size:2.65mm;font-weight:700;overflow-wrap:anywhere}.asset-label__name{font-size:2.2mm;line-height:1.2;overflow-wrap:anywhere}.asset-label__label{font-size:1.75mm;color:#333;overflow-wrap:anywhere}</style></head><body><main class="asset-label"><img src="${src}" alt="二维码"><section class="asset-label__text"><div class="asset-label__type">${label.intendedAssetType === "MOLD" ? "模具资产标牌" : "周转车资产标牌"}</div><div class="asset-label__code">${escapeHtml(title)}</div><div class="asset-label__name">${escapeHtml(subtitle)}</div><div class="asset-label__label">${escapeHtml(label.labelNo)}</div></section></main></body></html>`);
    printWindow.document.close();
  }
  return <Modal title={label.assetCode ? "补打资产二维码" : "预生成二维码标签"} description={label.intendedAssetType === "MOLD" ? "模具标牌默认 50 × 30 mm；二维码外框 24 × 24 mm，含静区与人工核对标签号。" : "请粘贴到对应周转车；未绑定标签须在小程序扫码绑定后使用。"} width="small" onClose={onClose}><div className="asset-qr-preview">{src ? <img src={src} alt={`二维码 ${label.labelNo}`} /> : <span aria-live="polite">二维码生成中</span>}<strong>{label.assetCode ?? label.labelNo}</strong><span>{label.assetName ?? (label.intendedAssetType === "MOLD" ? "待绑定模具标签" : "待绑定周转车标签")}</span><small>{payload}</small></div><footer className="form-actions"><button className="button button-secondary" type="button" onClick={onClose}>关闭</button><button className="button button-primary" type="button" disabled={!src} onClick={printLabel}><Printer aria-hidden="true" />打印此标签</button></footer></Modal>;
}

function escapeHtml(value: string) {
  return value.replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character] ?? character);
}

function downloadFile(file: Blob, filename: string) {
  const url = URL.createObjectURL(file);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function dateStamp() {
  return new Date().toISOString().slice(0, 10).replaceAll("-", "");
}
