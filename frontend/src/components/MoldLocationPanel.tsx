import { FormEvent, useState } from "react";
import { MapPin, Pencil, Plus } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, Modal, SubmitActions } from "./ui";
import { useAsyncData } from "../hooks";
import type { MoldStorageLocation } from "../types";

export function MoldLocationPanel({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(() => api.molds.locations(), []);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<MoldStorageLocation | null>(null);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); setSaving(true); setError(null);
    try { await api.molds.createLocation({ locationCode: String(form.get("locationCode")), locationName: String(form.get("locationName")), capacity: Number(form.get("capacity")), operatorCode }); event.currentTarget.reset(); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!editing) return; const form = new FormData(event.currentTarget); setSaving(true); setError(null);
    try { await api.molds.updateLocation(editing.locationCode, { locationName: String(form.get("locationName")), capacity: Number(form.get("capacity")), active: form.get("active") === "on", operatorCode }); setEditing(null); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }
  if (state.loading) return <LoadingState label="正在加载模具库位" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const locations = state.data ?? [];
  return <section className="section-block"><header className="section-heading"><div><h2>模具库位管理</h2><p>仓管和管理员可维护库位、容量与启用状态；已占用库位不能直接停用或缩小容量。</p></div><MapPin aria-hidden="true" /></header>{error != null && <ErrorNotice error={error} onRetry={() => setError(null)} />}<form className="compact-form" onSubmit={create}><div className="form-grid"><Field label="库位编码" required><input name="locationCode" required maxLength={64} placeholder="如 MOLD-E-01" /></Field><Field label="库位名称" required><input name="locationName" required maxLength={160} placeholder="如 E区-01" /></Field><Field label="容量" required><input name="capacity" type="number" min="1" step="1" defaultValue="1" required /></Field></div><SubmitActions pending={saving} submitLabel="新增库位" onCancel={() => undefined} /></form>{locations.length === 0 ? <EmptyState title="暂无库位" description="新增库位后，模具入库可自动或手动选用。" /> : <div className="table-scroll"><table><thead><tr><th>编码</th><th>名称</th><th>占用</th><th>状态</th><th>操作</th></tr></thead><tbody>{locations.map((location) => <tr key={location.locationCode}><td><strong>{location.locationCode}</strong></td><td>{location.locationName}</td><td>{location.occupiedCount} / {location.capacity}</td><td>{location.active ? "启用" : "停用"}</td><td><button className="button button-secondary button-small" type="button" onClick={() => setEditing(location)}><Pencil aria-hidden="true" />编辑</button></td></tr>)}</tbody></table></div>}{editing && <Modal title={`编辑库位 ${editing.locationCode}`} description="保存前会校验当前模具占用数量。" width="small" onClose={() => !saving && setEditing(null)}><form onSubmit={save}><div className="form-grid"><Field label="库位名称" required><input name="locationName" defaultValue={editing.locationName} required maxLength={160} /></Field><Field label="容量" required><input name="capacity" type="number" min="1" step="1" defaultValue={editing.capacity} required /></Field><Field label="启用状态"><label className="checkbox-field"><input name="active" type="checkbox" defaultChecked={editing.active} />启用库位</label></Field></div><SubmitActions pending={saving} submitLabel="保存库位" onCancel={() => setEditing(null)} /></form></Modal>}</section>;
}
