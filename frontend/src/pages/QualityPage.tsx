import { FormEvent, useState } from "react";
import { ClipboardCheck, Plus, RefreshCw, Wrench } from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  formatDate,
  formatQuantity,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { QualityInspection } from "../types";

export function QualityPage({ operatorCode }: { operatorCode: string }) {
  const state = useAsyncData(async () => {
    const [inspections, tasks] = await Promise.all([api.quality.list(), api.tasks.list()]);
    return { inspections, tasks };
  }, []);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [disposition, setDisposition] = useState<QualityInspection | null>(null);

  async function inspect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    setError(null);
    try {
      await api.quality.inspect({
        operationId: crypto.randomUUID(),
        taskId: String(form.get("taskId")),
        inspectedQuantity: Number(form.get("inspectedQuantity")),
        acceptedQuantity: Number(form.get("acceptedQuantity")),
        rejectedQuantity: Number(form.get("rejectedQuantity")),
        defectCode: String(form.get("defectCode") || ""),
        inspectorCode: operatorCode,
        remark: String(form.get("remark") || "")
      });
      formElement.reset();
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function dispose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!disposition) return;
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    try {
      await api.quality.dispose(disposition.id, {
        decision: String(form.get("decision")),
        quantity: Number(form.get("quantity")),
        reason: String(form.get("reason")),
        decidedBy: operatorCode
      });
      setDisposition(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  if (state.loading) return <LoadingState label="正在加载质量台账" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const inspections = state.data?.inspections ?? [];
  const tasks = (state.data?.tasks ?? []).filter((task) => task.goodQuantity > 0);

  return (
    <>
      <PageHeader
        title="质量管理"
        description="检验、缺陷与不合格品处置流水"
        action={
          <button className="icon-button" type="button" onClick={state.reload} aria-label="刷新">
            <RefreshCw aria-hidden="true" />
          </button>
        }
      />
      {error != null && <ErrorNotice error={error} />}

      <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>新建检验</h2><p>检验总数不得超过任务合格数</p></div>
            <ClipboardCheck aria-hidden="true" />
          </header>
          <form className="compact-form" onSubmit={inspect}>
            <div className="form-grid">
              <Field label="生产任务" required>
                <select name="taskId" required defaultValue="">
                  <option value="" disabled>选择有产出的任务</option>
                  {tasks.map((task) => (
                    <option key={task.id} value={task.id}>
                      {task.taskNo} · {task.operationName} · 合格 {formatQuantity(task.goodQuantity)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="检验数量" required>
                <input name="inspectedQuantity" type="number" min="0.001" step="0.001" required />
              </Field>
              <Field label="合格数量" required>
                <input name="acceptedQuantity" type="number" min="0" step="0.001" defaultValue="0" required />
              </Field>
              <Field label="不合格数量" required>
                <input name="rejectedQuantity" type="number" min="0" step="0.001" defaultValue="0" required />
              </Field>
              <Field label="缺陷代码">
                <input name="defectCode" maxLength={64} />
              </Field>
              <Field label="备注">
                <input name="remark" maxLength={500} />
              </Field>
            </div>
            <button className="button button-primary" disabled={saving || tasks.length === 0}>
              <Plus aria-hidden="true" />{saving ? "提交中" : "提交检验"}
            </button>
          </form>
        </div>

        <div className="table-pane">
          <header className="section-heading">
            <div><h2>检验台账</h2><p>{inspections.length} 条不可覆盖记录</p></div>
          </header>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>检验单</th><th>任务</th><th>数量</th><th>结果</th>
                  <th>缺陷</th><th>检验员</th><th>时间</th><th>处置</th>
                </tr>
              </thead>
              <tbody>
                {inspections.map((item) => {
                  const remaining = item.rejectedQuantity - item.disposedQuantity;
                  return (
                    <tr key={item.id}>
                      <td className="primary-cell">{item.inspectionNo}</td>
                      <td>{item.taskId.slice(0, 8)}</td>
                      <td>
                        <strong>{formatQuantity(item.inspectedQuantity)}</strong>
                        <small>合格 {formatQuantity(item.acceptedQuantity)} / 不合格 {formatQuantity(item.rejectedQuantity)}</small>
                      </td>
                      <td><StatusBadge value={item.result} /></td>
                      <td>{item.defectCode || "—"}</td>
                      <td>{item.inspectorCode}</td>
                      <td>{formatDate(item.occurredAt, true)}</td>
                      <td className="actions-cell">
                        {remaining > 0 ? (
                          <button className="button button-secondary button-small" onClick={() => setDisposition(item)}>
                            <Wrench aria-hidden="true" />处置 {formatQuantity(remaining)}
                          </button>
                        ) : <span className="muted">已闭环</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {disposition && (
        <Modal
          title={`不合格处置 · ${disposition.inspectionNo}`}
          description={`剩余待处置 ${formatQuantity(disposition.rejectedQuantity - disposition.disposedQuantity)}`}
          onClose={() => !saving && setDisposition(null)}
          width="small"
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={dispose}>
            <div className="form-grid">
              <Field label="处置方式" required>
                <select name="decision" defaultValue="REWORK">
                  <option value="REWORK">返工</option>
                  <option value="SCRAP">报废</option>
                  <option value="CONCESSION">让步接收</option>
                </select>
              </Field>
              <Field label="处置数量" required>
                <input
                  name="quantity"
                  type="number"
                  min="0.001"
                  step="0.001"
                  max={disposition.rejectedQuantity - disposition.disposedQuantity}
                  required
                />
              </Field>
            </div>
            <Field label="处置原因" required>
              <input name="reason" maxLength={500} required />
            </Field>
            <SubmitActions pending={saving} submitLabel="确认处置" onCancel={() => setDisposition(null)} />
          </form>
        </Modal>
      )}
    </>
  );
}
