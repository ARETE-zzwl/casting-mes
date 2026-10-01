import { FormEvent, useState } from "react";
import {
  Bell,
  Boxes,
  Check,
  CircleUserRound,
  PlugZap,
  Plus,
  RefreshCw,
  RotateCw
} from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  formatDate,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { IntegrationJob, ResourceAsset } from "../types";

type View = "resources" | "organization" | "notifications" | "integration";
type Action =
  | { kind: "occupy"; asset: ResourceAsset }
  | { kind: "release"; asset: ResourceAsset }
  | { kind: "fail"; job: IntegrationJob }
  | null;

export function AdministrationPage({ operatorCode }: { operatorCode: string }) {
  const [view, setView] = useState<View>("resources");
  const [action, setAction] = useState<Action>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(async () => {
    const [overview, resources, occupations, units, members, roles, notifications, jobs] =
      await Promise.all([
        api.operations.overview(),
        api.resources.list(),
        api.resources.occupations(),
        api.organization.units(),
        api.organization.members(),
		api.access.roles(),
        api.notifications.list(operatorCode),
        api.integration.list()
      ]);
      return { overview, resources, occupations, units, members, roles, notifications, jobs };
  }, [operatorCode]);

  async function execute(operation: () => Promise<unknown>, form?: HTMLFormElement) {
    setSaving(true);
    setError(null);
    try {
      await operation();
      form?.reset();
      setAction(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  function submitResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const lifeLimit = String(form.get("lifeLimit") || "");
    return execute(
      () =>
        api.resources.register({
          assetCode: String(form.get("assetCode")),
          assetName: String(form.get("assetName")),
          assetType: String(form.get("assetType")),
          locationCode: String(form.get("locationCode") || ""),
          lifeLimit: lifeLimit ? Number(lifeLimit) : undefined
        }),
      formElement
    );
  }

  function submitUnit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    return execute(
      () =>
        api.organization.createUnit({
          code: String(form.get("code")),
          name: String(form.get("name")),
          unitType: String(form.get("unitType")),
          parentCode: String(form.get("parentCode") || "")
        }),
      formElement
    );
  }

  function submitMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    return execute(
      () =>
        api.organization.createMember({
          employeeCode: String(form.get("employeeCode")),
          name: String(form.get("name")),
          unitCode: String(form.get("unitCode")),
          roleCode: String(form.get("roleCode"))
        }),
      formElement
    );
  }

	function maintainMember(member: { employeeCode: string; name: string; unitCode: string; roleCode: string; active: boolean }) {
		return execute(() => api.organization.maintainMember(member.employeeCode, {
			name: member.name, unitCode: member.unitCode, roleCode: member.roleCode, active: !member.active
		}));
	}

  function submitNotification(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    return execute(
      () =>
        api.notifications.create({
          recipientCode: String(form.get("recipientCode")),
          category: String(form.get("category")),
          title: String(form.get("title")),
          content: String(form.get("content")),
          businessLink: String(form.get("businessLink") || "")
        }),
      formElement
    );
  }

  function submitIntegration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    return execute(
      () =>
        api.integration.submit({
          operationId: crypto.randomUUID(),
          interfaceCode: String(form.get("interfaceCode")),
          businessKey: String(form.get("businessKey")),
          direction: String(form.get("direction")),
          payload: String(form.get("payload"))
        }),
      formElement
    );
  }

  function submitAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!action) return;
    const form = new FormData(event.currentTarget);
    if (action.kind === "occupy") {
      return execute(() =>
        api.resources.occupy(action.asset.id, {
          businessKey: String(form.get("businessKey")),
          operatorCode,
          note: String(form.get("note") || "")
        })
      );
    }
    if (action.kind === "release") {
      return execute(() =>
        api.resources.release(action.asset.id, {
          operatorCode,
          consumeLife: form.get("consumeLife") === "on"
        })
      );
    }
    return execute(() => api.integration.fail(action.job.id, String(form.get("error"))));
  }

  if (state.loading) return <LoadingState label="正在加载平台运维数据" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  const data = state.data!;
  const overviewItems = [
    ["接口失败", data.overview.failedIntegrationJobs],
    ["未读消息", data.overview.unreadNotifications],
    ["资源占用", data.overview.occupiedResources],
    ["寿命耗尽", data.overview.exhaustedResources],
    ["待审流程", data.overview.pendingWorkflows],
    ["已发配置", data.overview.publishedConfigurations]
  ];

  return (
    <>
      <PageHeader
        title="平台运维"
        description="组织、资源、消息与集成任务的统一控制台"
        action={
          <button className="icon-button" type="button" onClick={state.reload} aria-label="刷新">
            <RefreshCw aria-hidden="true" />
          </button>
        }
      />
      {error != null && <ErrorNotice error={error} />}
      <section className="ops-metric-strip" aria-label="平台运行指标">
        {overviewItems.map(([label, value]) => (
          <div key={label}>
            <span>{label}</span>
            <strong>{value}</strong>
          </div>
        ))}
      </section>
      <div className="filter-bar">
        <div className="segmented-control" aria-label="平台运维视图">
          {([
            ["resources", "资源台账"],
            ["organization", "组织成员"],
            ["notifications", "消息中心"],
            ["integration", "接口任务"]
          ] as const).map(([key, label]) => (
            <button
              key={key}
              type="button"
              className={view === key ? "active" : ""}
              onClick={() => setView(key)}
            >
              {label}
            </button>
          ))}
        </div>
        <span className="muted">数据生成于 {formatDate(data.overview.generatedAt, true)}</span>
      </div>

      {view === "resources" && (
		<section className="split-section">
          <div className="editor-pane">
            <SectionTitle icon={<Boxes />} title="登记资源" hint="模具、设备、炉釜与载具统一编码" />
            <form className="compact-form" onSubmit={submitResource}>
              <div className="form-grid">
                <Field label="资源编码" required><input name="assetCode" required /></Field>
                <Field label="资源名称" required><input name="assetName" required /></Field>
                <Field label="资源类型" required>
                  <select name="assetType" defaultValue="MOLD">
                    <option value="MOLD">模具</option><option value="EQUIPMENT">设备</option>
                    <option value="TREE">组树</option><option value="FURNACE">炉/釜</option>
                    <option value="CARRIER">周转载具</option>
                  </select>
                </Field>
                <Field label="位置"><input name="locationCode" /></Field>
                <Field label="寿命上限"><input name="lifeLimit" type="number" min="1" /></Field>
              </div>
              <button className="button button-primary" disabled={saving}><Plus />登记</button>
            </form>
          </div>
          <TablePane title="资源状态" count={data.resources.length}>
            <thead><tr><th>编码/名称</th><th>类型</th><th>位置</th><th>寿命</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              {data.resources.map((asset) => (
                <tr key={asset.id}>
                  <td><strong>{asset.assetCode}</strong><small>{asset.assetName}</small></td>
                  <td>{asset.assetType}</td><td>{asset.locationCode || "—"}</td>
                  <td>{asset.lifeUsed} / {asset.lifeLimit ?? "不限"}</td>
                  <td><StatusBadge value={asset.status} /></td>
                  <td className="actions-cell">
                    {asset.status === "AVAILABLE" && <button className="button button-primary button-small" onClick={() => setAction({ kind: "occupy", asset })}>占用</button>}
                    {asset.status === "OCCUPIED" && <button className="button button-secondary button-small" onClick={() => setAction({ kind: "release", asset })}>释放</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </TablePane>
        </section>
      )}

      {view === "organization" && (
        <section className="split-section">
          <div className="editor-pane">
            <SectionTitle icon={<CircleUserRound />} title="组织与成员" hint="先建立组织，再登记成员" />
            <form className="compact-form stacked-form" onSubmit={submitUnit}>
              <h3>新增组织</h3>
              <div className="form-grid">
                <Field label="组织编码" required><input name="code" required /></Field>
                <Field label="组织名称" required><input name="name" required /></Field>
                <Field label="类型" required><select name="unitType"><option value="COMPANY">公司</option><option value="FACTORY">工厂</option><option value="WORKSHOP">车间</option><option value="TEAM">班组</option></select></Field>
                <Field label="上级编码"><input name="parentCode" /></Field>
              </div>
              <button className="button button-primary" disabled={saving}><Plus />新增组织</button>
            </form>
            <form className="compact-form stacked-form" onSubmit={submitMember}>
              <h3>新增成员</h3>
              <div className="form-grid">
                <Field label="员工工号" required><input name="employeeCode" required /></Field>
                <Field label="姓名" required><input name="name" required /></Field>
                <Field label="所属组织" required>
                  <select name="unitCode" required>{data.units.map((unit) => <option key={unit.id} value={unit.code}>{unit.name}</option>)}</select>
                </Field>
                <Field label="岗位角色" required><input name="roleCode" defaultValue="OPERATOR" required /></Field>
              </div>
              <button className="button button-primary" disabled={saving || data.units.length === 0}><Plus />新增成员</button>
            </form>
          </div>
          <TablePane title="成员目录" count={data.members.length}>
            <thead><tr><th>工号</th><th>姓名</th><th>组织</th><th>岗位</th><th>状态</th></tr></thead>
            <tbody>{data.members.map((member) => <tr key={member.id}><td className="primary-cell">{member.employeeCode}</td><td>{member.name}</td><td>{member.unitCode}</td><td>{member.roleCode}</td><td><StatusBadge value={member.active ? "ACTIVE" : "INACTIVE"} /></td></tr>)}</tbody>
          </TablePane>
        </section>
      )}

      {view === "notifications" && (
        <section className="split-section">
          <div className="editor-pane">
            <SectionTitle icon={<Bell />} title="发送站内通知" hint="业务链接可直接引导到待处理页面" />
            <form className="compact-form" onSubmit={submitNotification}>
              <div className="form-grid">
                <Field label="接收人工号" required><input name="recipientCode" defaultValue={operatorCode} required /></Field>
                <Field label="类别" required><select name="category"><option value="TASK">任务</option><option value="QUALITY">质量</option><option value="INVENTORY">库存</option><option value="WORKFLOW">审批</option><option value="OUTSOURCING">外协</option><option value="SYSTEM">系统</option></select></Field>
                <Field label="标题" required><input name="title" required /></Field>
                <Field label="业务链接"><input name="businessLink" placeholder="/tasks" /></Field>
              </div>
              <Field label="内容" required><textarea name="content" required rows={4} /></Field>
              <button className="button button-primary" disabled={saving}><Plus />发送</button>
            </form>
          </div>
          <TablePane title={`${operatorCode} 的消息`} count={data.notifications.length}>
            <thead><tr><th>消息</th><th>类别</th><th>时间</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>{data.notifications.map((item) => <tr key={item.id}><td><strong>{item.title}</strong><small>{item.content}</small></td><td>{item.category}</td><td>{formatDate(item.createdAt, true)}</td><td><StatusBadge value={item.status} /></td><td className="actions-cell">{item.status === "UNREAD" && <button className="icon-button compact success" aria-label={`标记已读 ${item.title}`} onClick={() => execute(() => api.notifications.markRead(item.id, operatorCode))}><Check /></button>}</td></tr>)}</tbody>
          </TablePane>
        </section>
      )}

      {view === "integration" && (
        <section className="split-section">
          <div className="editor-pane">
            <SectionTitle icon={<PlugZap />} title="提交接口任务" hint="操作号自动生成，重复操作由服务端幂等保护" />
            <form className="compact-form" onSubmit={submitIntegration}>
              <div className="form-grid">
                <Field label="接口编码" required><input name="interfaceCode" defaultValue="ERP_ORDER_SYNC" required /></Field>
                <Field label="业务键" required><input name="businessKey" required /></Field>
                <Field label="方向" required><select name="direction"><option value="OUTBOUND">出站</option><option value="INBOUND">入站</option></select></Field>
              </div>
              <Field label="JSON 载荷" required><textarea name="payload" defaultValue="{}" rows={5} required /></Field>
              <button className="button button-primary" disabled={saving}><Plus />提交任务</button>
            </form>
          </div>
          <TablePane title="接口任务" count={data.jobs.length}>
            <thead><tr><th>任务号</th><th>接口/业务键</th><th>方向</th><th>尝试</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>{data.jobs.map((job) => <tr key={job.id}><td className="primary-cell">{job.jobNo}</td><td><strong>{job.interfaceCode}</strong><small>{job.businessKey}{job.lastError ? ` · ${job.lastError}` : ""}</small></td><td>{job.direction}</td><td>{job.attemptCount}</td><td><StatusBadge value={job.status} /></td><td className="actions-cell action-pair">{["PENDING", "RETRYING"].includes(job.status) && <><button className="icon-button compact success" aria-label={`标记成功 ${job.jobNo}`} onClick={() => execute(() => api.integration.succeed(job.id))}><Check /></button><button className="button button-secondary button-small" onClick={() => setAction({ kind: "fail", job })}>失败</button></>}{job.status === "FAILED" && <button className="icon-button compact" aria-label={`重试 ${job.jobNo}`} onClick={() => execute(() => api.integration.retry(job.id))}><RotateCw /></button>}</td></tr>)}</tbody>
          </TablePane>
        </section>
      )}

      {action && (
        <Modal
          title={action.kind === "occupy" ? "占用资源" : action.kind === "release" ? "释放资源" : "标记接口失败"}
          onClose={() => setAction(null)}
          width="small"
        >
          <form onSubmit={submitAction}>
            {action.kind === "occupy" && <><Field label="业务单号" required><input name="businessKey" required autoFocus /></Field><Field label="备注"><textarea name="note" rows={3} /></Field></>}
            {action.kind === "release" && <label className="check-control"><input name="consumeLife" type="checkbox" defaultChecked /><span>本次使用计入资源寿命</span></label>}
            {action.kind === "fail" && <Field label="失败原因" required><textarea name="error" rows={4} required autoFocus /></Field>}
            <SubmitActions pending={saving} submitLabel="确认" onCancel={() => setAction(null)} />
          </form>
        </Modal>
      )}
    </>
  );
}

function SectionTitle({ icon, title, hint }: { icon: React.ReactNode; title: string; hint: string }) {
  return <header className="section-heading"><div><h2>{title}</h2><p>{hint}</p></div>{icon}</header>;
}

function TablePane({ title, count, children }: { title: string; count: number; children: React.ReactNode }) {
  return <div className="table-pane"><header className="section-heading"><div><h2>{title}</h2><p>{count} 条记录</p></div></header><div className="table-scroll"><table>{children}</table></div></div>;
}
