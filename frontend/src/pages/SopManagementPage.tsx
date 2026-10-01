import { FormEvent, useState } from "react";
import { ClipboardCheck, Plus, RefreshCw, ShieldAlert, Sparkles } from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  formatDate,
  LoadingState,
  Modal,
  PageHeader,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AiSopDraft } from "../types";

export function SopManagementPage({ operatorCode }: { operatorCode: string }) {
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [aiDraft, setAiDraft] = useState<AiSopDraft | null>(null);
  const [draftTarget, setDraftTarget] = useState({ operationCode: "", operationName: "" });
  const [sopSeed, setSopSeed] = useState<{ draft: AiSopDraft; operationCode: string; operationName: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(api.sops.list);
  const sops = state.data ?? [];
  const selected =
    sops.find((sop) => sop.operationCode === selectedCode) ?? sops[0] ?? null;

  async function publish(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const steps = String(form.get("steps"))
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [title, ...instruction] = line.split("|");
        return { title: title.trim(), instruction: instruction.join("|").trim() };
      });
    const qualityPoints = String(form.get("qualityPoints"))
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean);
    const keyParameters = String(form.get("keyParameters"))
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [name, ...value] = line.split("|");
        return { name: name.trim(), value: value.join("|").trim() };
      })
      .filter((parameter) => parameter.name && parameter.value)
      .slice(0, 5);
    setSaving(true);
    setError(null);
    try {
      const created = await api.sops.publish({
        operationCode: String(form.get("operationCode")),
        operationName: String(form.get("operationName")),
        version: String(form.get("version")),
        safetyNotice: String(form.get("safetyNotice")),
        preparationNote: String(form.get("preparationNote")),
        steps,
        qualityPoints,
        keyParameters,
        updatedBy: operatorCode
      });
      setSelectedCode(created.operationCode);
      setSopSeed(null);
      setCreating(false);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function generateDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const target = { operationCode: String(form.get("operationCode")).trim(), operationName: String(form.get("operationName")).trim() };
    setSaving(true);
    setError(null);
    try {
      setDraftTarget(target);
      setAiDraft(await api.aiAssistant.sopDraft({ operationCode: target.operationCode, operationName: target.operationName, engineeringRequirements: String(form.get("engineeringRequirements") || ""), routeType: String(form.get("routeType") || ""), operatorCode }));
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  function applyDraft() {
    if (!aiDraft) return;
    setSopSeed({ draft: aiDraft, ...draftTarget });
    setDrafting(false);
    setCreating(true);
  }

  if (state.loading) return <LoadingState label="正在加载作业指导书" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  return (
    <>
      <PageHeader
        title="SOP 作业指导"
        description="发布工序标准、安全提醒和质量自检点，一线工作台自动使用最新版本。"
        action={
          <div className="header-actions">
            <button className="button button-secondary" type="button" onClick={() => { setAiDraft(null); setDrafting(true); }}><Sparkles aria-hidden="true" />AI SOP 草稿</button>
            <button
              className="button button-primary"
              type="button"
              onClick={() => setCreating(true)}
            >
              <Plus aria-hidden="true" />
              发布新版本
            </button>
            <button
              className="icon-button"
              type="button"
              onClick={state.reload}
              aria-label="刷新 SOP"
              title="刷新"
            >
              <RefreshCw aria-hidden="true" />
            </button>
          </div>
        }
      />
      {error != null && <ErrorNotice error={error} />}

      <div className="sop-management-layout">
        <aside className="sop-catalog" aria-label="工序 SOP">
          {sops.map((sop) => (
            <button
              type="button"
              key={sop.id}
              className={selected?.id === sop.id ? "active" : ""}
              onClick={() => setSelectedCode(sop.operationCode)}
            >
              <span className="sop-number">{sop.steps.length}</span>
              <span>
                <strong>{sop.operationName}</strong>
                <small>
                  {sop.operationCode} · {sop.version}
                </small>
              </span>
            </button>
          ))}
        </aside>

        {selected && (
          <section className="sop-document">
            <header>
              <div>
                <span className="eyebrow">已发布 · {selected.version}</span>
                <h2>{selected.operationName}</h2>
                <p>
                  {selected.operationCode} · {formatDate(selected.updatedAt, true)} ·{" "}
                  {selected.updatedBy}
                </p>
              </div>
              <ClipboardCheck aria-hidden="true" />
            </header>

            <div className="safety-band">
              <ShieldAlert aria-hidden="true" />
              <div>
                <strong>安全要求</strong>
                <p>{selected.safetyNotice}</p>
              </div>
            </div>
            <div className="sop-preparation">
              <strong>工前准备</strong>
              <p>{selected.preparationNote}</p>
            </div>
            {selected.keyParameters?.length > 0 && <div className="document-quality sop-key-parameter-list"><h3>操作工关键参数</h3>{selected.keyParameters.map((parameter) => <p key={`${parameter.name}-${parameter.value}`}><strong>{parameter.name}</strong><span>{parameter.value}</span></p>)}</div>}
            <ol className="sop-steps document-steps">
              {selected.steps.map((step) => (
                <li key={step.stepNo}>
                  <span className="check-button">{step.stepNo}</span>
                  <div>
                    <strong>{step.title}</strong>
                    <p>{step.instruction}</p>
                  </div>
                </li>
              ))}
            </ol>
            <div className="document-quality">
              <h3>质量自检点</h3>
              {selected.qualityPoints.map((point) => (
                <p key={point}>
                  <span aria-hidden="true">✓</span>
                  {point}
                </p>
              ))}
            </div>
          </section>
        )}
      </div>

      {creating && (
        <Modal
          title="发布 SOP 新版本"
          description="同一工序发布后，上一版本自动保留为历史版本。"
          width="large"
          onClose={() => !saving && setCreating(false)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={publish}>
            <div className="form-grid form-grid-two">
              <Field label="工序编码" required>
                <input
                  name="operationCode"
                  defaultValue={sopSeed?.operationCode ?? selected?.operationCode}
                  maxLength={64}
                  required
                  autoFocus
                />
              </Field>
              <Field label="工序名称" required>
                <input
                  name="operationName"
                  defaultValue={sopSeed?.operationName ?? selected?.operationName}
                  maxLength={120}
                  required
                />
              </Field>
              <Field label="版本号" required>
                <input name="version" placeholder="V1.1" maxLength={32} required />
              </Field>
              <Field label="安全要求" required>
                <textarea
                  name="safetyNotice"
                  defaultValue={sopSeed?.draft.safetyNotice ?? selected?.safetyNotice}
                  rows={3}
                  maxLength={1000}
                  required
                />
              </Field>
              <Field label="工前准备" required>
                <textarea
                  name="preparationNote"
                  defaultValue={sopSeed?.draft.preparationNote ?? selected?.preparationNote}
                  rows={3}
                  maxLength={1000}
                  required
                />
              </Field>
              <Field label="操作工关键参数" hint="最多 5 项；每行一项，格式：参数名称|数值或要求。未填写时，操作工端显示 SOP 关键要点。">
                <textarea
                  name="keyParameters"
                  defaultValue={(selected?.keyParameters ?? [])
                    .map((parameter) => `${parameter.name}|${parameter.value}`)
                    .join("\n")}
                  rows={5}
                  maxLength={2900}
                />
              </Field>
              <Field label="作业步骤" required hint="每行一项，格式：步骤标题|详细说明">
                <textarea
                  name="steps"
                  defaultValue={(sopSeed?.draft.steps ?? selected?.steps ?? [])
                    .map((step) => `${step.title}|${step.instruction}`)
                    .join("\n")}
                  rows={7}
                  required
                />
              </Field>
              <Field label="质量自检点" required hint="每行一项">
                <textarea
                  name="qualityPoints"
                  defaultValue={(sopSeed?.draft.qualityPoints ?? selected?.qualityPoints)?.join("\n")}
                  rows={7}
                  required
                />
              </Field>
            </div>
            <SubmitActions
              pending={saving}
              submitLabel="发布版本"
              onCancel={() => setCreating(false)}
            />
          </form>
        </Modal>
      )}
      {drafting && <Modal title="AI SOP 草稿" description="将工程要求整理为可编辑的岗位提示。生成内容不会自动发布，安全参数和质量点必须由工程师复核。" width="large" onClose={() => !saving && setDrafting(false)}>{error != null && <ErrorNotice error={error} />}<form onSubmit={generateDraft}><div className="form-grid form-grid-two"><Field label="工序编码" required><input name="operationCode" defaultValue={selected?.operationCode} maxLength={64} required autoFocus /></Field><Field label="工序名称" required><input name="operationName" defaultValue={selected?.operationName} maxLength={120} required /></Field><Field label="生产路线"><select name="routeType" defaultValue=""><option value="">通用</option><option value="MID_TEMP_WAX">中温蜡</option><option value="LOW_TEMP_WAX">低温蜡</option><option value="SAND_CASTING">砂型</option></select></Field><Field label="工程要求"><textarea name="engineeringRequirements" rows={6} maxLength={2000} placeholder="材质、关键参数、质量重点；未知参数可留空，由工程师后续确认。" /></Field></div><SubmitActions pending={saving} submitLabel="生成可编辑草稿" onCancel={() => setDrafting(false)} /></form>{aiDraft && <div className="ai-draft"><div className="safety-band"><ShieldAlert aria-hidden="true" /><div><strong>安全要求草稿</strong><p>{aiDraft.safetyNotice}</p></div></div><p><strong>工前准备：</strong>{aiDraft.preparationNote}</p><ol>{aiDraft.steps.map((step) => <li key={`${step.title}-${step.instruction}`}><strong>{step.title}</strong><span>{step.instruction}</span></li>)}</ol><p><strong>质量自检：</strong>{aiDraft.qualityPoints.join("；")}</p><p className="ai-caution"><strong>人工核对：</strong>{aiDraft.caution}</p><button type="button" className="button button-primary" onClick={applyDraft}>带入发布表单</button></div>}</Modal>}
    </>
  );
}
