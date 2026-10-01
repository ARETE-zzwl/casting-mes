import { FormEvent, useState } from "react";
import { Link2, Plus, RefreshCw } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api";
import {
  EmptyState,
  ErrorNotice,
  Field,
  LoadingState,
  Modal,
  PageHeader
} from "../components/ui";
import { useAsyncData } from "../hooks";
import { ProductMoldRelationsPanel } from "../components/ProductMoldRelationsPanel";
import type { AccessUser, Product, RouteType } from "../types";

const routeNames: Record<RouteType, string> = {
  MID_TEMP_WAX: "中温蜡",
  LOW_TEMP_WAX: "低温蜡",
  SAND_OUTSOURCE: "砂型外协"
};

export function MasterDataPage({ user }: { user: AccessUser }) {
  const state = useAsyncData(async () => {
    const [products, customerProductHistory] = await Promise.all([api.products.list(), api.customerProductMolds.history()]);
    return { products, customerProductHistory };
  }, []);
  const [formError, setFormError] = useState<unknown>(null);
  const [saving, setSaving] = useState<"product" | null>(null);
	const [productModelImageUrl, setProductModelImageUrl] = useState<string | undefined>();
	const [editingProduct, setEditingProduct] = useState<Product | null>(null);

  async function createProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving("product");
    setFormError(null);
    try {
      await api.products.create({
        code: String(form.get("code")),
        name: String(form.get("name")),
        routeType: String(form.get("routeType")) as RouteType,
        routeVersion: String(form.get("routeVersion")),
			modelImageUrl: productModelImageUrl,
			specification: String(form.get("specification") || ""),
			material: String(form.get("material") || "")
      });
      formElement.reset();
		setProductModelImageUrl(undefined);
      await state.reload();
    } catch (error) {
      setFormError(error);
    } finally {
      setSaving(null);
    }
  }

	async function uploadProductModel(file: File) {
		setSaving("product"); setFormError(null);
		try { setProductModelImageUrl((await api.files.upload("PRODUCT_MODEL", file)).url); }
		catch (error) { setFormError(error); }
		finally { setSaving(null); }
	}

	async function updateProductModel(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!editingProduct) return;
		const file = new FormData(event.currentTarget).get("modelImage");
		if (!(file instanceof File) || file.size === 0) { setFormError(new Error("请选择产品模型图。")); return; }
		setSaving("product"); setFormError(null);
		try {
			const uploaded = await api.files.upload("PRODUCT_MODEL", file);
			await api.products.updateModelImage(editingProduct.id, uploaded.url);
			setEditingProduct(null);
			await state.reload();
		} catch (error) { setFormError(error); } finally { setSaving(null); }
	}

  if (state.loading) return <LoadingState label="正在加载产品目录" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const products = state.data?.products ?? [];
	const customerCounts = new Map<string, number>();
	for (const history of state.data?.customerProductHistory ?? []) {
		customerCounts.set(history.productId, (customerCounts.get(history.productId) ?? 0) + 1);
	}

  return (
    <>
      <PageHeader
        title="产品与路线"
        description="维护产品规格、材质、模型图与已发布的生产路线版本"
        action={
          <div className="header-actions"><Link className="button button-secondary" to="/customers">客户管理</Link><button className="icon-button" type="button" onClick={state.reload} aria-label="刷新"><RefreshCw aria-hidden="true" /></button></div>
        }
      />
      {formError && <ErrorNotice error={formError} />}

      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>新建产品</h2><p>选择试点路线并固化版本</p></div>
          </header>
          <form onSubmit={createProduct} className="compact-form">
            <div className="form-grid">
              <Field label="产品编码" required><input name="code" required maxLength={64} /></Field>
              <Field label="产品名称" required><input name="name" required maxLength={160} /></Field>
              <Field label="生产路线" required>
                <select name="routeType" defaultValue="MID_TEMP_WAX">
                  {Object.entries(routeNames).map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </Field>
              <Field label="路线版本" required><input name="routeVersion" required defaultValue="V1" maxLength={32} /></Field>
				<Field label="产品规格"><input name="specification" maxLength={500} /></Field>
				<Field label="材质"><input name="material" maxLength={160} /></Field>
				<Field label="工件模型图"><input type="file" accept="image/jpeg,image/png,image/webp" disabled={saving === "product"} onChange={(event) => { const file = event.target.files?.[0]; if (file) void uploadProductModel(file); }} />{productModelImageUrl && <small className="muted">已上传模型图</small>}</Field>
            </div>
            <button className="button button-primary" disabled={saving === "product"}>
              <Plus aria-hidden="true" />{saving === "product" ? "保存中" : "创建产品"}
            </button>
          </form>
        </div>

        <div className="table-pane">
          <header className="section-heading">
            <div><h2>产品目录</h2><p>{products.length} 个有效产品</p></div>
          </header>
          {products.length === 0 ? (
            <EmptyState title="暂无产品" description="在左侧创建首个试点产品。" />
          ) : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>编码</th><th>名称</th><th>模型图</th><th>路线</th><th>版本</th><th>历史客户</th><th>操作</th></tr></thead>
                <tbody>
                  {products.map((product) => (
                    <tr key={product.id}>
                      <td className="primary-cell">{product.code}</td>
                      <td>{product.name}</td>
                      <td>{product.modelImageUrl ? <img className="product-model-thumb" src={product.modelImageUrl} alt={`${product.name} 模型图`} /> : <span className="muted">未附图</span>}</td>
                      <td>{routeNames[product.routeType]}</td>
                      <td>{product.routeVersion}</td>
						<td>{customerCounts.get(product.id) ?? 0} 家</td>
						<td><button className="button button-secondary button-small" type="button" onClick={() => setEditingProduct(product)}>{product.modelImageUrl ? "更新模型图" : "添加模型图"}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

		{editingProduct && <Modal title="维护产品模型图" description={`${editingProduct.code} · ${editingProduct.name}。新图保存后会立即用于产品检索、订单选择和工艺卡。`} width="small" onClose={() => saving !== "product" && setEditingProduct(null)}><form onSubmit={updateProductModel}><Field label="当前模型图">{editingProduct.modelImageUrl ? <img className="product-model-preview-large" src={editingProduct.modelImageUrl} alt={`${editingProduct.name} 当前模型图`} /> : <span className="muted">当前没有模型图</span>}</Field><Field label="上传新模型图" required><input name="modelImage" type="file" accept="image/jpeg,image/png,image/webp" required disabled={saving === "product"} /></Field><div className="form-actions"><button className="button button-secondary" type="button" onClick={() => setEditingProduct(null)}>取消</button><button className="button button-primary" disabled={saving === "product"}>{saving === "product" ? "上传中" : "保存模型图"}</button></div></form></Modal>}
	  <section className="section-block relationship-entry">
		<div><h2>客户产品模具关系</h2><p>维护客户专用模具、多套备模与产品级模具匹配，订单创建时会按此关系自动推荐。</p></div>
		<Link className="button button-secondary" to="/customer-product-molds"><Link2 aria-hidden="true" />进入关系管理</Link>
	  </section>
	  <ProductMoldRelationsPanel operatorCode={user.employeeCode} />
    </>
  );
}
