import { FormEvent, useMemo, useState } from "react";
import { Link2, Plus, RefreshCw, Unlink } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, RouteType } from "../types";

const routeNames: Record<RouteType, string> = {
  MID_TEMP_WAX: "中温蜡线",
  LOW_TEMP_WAX: "低温蜡线",
  SAND_OUTSOURCE: "砂型外协"
};

export function CustomerProductMoldRelationsPage({ user }: { user: AccessUser }) {
  const state = useAsyncData(async () => {
    const [customers, products, assets, productMolds, customerProductMolds, customerProductHistory] = await Promise.all([
      api.customers.list(),
      api.products.list(),
      api.resources.list(),
      api.productMolds.list(),
      api.customerProductMolds.list(),
      api.customerProductMolds.history()
    ]);
    return { customers, products, assets, productMolds, customerProductMolds, customerProductHistory };
  }, []);
  const [routeFilter, setRouteFilter] = useState<RouteType | "ALL">("ALL");
  const [query, setQuery] = useState("");
  const [customerId, setCustomerId] = useState("");
  const [productId, setProductId] = useState("");
  const [moldAssetId, setMoldAssetId] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const customers = state.data?.customers ?? [];
  const products = state.data?.products ?? [];
  const productMolds = state.data?.productMolds ?? [];
  const customerProductMolds = state.data?.customerProductMolds ?? [];
	const customerProductHistory = state.data?.customerProductHistory ?? [];
  const molds = (state.data?.assets ?? []).filter((asset) => asset.assetType === "MOLD");
  const filteredProducts = products.filter((product) => product.active && (routeFilter === "ALL" || product.routeType === routeFilter));
  const linkedMoldIds = new Set(productMolds.filter((relation) => relation.productId === productId).map((relation) => relation.moldAssetId));
  const candidateMolds = useMemo(() => [...molds].sort((left, right) => Number(linkedMoldIds.has(right.id)) - Number(linkedMoldIds.has(left.id)) || left.assetCode.localeCompare(right.assetCode)), [molds, linkedMoldIds]);
  const normalizedQuery = query.trim().toLowerCase();
  const visibleRelations = customerProductMolds.filter((relation) => {
    const product = products.find((item) => item.id === relation.productId);
    if (routeFilter !== "ALL" && product?.routeType !== routeFilter) return false;
    return !normalizedQuery || `${relation.customerCode} ${relation.customerName} ${relation.productCode} ${relation.productName} ${relation.moldAssetCode} ${relation.moldAssetName} ${relation.locationCode ?? ""}`.toLowerCase().includes(normalizedQuery);
  });
	const visibleHistory = customerProductHistory.filter((history) => {
		if (routeFilter !== "ALL" && history.routeType !== routeFilter) return false;
		return !normalizedQuery || `${history.customerCode} ${history.customerName} ${history.productCode} ${history.productName}`.toLowerCase().includes(normalizedQuery);
	});

  async function bind(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!customerId || !productId || !moldAssetId) return;
    setSaving(true);
    setError(null);
    try {
      if (!linkedMoldIds.has(moldAssetId)) {
        await api.productMolds.bind({ productId, moldAssetId, note: "由客户-产品-模具关系管理页创建", operatorCode: user.employeeCode });
      }
      await api.customerProductMolds.bind({ customerId, productId, moldAssetId, operatorCode: user.employeeCode });
      setMoldAssetId("");
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function unbind(id: string) {
    setSaving(true);
    setError(null);
    try {
      await api.customerProductMolds.unbind(id);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  if (state.loading) return <LoadingState label="正在加载客户产品模具关系" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  return <>
    <PageHeader title="客户产品模具关系" description="维护客户、产品与模具的专用关系；订单创建时按此关系优先匹配，并支持一个产品配置多套模具。" action={<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新"><RefreshCw aria-hidden="true" /></button>} />
    {error && <ErrorNotice error={error} onRetry={() => setError(null)} />}

		<section className="section-block relationship-history">
			<header className="section-heading"><div><h2>订单沉淀关系</h2><p>按历史订单实时归纳客户与产品；订单选模或待入库模具收货后，会自动补齐产品模具与客户产品模具关系。</p></div></header>
			{visibleHistory.length === 0 ? <EmptyState title="暂无订单沉淀关系" description="创建订单后，客户与产品会自动出现在这里。" /> : <div className="table-scroll"><table><thead><tr><th>客户</th><th>产品</th><th>产线</th><th>历史订单</th><th>关联模具</th><th>最近订单</th></tr></thead><tbody>{visibleHistory.map((history) => <tr key={`${history.customerId}-${history.productId}`}><td><strong>{history.customerName}</strong><small>{history.customerCode}</small></td><td><strong>{history.productCode} · {history.productName}</strong></td><td>{routeNames[history.routeType]}</td><td>{history.orderCount} 单</td><td>{history.moldCount} 套</td><td>{new Date(history.lastOrderAt).toLocaleDateString("zh-CN")}</td></tr>)}</tbody></table></div>}
		</section>

    <section className="section-block relationship-manager">
      <header className="section-heading"><div><h2>新增三方绑定</h2><p>若模具尚未进入产品模具库，保存时会一并补齐产品级绑定。</p></div><Link2 aria-hidden="true" /></header>
      <form className="compact-form" onSubmit={bind}>
        <div className="form-grid relationship-form-grid">
          <Field label="生产线"><select value={routeFilter} onChange={(event) => { setRouteFilter(event.target.value as RouteType | "ALL"); setProductId(""); setMoldAssetId(""); }}><option value="ALL">全部产线</option>{Object.entries(routeNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></Field>
          <Field label="客户" required><select value={customerId} onChange={(event) => setCustomerId(event.target.value)} required><option value="" disabled>选择客户</option>{customers.filter((customer) => customer.active).map((customer) => <option key={customer.id} value={customer.id}>{customer.code} · {customer.name}</option>)}</select></Field>
          <Field label="产品" required><select value={productId} onChange={(event) => { setProductId(event.target.value); setMoldAssetId(""); }} required><option value="" disabled>选择产品</option>{filteredProducts.map((product) => <option key={product.id} value={product.id}>{product.code} · {product.name} · {product.specification ?? "未填规格"} · {product.material ?? "未填材质"}</option>)}</select></Field>
          <Field label="模具" required hint={productId ? "已绑定到该产品的模具排在前面。" : "请先选择产品。"}><select value={moldAssetId} onChange={(event) => setMoldAssetId(event.target.value)} required disabled={!productId}><option value="" disabled>选择模具</option>{candidateMolds.map((mold) => <option key={mold.id} value={mold.id}>{linkedMoldIds.has(mold.id) ? "已匹配 · " : ""}{mold.assetCode} · {mold.assetName} · {mold.locationCode ?? "未登记"} · {mold.ownershipType === "CUSTOMER_OWNED" ? "客户寄存" : "企业自有"}</option>)}</select></Field>
        </div>
        <div className="form-actions"><button className="button button-primary" disabled={saving || !customerId || !productId || !moldAssetId}><Plus aria-hidden="true" />{saving ? "保存中" : "保存三方绑定"}</button></div>
      </form>
    </section>

    <section className="section-block">
      <header className="section-heading"><div><h2>已绑定关系</h2><p>{customerProductMolds.length} 条专用关系，{new Set(customerProductMolds.map((relation) => relation.productId)).size} 个产品已关联客户模具。</p></div></header>
      <div className="filter-bar relationship-filter"><Field label="产线筛选"><select value={routeFilter} onChange={(event) => setRouteFilter(event.target.value as RouteType | "ALL")}><option value="ALL">全部产线</option>{Object.entries(routeNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></Field><Field label="关系检索"><input value={query} placeholder="客户、产品、规格、材质、模具编码或库位" onChange={(event) => setQuery(event.target.value)} /></Field></div>
      {visibleRelations.length === 0 ? <EmptyState title="未找到匹配关系" description="可调整筛选条件，或在上方新增客户、产品与模具绑定。" /> : <div className="table-scroll"><table><thead><tr><th>客户</th><th>产品</th><th>模具</th><th>库位与权属</th><th>状态</th><th>维护人</th><th>操作</th></tr></thead><tbody>{visibleRelations.map((relation) => {
        const product = products.find((item) => item.id === relation.productId);
        return <tr key={relation.id}><td><strong>{relation.customerName}</strong><small>{relation.customerCode}</small></td><td className="relation-product-cell">{product?.modelImageUrl ? <img src={product.modelImageUrl} alt={`${relation.productName} 模型图`} /> : <span className="relation-no-image">无图</span>}<div><strong>{relation.productCode} · {relation.productName}</strong><small>{product?.specification ?? "未填规格"} · {product?.material ?? "未填材质"} · {product ? routeNames[product.routeType] : "路线待同步"}</small></div></td><td><strong>{relation.moldAssetCode}</strong><small>{relation.moldAssetName}</small></td><td>{relation.locationCode ?? "未登记"}<small>{relation.ownershipType === "CUSTOMER_OWNED" ? `客户寄存 · ${relation.ownerName ?? "未登记"}` : "企业自有"}</small></td><td><StatusBadge value={relation.moldStatus} /><small>{relation.moldCustodyStatus === "IN_STOCK" ? "在库" : relation.moldCustodyStatus ?? "状态待同步"}</small></td><td>{relation.createdBy}<small>最近使用 {new Date(relation.lastUsedAt).toLocaleDateString("zh-CN")}</small></td><td><button className="button button-secondary button-small" type="button" disabled={saving} onClick={() => void unbind(relation.id)}><Unlink aria-hidden="true" />解除专用关系</button></td></tr>;
      })}</tbody></table></div>}
    </section>
  </>;
}
