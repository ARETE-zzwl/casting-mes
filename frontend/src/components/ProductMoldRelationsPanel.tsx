import { FormEvent, useState } from "react";
import { Link2, Unlink } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, SubmitActions } from "./ui";
import { useAsyncData } from "../hooks";

export function ProductMoldRelationsPanel({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [products, assets, relations] = await Promise.all([api.products.list(), api.resources.list(), api.productMolds.list()]);
    return { products, assets, relations };
  }, []);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  async function bind(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true); setError(null);
    try {
      await api.productMolds.bind({ productId: String(form.get("productId")), moldAssetId: String(form.get("moldAssetId")), note: String(form.get("note") || "") || undefined, operatorCode });
      event.currentTarget.reset();
      await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  async function unbind(id: string) {
    setSaving(true); setError(null);
    try { await api.productMolds.unbind(id); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  if (state.loading) return <LoadingState label="正在加载产品模具关系" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const products = state.data?.products ?? [];
  const molds = (state.data?.assets ?? []).filter((asset) => asset.assetType === "MOLD");
  const relations = state.data?.relations ?? [];

  return <section className="section-block">
    <header className="section-heading"><div><h2>产品与模具匹配</h2><p>绑定后，订单选择产品时会优先推荐对应的在库模具；仍可手动改选其他模具。</p></div><Link2 aria-hidden="true" /></header>
    {error != null && <ErrorNotice error={error} onRetry={() => setError(null)} />}
    <form className="compact-form" onSubmit={bind}><div className="form-grid"><Field label="产品" required><select name="productId" required defaultValue=""><option value="" disabled>选择产品</option>{products.filter((product) => product.active).map((product) => <option key={product.id} value={product.id}>{product.code} · {product.name} · {product.specification ?? "未填规格"}</option>)}</select></Field><Field label="模具" required><select name="moldAssetId" required defaultValue=""><option value="" disabled>选择模具</option>{molds.map((mold) => <option key={mold.id} value={mold.id}>{mold.assetCode} · {mold.assetName} · {mold.locationCode ?? "未登记"}</option>)}</select></Field><Field label="匹配说明"><input name="note" maxLength={500} placeholder="例如：客户专用、共用备用或新模首用" /></Field></div><SubmitActions pending={saving} submitLabel="绑定产品与模具" onCancel={() => undefined} /></form>
    {relations.length === 0 ? <EmptyState title="暂无产品模具关系" description="先绑定一组常用产品与模具，订单建单时便会自动优先推荐。" /> : <div className="table-scroll"><table><thead><tr><th>产品</th><th>模具</th><th>库位 / 权属</th><th>图片</th><th>说明</th><th>操作</th></tr></thead><tbody>{relations.map((relation) => <tr key={relation.id}><td><strong>{relation.productCode}</strong><small>{relation.productName}</small></td><td><strong>{relation.moldAssetCode}</strong><small>{relation.moldAssetName}</small></td><td>{relation.locationCode ?? "未登记"}<small>{relation.ownershipType === "CUSTOMER_OWNED" ? `客户寄存${relation.ownerName ? ` · ${relation.ownerName}` : ""}` : "企业自有"}</small></td><td>{relation.moldImageUrl ? <a className="text-link" href={relation.moldImageUrl} target="_blank" rel="noreferrer">查看</a> : "未上传"}</td><td>{relation.note ?? "-"}</td><td><button className="button button-secondary button-small" type="button" disabled={saving} onClick={() => void unbind(relation.id)}><Unlink aria-hidden="true" />解除</button></td></tr>)}</tbody></table></div>}
  </section>;
}
