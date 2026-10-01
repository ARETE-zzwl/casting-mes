import { FormEvent, useState } from "react";
import { Check, FileCog, Plus, RefreshCw, RotateCcw, Send, ShieldCheck, X } from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  formatDate,
  LoadingState,
  PageHeader,
  StatusBadge
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser } from "../types";

export function PlatformPage({ operatorCode, user }: { operatorCode: string; user: AccessUser }) {
  const administrator = user.roles.includes("SYSTEM_ADMIN");
  const workflowParticipant = user.permissions.includes("WORKFLOW_MANAGE");
  const state = useAsyncData(async () => {
    const [workflows, configurations, definitions, roles] = await Promise.all([
      workflowParticipant ? api.workflows.list() : Promise.resolve([]),
      administrator ? api.configurations.list() : Promise.resolve([]),
			api.workflows.definitions(),
			administrator ? api.access.roles() : Promise.resolve([])
    ]);
    return { workflows, configurations, definitions, roles };
  }, [operatorCode, administrator, workflowParticipant]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submitWorkflow(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    setError(null);
    try {
      await api.workflows.submit({
        workflowType: String(form.get("workflowType")),
        businessKey: String(form.get("businessKey")),
        title: String(form.get("title")),
        requesterCode: operatorCode,
        requiredApprovals: Number(form.get("requiredApprovals")),
        payload: String(form.get("payload") || "")
      });
      formElement.reset();
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function act(id: string, action: "APPROVE" | "REJECT") {
    setSaving(true);
    setError(null);
    try {
      await api.workflows.act(id, { action, actorCode: operatorCode });
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function createConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    setError(null);
    try {
      await api.configurations.create({
        configType: String(form.get("configType")),
        name: String(form.get("name")),
        version: String(form.get("version")),
        content: String(form.get("content")),
        createdBy: operatorCode
      });
      formElement.reset();
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

	async function createDefinition(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		setSaving(true); setError(null);
		try {
			await api.workflows.createDefinition({ workflowType: String(form.get("workflowType")), name: String(form.get("name")), description: String(form.get("description") || "") || undefined,
				requiredApprovals: Number(form.get("requiredApprovals")), approverRoles: form.getAll("approverRoles").map(String), createdBy: operatorCode });
			formElement.reset(); await state.reload();
		} catch (caught) { setError(caught); } finally { setSaving(false); }
	}

  async function changeConfiguration(id: string, action: "publish" | "rollback") {
    setSaving(true);
    setError(null);
    try {
      if (action === "publish") await api.configurations.publish(id, operatorCode);
      else await api.configurations.rollback(id, operatorCode);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  if (state.loading) return <LoadingState label="正在加载审批与配置" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const workflows = state.data?.workflows ?? [];
  const configurations = state.data?.configurations ?? [];
	const definitions = state.data?.definitions ?? [];
	const roles = state.data?.roles ?? [];

  return (
    <>
      <PageHeader
        title="审批与配置"
        description="动态会签、职责分离与版本化配置发布"
        action={
          <button className="icon-button" type="button" onClick={state.reload} aria-label="刷新">
            <RefreshCw aria-hidden="true" />
          </button>
        }
      />
      {error != null && <ErrorNotice error={error} />}

		<section className="split-section">
			{administrator && <div className="editor-pane">
				<header className="section-heading"><div><h2>自定义审批模板</h2><p>审批角色和会签人数按模板执行。</p></div><ShieldCheck aria-hidden="true" /></header>
				<form className="compact-form" onSubmit={createDefinition}><div className="form-grid">
					<Field label="模板编码" required><input name="workflowType" pattern="[A-Za-z0-9_]+" maxLength={64} placeholder="例如 MOLD_REPAIR_APPROVAL" required /></Field>
					<Field label="模板名称" required><input name="name" maxLength={160} required /></Field>
					<Field label="所需同意人数" required><input name="requiredApprovals" type="number" min="1" max="5" defaultValue="1" required /></Field>
				</div><Field label="模板说明"><textarea name="description" rows={2} maxLength={500} /></Field><fieldset className="permission-groups"><legend>允许审批的角色</legend><div className="choice-grid">{roles.map((role) => <label key={role.code} className="permission-choice"><input type="checkbox" name="approverRoles" value={role.code} /><span><strong>{role.name}</strong><small>{role.code}</small></span></label>)}</div></fieldset><button className="button button-primary" disabled={saving}><Plus aria-hidden="true" />创建审批模板</button></form>
			</div>}
			<div className="table-pane"><header className="section-heading"><div><h2>审批模板目录</h2><p>{definitions.length} 个模板；模板会覆盖发起时的会签人数。</p></div></header><div className="table-scroll"><table><thead><tr><th>类型</th><th>名称</th><th>会签</th><th>审批角色</th><th>状态</th></tr></thead><tbody>{definitions.map((item) => <tr key={item.id}><td className="primary-cell">{item.workflowType}</td><td><strong>{item.name}</strong><small>{item.description || "-"}</small></td><td>{item.requiredApprovals} 人</td><td>{item.approverRoles.length ? item.approverRoles.join("、") : "未限制"}</td><td><StatusBadge value={item.active ? "ACTIVE" : "INACTIVE"} /></td></tr>)}</tbody></table></div></div>
		</section>

      {workflowParticipant && <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>发起审批</h2><p>申请人不能审批自己的申请</p></div>
            <Send aria-hidden="true" />
          </header>
          <form className="compact-form" onSubmit={submitWorkflow}>
            <div className="form-grid">
              <Field label="审批类型" required>
					<select name="workflowType">{definitions.map((definition) => <option key={definition.id} value={definition.workflowType}>{definition.name} · {definition.requiredApprovals} 人会签</option>)}</select>
              </Field>
              <Field label="业务单号" required><input name="businessKey" required maxLength={128} /></Field>
              <Field label="审批标题" required><input name="title" required maxLength={200} /></Field>
              <Field label="所需同意人数" required>
                <input name="requiredApprovals" type="number" min="1" max="5" defaultValue="1" required />
              </Field>
            </div>
            <Field label="申请数据"><textarea name="payload" rows={3} maxLength={4000} /></Field>
            <button className="button button-primary" disabled={saving}>
              <Plus aria-hidden="true" />提交审批
            </button>
          </form>
        </div>
        <div className="table-pane">
          <header className="section-heading">
            <div><h2>审批待办</h2><p>{workflows.filter((item) => item.status === "PENDING").length} 项待处理</p></div>
          </header>
          <div className="table-scroll">
            <table>
              <thead><tr><th>审批单</th><th>标题</th><th>申请人</th><th>进度</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                {workflows.map((item) => (
                  <tr key={item.id}>
                    <td className="primary-cell">{item.requestNo}</td>
                    <td><strong>{item.title}</strong><small>{item.businessKey}</small></td>
                    <td>{item.requesterCode}</td>
                    <td>{item.approvalCount} / {item.requiredApprovals}</td>
                    <td><StatusBadge value={item.status} /></td>
                    <td className="actions-cell action-pair">
                      {item.status === "PENDING" && item.requesterCode !== operatorCode && (administrator || definitions.find((definition) => definition.workflowType === item.workflowType)?.approverRoles.some((role) => user.roles.includes(role))) && (
                        <>
                          <button className="icon-button compact success" disabled={saving} onClick={() => act(item.id, "APPROVE")} aria-label={`同意 ${item.requestNo}`}>
                            <Check aria-hidden="true" />
                          </button>
                          <button className="icon-button compact danger" disabled={saving} onClick={() => act(item.id, "REJECT")} aria-label={`拒绝 ${item.requestNo}`}>
                            <X aria-hidden="true" />
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>}

      {administrator && <section className="split-section">
        <div className="editor-pane">
          <header className="section-heading">
            <div><h2>新建配置包</h2><p>内容版本发布后不允许覆盖</p></div>
            <FileCog aria-hidden="true" />
          </header>
          <form className="compact-form" onSubmit={createConfiguration}>
            <div className="form-grid">
              <Field label="配置类型" required>
                <select name="configType" defaultValue="FORM">
                  <option value="ROUTE">工艺路线</option>
                  <option value="FORM">动态表单</option>
                  <option value="FORMULA">计算公式</option>
                  <option value="LABEL">标签模板</option>
                  <option value="POLICY">业务策略</option>
                </select>
              </Field>
              <Field label="配置名称" required><input name="name" required maxLength={160} /></Field>
              <Field label="版本" required><input name="version" defaultValue="V1" required maxLength={32} /></Field>
            </div>
            <Field label="配置内容" required>
              <textarea name="content" rows={6} required maxLength={4000} defaultValue={'{"fields": []}'} />
            </Field>
            <button className="button button-primary" disabled={saving}>
              <Plus aria-hidden="true" />保存草稿
            </button>
          </form>
        </div>
        <div className="table-pane">
          <header className="section-heading">
            <div><h2>配置版本</h2><p>{configurations.length} 个历史版本</p></div>
          </header>
          <div className="table-scroll">
            <table>
              <thead><tr><th>配置包</th><th>类型/名称</th><th>版本</th><th>状态</th><th>创建人</th><th>时间</th><th>操作</th></tr></thead>
              <tbody>
                {configurations.map((item) => (
                  <tr key={item.id}>
                    <td className="primary-cell">{item.packageNo}</td>
                    <td><strong>{item.name}</strong><small>{item.configType}</small></td>
                    <td>{item.version}</td>
                    <td><StatusBadge value={item.status} /></td>
                    <td>{item.createdBy}</td>
                    <td>{formatDate(item.createdAt, true)}</td>
                    <td className="actions-cell">
                      {item.status === "DRAFT" && (
                        <button className="button button-primary button-small" disabled={saving} onClick={() => changeConfiguration(item.id, "publish")}>
                          <Send aria-hidden="true" />发布
                        </button>
                      )}
                      {item.status === "PUBLISHED" && (
                        <button className="button button-secondary button-small" disabled={saving} onClick={() => changeConfiguration(item.id, "rollback")}>
                          <RotateCcw aria-hidden="true" />回退
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>}
    </>
  );
}
