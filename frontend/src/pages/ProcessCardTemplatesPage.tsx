import { FormEvent, useRef, useState } from "react";
import { Check, Copy, FilePlus2, ImagePlus, Sparkles } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, Field, formatDate, LoadingState, PageHeader, StatusBadge, SubmitActions } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { ProcessCardAiSuggestion, ProcessCardTemplate, Product } from "../types";

const PROCESS_STEPS = [
  { code: "WAX_INJECTION", label: "射蜡", hint: "蜡料、注射温度、压力、保压时间" },
  { code: "WAX_REPAIR", label: "修蜡", hint: "外观要求、修整重点、允许缺陷" },
  { code: "TREE_ASSEMBLY", label: "组树", hint: "每树件数、组树标准、浇道要求" },
  { code: "SHELL_BUILDING", label: "制壳", hint: "壳层、浆料、干燥时间、自动或手工路线" },
  { code: "DEWAX", label: "脱蜡", hint: "炉次、装炉量、温度、保温时间" },
  { code: "POURING", label: "浇筑", hint: "材质批次、炉号、浇筑温度、浇筑要求" },
  { code: "KNOCKOUT_CUTTING", label: "脱壳与分割", hint: "冷却要求、分割位置、清点要求" },
  { code: "OPTIONAL_FINISHING", label: "后处理（可选）", hint: "工艺路线、检验要点、交付状态" }
] as const;

type ProcessStepCode = (typeof PROCESS_STEPS)[number]["code"];
type EditMode = "FIRST_VERSION" | "REVISION";
type AiDraft = ProcessCardAiSuggestion & { version: string; requestId: number };

function productLabel(product: Product) {
	return `${product.code} · ${product.name} · ${product.specification ?? "未填规格"} · ${product.material ?? "未填材质"}${product.active ? "" : " · 历史已停用"}`;
}

function legacyProductLabel(product: Product) {
	return `${product.code} · ${product.name} · ${product.routeType}${product.active ? "" : " · 历史已停用"}`;
}

function readOperationParameters(template?: ProcessCardTemplate): Record<ProcessStepCode, string> {
  const empty = Object.fromEntries(PROCESS_STEPS.map((step) => [step.code, ""])) as Record<ProcessStepCode, string>;
  if (!template) return empty;
  try {
    const parsed = JSON.parse(template.operationParameters) as Record<string, unknown>;
    PROCESS_STEPS.forEach((step) => { const value = parsed[step.code]; empty[step.code] = typeof value === "string" ? value : ""; });
  } catch {
    // Old template data remains readable; the engineer can complete the normalized fields before saving a revision.
  }
  return empty;
}

type OperationImages = Partial<Record<ProcessStepCode, string[]>>;

function readOperationImages(template?: ProcessCardTemplate): OperationImages {
  if (!template) return {};
  try {
    const parsed = JSON.parse(template.operationParameters) as Record<string, unknown>;
    const images = parsed._operationImages;
    if (!images || typeof images !== "object") return {};
	return Object.fromEntries(PROCESS_STEPS.flatMap((step) => {
      const value = (images as Record<string, unknown>)[step.code];
		const urls = Array.isArray(value) ? value.filter((item): item is string => typeof item === "string" && Boolean(item)) : typeof value === "string" && value ? [value] : [];
		return urls.length ? [[step.code, urls]] : [];
	})) as OperationImages;
  } catch { return {}; }
}

export function ProcessCardTemplatesPage({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [templates, products] = await Promise.all([api.processCardTemplates.list(), api.products.list()]);
    return { templates, products };
  }, []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [productQuery, setProductQuery] = useState("");
  const [selectedProductId, setSelectedProductId] = useState("");
  const [sourceTemplateId, setSourceTemplateId] = useState("");
  const [mode, setMode] = useState<EditMode>("FIRST_VERSION");
  const [aiDraft, setAiDraft] = useState<AiDraft | null>(null);
  const [aiNote, setAiNote] = useState("");
  const [assisting, setAssisting] = useState(false);
	const [operationImageUrls, setOperationImageUrls] = useState<OperationImages>({});
  const editorFormRef = useRef<HTMLFormElement>(null);

  function chooseProduct(product: Product | undefined) {
    setSelectedProductId(product?.id ?? "");
    setProductQuery(product ? productLabel(product) : "");
    setSourceTemplateId("");
    setAiDraft(null); setAiNote("");
		setOperationImageUrls({});
    setMode(product && state.data?.templates.some((template) => template.productId === product.id) ? "REVISION" : "FIRST_VERSION");
  }

  function startRevision(template: ProcessCardTemplate) {
    const product = state.data?.products.find((item) => item.id === template.productId);
    chooseProduct(product);
    setSourceTemplateId(template.id);
		setOperationImageUrls(readOperationImages(template));
    setMode("REVISION");
    document.getElementById("process-card-editor")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const product = state.data?.products.find((item) => item.id === selectedProductId);
    const sourceTemplate = state.data?.templates.find((template) => template.id === sourceTemplateId);
    if (!product) { setError(new Error("请从产品提示列表中选择产品。")); return; }
    if (mode === "REVISION" && !sourceTemplate) { setError(new Error("请选择需要修订的历史版本。")); return; }

    const form = new FormData(event.currentTarget);
    const operationParameters = Object.fromEntries(
      PROCESS_STEPS.map((step) => [step.code, String(form.get(`operation-${step.code}`) ?? "").trim()])
    );
		const operationImages = { ...readOperationImages(sourceTemplate), ...operationImageUrls };
    setSaving(true); setError(null);
    try {
      await api.processCardTemplates.create({
        productId: product.id,
        version: String(form.get("version")).trim(),
        engineeringParameters: String(form.get("engineeringParameters")).trim(),
			operationParameters: JSON.stringify({ ...operationParameters, _operationImages: operationImages }),
        createdBy: operatorCode
      });
      setSourceTemplateId("");
      setMode("REVISION");
      await state.reload();
    } catch (caught) { setError(caught); } finally { setSaving(false); }
  }

	async function uploadOperationImage(code: ProcessStepCode, file: File) {
		setSaving(true); setError(null);
		try {
			const uploaded = await api.files.upload("PROCESS_CARD_IMAGE", file);
			setOperationImageUrls((current) => ({ ...current, [code]: [...(current[code] ?? []), uploaded.url] }));
		}
		catch (caught) { setError(caught); } finally { setSaving(false); }
	}

  async function suggest() {
    const product = state.data?.products.find((item) => item.id === selectedProductId);
    const form = editorFormRef.current;
    if (!product || !form) return;
    const values = new FormData(form);
    const currentOperationParameters = Object.fromEntries(
      PROCESS_STEPS.map((step) => [step.code, String(values.get(`operation-${step.code}`) ?? "").trim()])
    );
    setAssisting(true); setError(null);
    try {
      const suggestion = await api.processCardTemplates.suggest({
        productId: product.id,
        sourceTemplateId: sourceTemplateId || undefined,
        currentEngineeringParameters: String(values.get("engineeringParameters") ?? "").trim(),
        currentOperationParameters: JSON.stringify(currentOperationParameters),
        operatorCode
      });
      setAiDraft({ ...suggestion, version: String(values.get("version") ?? "").trim(), requestId: Date.now() });
      setAiNote(suggestion.note || "AI 已生成草稿，请结合试制与首件结果复核后再保存。");
    } catch (caught) { setError(caught); } finally { setAssisting(false); }
  }

  async function publish(id: string) {
    setSaving(true); setError(null);
    try { await api.processCardTemplates.publish(id, operatorCode); await state.reload(); }
    catch (caught) { setError(caught); } finally { setSaving(false); }
  }

  if (state.loading) return <LoadingState label="正在加载产品工艺模板" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const data = state.data!;
	const selectedProduct = data.products.find((product) => product.id === selectedProductId);
	const productTemplates = selectedProduct ? data.templates.filter((template) => template.productId === selectedProduct.id) : [];
	const normalizedProductQuery = productQuery.trim().toLowerCase();
	const filteredProducts = data.products.filter((product) => {
		const searchable = [product.code, product.name, product.specification, product.material, product.routeType]
			.filter(Boolean).join(" ").toLowerCase();
		return !normalizedProductQuery || searchable.includes(normalizedProductQuery);
	});
	const sourceTemplate = productTemplates.find((template) => template.id === sourceTemplateId);
	const sourceOperationImages = readOperationImages(sourceTemplate);
  const operationParameters = aiDraft?.operationParameters ?? readOperationParameters(sourceTemplate);
  const isRevision = mode === "REVISION";

  return <>
    <PageHeader title="产品工艺模板" description="选择产品后建立首版，或基于历史版本修订。发布只切换当前生效版本，历史版本始终保留追溯。" />
    {error && <ErrorNotice error={error} />}

    <section className="section-block process-card-picker">
      <div className="section-heading"><div><h2>1. 选择产品</h2><p>可按产品编号、名称、规格或材质检索并点选；历史停用产品仅用于维护旧工艺。</p></div></div>
		<Field label="产品" required hint="可按产品编号、名称、规格或材质检索，再从结果中选择。">
        <input list="process-card-products" value={productQuery} onChange={(event) => {
			const matched = data.products.find((product) => productLabel(product) === event.target.value || legacyProductLabel(product) === event.target.value);
          setProductQuery(event.target.value);
          if (matched) chooseProduct(matched);
          else if (!event.target.value) chooseProduct(undefined);
			}} placeholder="输入产品编号、名称、规格或材质" autoComplete="off" />
			<datalist id="process-card-products">{data.products.map((product) => <option key={product.id} value={productLabel(product)} />)}</datalist>
		</Field>
		<div className="process-card-product-results" aria-label="工艺检索结果">
			{filteredProducts.slice(0, 12).map((product) => {
				const versionCount = data.templates.filter((template) => template.productId === product.id).length;
				return <button key={product.id} className={selectedProductId === product.id ? "selected" : ""} type="button" onClick={() => chooseProduct(product)}>
					<div><strong>{product.code} · {product.name}</strong><span>{product.specification ?? "未填规格"} · {product.material ?? "未填材质"}</span></div>
					<small>{product.routeType} · {versionCount} 个版本{product.active ? "" : " · 历史"}</small>
				</button>;
			})}
			{filteredProducts.length === 0 && <p>未找到匹配产品，请调整产品名称、规格或材质关键词。</p>}
		</div>
      {selectedProduct && <div className="process-card-selection"><strong>{selectedProduct.code} · {selectedProduct.name}</strong><span>{selectedProduct.routeType} · {productTemplates.length} 个历史版本</span></div>}
    </section>

    {selectedProduct && <section id="process-card-editor" className="section-block process-card-editor">
      <div className="section-heading"><div><h2>2. 建立版本</h2><p>{productTemplates.length ? "有历史版本时建议基于旧版修订；首版尚未发布前也可继续新增草稿。" : "当前产品尚无工艺模板，请先建立首版草稿。"}</p></div></div>
      <div className="process-card-mode" role="group" aria-label="建卡方式">
        <button className={`button ${!isRevision ? "button-primary" : "button-secondary"}`} type="button" onClick={() => { setMode("FIRST_VERSION"); setSourceTemplateId(""); setAiDraft(null); setAiNote(""); }}><FilePlus2 aria-hidden="true" />新建首版</button>
        <button className={`button ${isRevision ? "button-primary" : "button-secondary"}`} type="button" disabled={productTemplates.length === 0} onClick={() => { setMode("REVISION"); setAiDraft(null); setAiNote(""); }}><Copy aria-hidden="true" />基于历史版本修订</button>
      </div>

      <div className="process-card-ai-action"><button className="button button-secondary" type="button" disabled={assisting} onClick={suggest}><Sparkles aria-hidden="true" />{assisting ? "AI 正在生成" : "AI 生成工艺建议"}</button><span>仅生成可编辑草稿，不会自动保存或发布。</span></div>
      {aiNote && <div className="info-notice process-card-ai-note"><Sparkles aria-hidden="true" /><span>{aiNote}</span></div>}

      <form ref={editorFormRef} key={`${selectedProduct.id}-${mode}-${sourceTemplate?.id ?? "blank"}-${aiDraft?.requestId ?? "manual"}`} onSubmit={create}>
        {isRevision && <Field label="修订依据" required hint="选择后会自动带入旧版内容，保存时形成新的草稿版本。">
			<select value={sourceTemplateId} onChange={(event) => { const template = productTemplates.find((item) => item.id === event.target.value); setSourceTemplateId(event.target.value); setOperationImageUrls(readOperationImages(template)); setAiDraft(null); setAiNote(""); }} required>
            <option value="">请选择历史版本</option>
            {productTemplates.map((template) => <option key={template.id} value={template.id}>{template.version} · {template.status === "PUBLISHED" ? "当前已发布" : template.status === "DRAFT" ? "草稿" : "已退役"}</option>)}
          </select>
        </Field>}
        <div className="form-grid form-grid-two process-card-basics">
          <Field label="新版本号" required hint="例如 V1.1、V2.0；同一产品不可重复。"><input name="version" required placeholder="例如 V1.1" maxLength={64} defaultValue={aiDraft?.version || (sourceTemplate ? `${sourceTemplate.version}-R1` : productTemplates.length ? "V1.1" : "V1")} /></Field>
          <Field label="工程总要求" required hint="填写材质、关键质量点、通用检验与特殊要求。"><textarea name="engineeringParameters" required rows={4} maxLength={2000} placeholder="例如：CF8；首件确认尺寸；关键密封面不得碰伤。" defaultValue={aiDraft?.engineeringParameters ?? sourceTemplate?.engineeringParameters ?? ""} /></Field>
        </div>
        <div className="process-card-operations"><div><h3>3. 分工序工艺要求</h3><p>按实际涉及工序填写；组树可上传多张标准图，其余工序保留一张关键工艺图。</p></div>
		  <div className="process-parameter-grid">{PROCESS_STEPS.map((step) => {
			const images = operationImageUrls[step.code] ?? sourceOperationImages[step.code] ?? [];
			const canAddImage = step.code === "TREE_ASSEMBLY" || images.length === 0;
			return <div key={step.code} className="process-operation-card"><Field label={step.label} hint={step.hint}><textarea name={`operation-${step.code}`} rows={3} maxLength={1200} placeholder={step.hint} defaultValue={operationParameters[step.code]} /></Field><div className="process-operation-image">{images.length ? <div className="process-operation-image-list">{images.map((url, index) => <span key={url}><img src={url} alt={`${step.label} 工艺示意图 ${index + 1}`} />{step.code === "TREE_ASSEMBLY" && <button type="button" className="icon-button compact" aria-label={`移除第 ${index + 1} 张组树图`} onClick={() => setOperationImageUrls((current) => ({ ...current, [step.code]: images.filter((item) => item !== url) }))}>×</button>}</span>)}</div> : <span>未附工艺图</span>}{canAddImage && <label className="button button-secondary button-small"><ImagePlus aria-hidden="true" />{step.code === "TREE_ASSEMBLY" && images.length ? "追加组树图" : "上传工艺图"}<input type="file" accept="image/jpeg,image/png,image/webp" hidden disabled={saving} onChange={(event) => { const file = event.target.files?.[0]; if (file) void uploadOperationImage(step.code, file); }} /></label>}</div></div>;
		  })}</div>
        </div>
        <SubmitActions pending={saving} submitLabel={isRevision ? "保存修订版草稿" : "保存首版草稿"} onCancel={() => { setSourceTemplateId(""); setMode(productTemplates.length ? "REVISION" : "FIRST_VERSION"); }} />
      </form>
    </section>}

    <section className="section-block"><div className="section-heading"><div><h2>{selectedProduct ? "该产品的版本记录" : "版本记录"}</h2><p>{selectedProduct ? "发布新版本后，原已发布版本自动退役；版本、参数和发布人均可追溯。" : "请先选择产品，再查看和修订对应工艺。"}</p></div></div>
      {!selectedProduct ? <EmptyState title="尚未选择产品" description="先选择一个产品，系统只显示它的工艺版本，避免在全库中查找。" /> : productTemplates.length === 0 ? <EmptyState title="尚无工艺模板" description="点击“新建首版”，先保存草稿；工艺探索过程中可多次修订后再发布。" /> : <div className="table-scroll"><table><thead><tr><th>版本</th><th>状态</th><th>工程总要求</th><th>创建 / 发布</th><th className="actions-cell">操作</th></tr></thead><tbody>{productTemplates.map((template) => <tr key={template.id}><td><strong>{template.version}</strong><small>{template.templateNo}</small></td><td><StatusBadge value={template.status} /></td><td className="truncate-cell">{template.engineeringParameters}</td><td><small>创建 {formatDate(template.createdAt, true)}</small><small>{template.publishedBy ? `发布 ${template.publishedBy}` : "尚未发布"}</small></td><td className="actions-cell"><button className="button button-secondary button-small" type="button" onClick={() => startRevision(template)}><Copy aria-hidden="true" />以此版本修订</button>{template.status !== "PUBLISHED" && <button className="button button-primary button-small" type="button" disabled={saving} onClick={() => publish(template.id)}><Check aria-hidden="true" />发布</button>}</td></tr>)}</tbody></table></div>}
    </section>
  </>;
}
