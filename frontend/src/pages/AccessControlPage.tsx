import { FormEvent, useEffect, useMemo, useState } from "react";
import { KeyRound, Plus, RefreshCw, Save, Settings2, ShieldCheck, SlidersHorizontal, UsersRound } from "lucide-react";
import { api } from "../api";
import {
  ErrorNotice,
  Field,
  LoadingState,
  Modal,
  PageHeader,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessPermission, AccessRole, ReportFormProfile, RouteType, UserDataScope } from "../types";

type View = "users" | "roles" | "forms";

const ROUTES: Array<{ code: RouteType; label: string }> = [
  { code: "MID_TEMP_WAX", label: "中温蜡线" },
  { code: "LOW_TEMP_WAX", label: "低温蜡线" },
  { code: "SAND_OUTSOURCE", label: "砂型外协线" }
];

const OPERATIONS = [
  ["WAX_INJECTION", "射蜡"], ["WAX_REPAIR", "修蜡"], ["TREE_ASSEMBLY", "组树"],
  ["SHELL_BUILDING", "自动化制壳"], ["MANUAL_SHELL_BUILDING", "手动制壳"], ["DEWAX", "脱蜡"],
  ["POURING", "浇筑"], ["KNOCKOUT", "脱壳"], ["CUTTING", "分割"], ["SEMI_FINISHED_COUNT", "半成品清点"],
  ["OPTIONAL_FINISHING", "后处理"], ["FINAL_COUNT", "成品清点"]
] as const;

const EMPTY_SCOPE: Omit<UserDataScope, "employeeCode"> = { supervisorRoutes: [], operatorRoutes: [], supervisorOperations: [] };

type RoleUsageGroup = {
  label: string;
  description: string;
  roleCodes: string[];
};

const ROLE_USAGE_GROUPS: RoleUsageGroup[] = [
  {
    label: "常用角色",
    description: "覆盖当前订单、工程、三条产线生产、模具领用及成品交付；新账户优先从这里选择。",
    roleCodes: [
      "SYSTEM_ADMIN",
      "GENERAL_MANAGER",
			"WORKSHOP_DISPLAY",
			"PRODUCTION_MANAGER",
      "CUSTOMER_MANAGER",
      "FRONT_DESK_CLERK",
      "PROCESS_ENGINEER",
      "MID_WAX_SUPERVISOR",
      "LOW_WAX_SUPERVISOR",
      "MID_SHELL_SUPERVISOR",
      "LOW_SHELL_SUPERVISOR",
      "POST_PROCESS_SUPERVISOR",
      "FINISHING_SUPERVISOR",
      "MOLD_KEEPER",
      "FINISHED_GOODS_KEEPER",
      "WAX_INJECTION_OPERATOR",
      "WAX_REPAIR_OPERATOR",
      "TREE_ASSEMBLY_OPERATOR",
      "SHELL_BUILDING_OPERATOR",
      "DEWAX_OPERATOR",
      "POURING_OPERATOR",
      "KNOCKOUT_OPERATOR",
      "CUTTING_OPERATOR",
      "FINISHING_OPERATOR"
    ]
  },
  {
    label: "按需启用",
    description: "用于现场扫码、质量、原料、外协、财务及后续扩展；通用主管和通用操作员仅为历史兼容，新账户优先使用上方细分角色。",
    roleCodes: []
  }
];

function groupRolesByUsage(roles: AccessRole[]) {
  const assigned = new Set<string>();
  const commonGroup = ROLE_USAGE_GROUPS[0];
  const commonRoles = commonGroup.roleCodes
    .map((code) => roles.find((role) => role.code === code))
    .filter((role): role is AccessRole => role != null);
  commonRoles.forEach((role) => assigned.add(role.code));

  return [
    { ...commonGroup, roles: commonRoles },
    {
      ...ROLE_USAGE_GROUPS[1],
      roles: roles.filter((role) => !assigned.has(role.code))
    }
  ].filter((group) => group.roles.length > 0);
}

export function AccessControlPage({ operatorCode }: { operatorCode: string }) {
  const [view, setView] = useState<View>("users");
  const [selectedUser, setSelectedUser] = useState<string | null>(null);
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const [selectedCodes, setSelectedCodes] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [accountUser, setAccountUser] = useState<string | null>(null);
	const [permissionQuery, setPermissionQuery] = useState("");
  const [userScope, setUserScope] = useState<Omit<UserDataScope, "employeeCode">>(EMPTY_SCOPE);
  const [scopeLoading, setScopeLoading] = useState(false);
  const [selectedFormOperation, setSelectedFormOperation] = useState<string>(OPERATIONS[0][0]);
  const [formProfile, setFormProfile] = useState<ReportFormProfile | null>(null);
	const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = useAsyncData(async () => {
    const [users, roles, permissions, reportForms] = await Promise.all([
      api.access.users(),
      api.access.roles(),
      api.access.permissions(),
      api.reportFormProfiles.list()
    ]);
    return { users, roles, permissions, reportForms };
  });

  const data = state.data;
  const activeUser = data?.users.find((user) => user.employeeCode === selectedUser);
  const activeRole = data?.roles.find((role) => role.code === selectedRole);
  const roleUsageGroups = useMemo(() => groupRolesByUsage(data?.roles ?? []), [data?.roles]);

  useEffect(() => {
    if (!data) return;
    if (view === "users") {
      const user = activeUser ?? data.users[0];
      setSelectedUser(user?.employeeCode ?? null);
      setSelectedCodes(user?.roles ?? []);
    } else {
      const role = activeRole ?? data.roles[0];
      setSelectedRole(role?.code ?? null);
      setSelectedCodes(role?.permissions ?? []);
    }
  }, [view, data, activeUser?.employeeCode, activeRole?.code]);

  useEffect(() => {
    if (!selectedUser || view !== "users") return;
    let active = true;
    setScopeLoading(true);
    api.access.userScopes(selectedUser)
      .then((scope) => active && setUserScope({ supervisorRoutes: scope.supervisorRoutes, operatorRoutes: scope.operatorRoutes, supervisorOperations: scope.supervisorOperations }))
      .catch((caught) => active && setError(caught))
      .finally(() => active && setScopeLoading(false));
    return () => { active = false; };
  }, [selectedUser, view]);

  useEffect(() => {
    const saved = data?.reportForms.find((profile) => profile.operationCode === selectedFormOperation);
    setFormProfile(saved ?? { operationCode: selectedFormOperation, showPhoto: true, requirePhoto: false, showDevice: false, requireDevice: false, showWorkstation: false, requireWorkstation: false, updatedBy: "SYSTEM", updatedAt: null });
  }, [data?.reportForms, selectedFormOperation]);

  const permissionGroups = useMemo(() => {
    const groups = new Map<string, AccessPermission[]>();
    for (const permission of data?.permissions ?? []) {
      const current = groups.get(permission.moduleCode) ?? [];
      groups.set(permission.moduleCode, [...current, permission]);
    }
    return [...groups.entries()];
  }, [data]);

  function toggle(code: string) {
    setSelectedCodes((current) =>
      current.includes(code)
        ? current.filter((value) => value !== code)
        : [...current, code]
    );
  }

	function toggleModule(permissions: AccessPermission[]) {
		const codes = permissions.map((permission) => permission.code);
		const allSelected = codes.every((code) => selectedCodes.includes(code));
		setSelectedCodes((current) => allSelected ? current.filter((code) => !codes.includes(code)) : [...new Set([...current, ...codes])]);
	}

  async function save() {
    setSaving(true);
    setError(null);
    try {
      if (view === "users" && selectedUser) {
        await api.access.setUserRoles(selectedUser, selectedCodes);
      } else if (view === "roles" && selectedRole) {
        await api.access.setRolePermissions(selectedRole, selectedCodes);
      }
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function saveScopes() {
    if (!selectedUser) return;
    setSaving(true); setError(null);
    try {
      await api.access.setUserScopes(selectedUser, userScope);
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  async function saveReportForm() {
    if (!formProfile) return;
    setSaving(true); setError(null);
    try {
      await api.reportFormProfiles.save({ ...formProfile, updatedBy: operatorCode });
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setSaving(false); }
  }

  function toggleRoute(kind: "supervisorRoutes" | "operatorRoutes", route: RouteType) {
    setUserScope((current) => ({ ...current, [kind]: current[kind].includes(route) ? current[kind].filter((item) => item !== route) : [...current[kind], route] }));
  }

  function toggleOperation(routeType: RouteType, operationCode: string) {
    setUserScope((current) => {
      const exists = current.supervisorOperations.some((item) => item.routeType === routeType && item.operationCode === operationCode);
      return { ...current, supervisorOperations: exists ? current.supervisorOperations.filter((item) => item.routeType !== routeType || item.operationCode !== operationCode) : [...current.supervisorOperations, { routeType, operationCode }] };
    });
  }

  async function createRole(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    try {
      const role = await api.access.createRole({
        code: String(form.get("code")),
        name: String(form.get("name")),
        description: String(form.get("description"))
      });
      setCreating(false);
      setView("roles");
      setSelectedRole(role.code);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  async function resetAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!accountUser) return;
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    try {
      await api.access.initializeAccount(accountUser, {
        temporaryPassword: String(form.get("temporaryPassword")),
        forcePasswordChange: false
      });
      setAccountUser(null);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  if (state.loading) return <LoadingState label="正在加载账户与权限" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  if (!data) return null;

  return (
    <>
      <PageHeader
        title="账户与权限"
        description="一个账户可配置多个角色，实际权限取所有角色的并集。"
        action={
          <div className="header-actions">
            <button
              className="button button-secondary"
              type="button"
              onClick={() => setCreating(true)}
            >
              <Plus aria-hidden="true" />
              新建角色
            </button>
            <button
              className="icon-button"
              type="button"
              onClick={state.reload}
              aria-label="刷新权限数据"
              title="刷新"
            >
              <RefreshCw aria-hidden="true" />
            </button>
          </div>
        }
      />
      {error != null && <ErrorNotice error={error} />}

      <div className="filter-bar access-tabs">
        <div className="segmented-control" role="group" aria-label="权限配置视图">
          <button
            type="button"
            className={view === "users" ? "active" : ""}
            onClick={() => setView("users")}
          >
            <UsersRound aria-hidden="true" />
            用户角色
          </button>
          <button
            type="button"
            className={view === "roles" ? "active" : ""}
            onClick={() => setView("roles")}
          >
            <KeyRound aria-hidden="true" />
            角色权限
          </button>
          <button type="button" className={view === "forms" ? "active" : ""} onClick={() => setView("forms")}>
            <SlidersHorizontal aria-hidden="true" />报工表单
          </button>
        </div>
        <button
          className="button button-primary"
          type="button"
          disabled={saving || (view === "users" && selectedCodes.length === 0) || view === "forms"}
          onClick={save}
        >
          <Save aria-hidden="true" />
          {saving ? "正在保存" : "保存配置"}
        </button>
      </div>

      <div className="access-layout">
        <aside className="access-list" aria-label={view === "users" ? "用户列表" : "角色列表"}>
          {view === "users"
            ? data.users.map((user) => (
                <button
                  key={user.employeeCode}
                  type="button"
                  className={selectedUser === user.employeeCode ? "active" : ""}
                  onClick={() => {
                    setSelectedUser(user.employeeCode);
                    setSelectedCodes(user.roles);
                  }}
                >
                  <span className="account-avatar" aria-hidden="true">
                    {user.name.slice(0, 1)}
                  </span>
                    <span>
                      <strong>{user.name}</strong>
                      <small>
                      {user.employeeCode} · {data.roles.find((role) => role.code === user.primaryRole)?.name ?? user.primaryRole}
                      </small>
                    </span>
                </button>
              ))
            : roleUsageGroups.map((group) => (
                <div className="access-role-list-group" key={group.label}>
                  <p>{group.label}</p>
                  {group.roles.map((role) => (
                    <button
                      key={role.code}
                      type="button"
                      className={selectedRole === role.code ? "active" : ""}
                      onClick={() => {
                        setSelectedRole(role.code);
                        setSelectedCodes(role.permissions);
                      }}
                    >
                      <ShieldCheck aria-hidden="true" />
                      <span>
                        <strong>{role.name}</strong>
                        <small>{role.code}</small>
                      </span>
                    </button>
                  ))}
                </div>
              ))}
        </aside>

        <section className="access-editor">
          {view === "users" && activeUser ? (
            <>
              <header>
                <div>
                  <span className="eyebrow">用户角色</span>
                  <h2>{activeUser.name}</h2>
                  <p>
                    {activeUser.employeeCode} · {data.roles.find((role) => role.code === activeUser.primaryRole)?.name ?? activeUser.primaryRole} · {activeUser.unitName}
                  </p>
                </div>
                <span className="permission-count">
                  当前 {activeUser.permissions.length} 项有效权限
                </span>
              </header>
              <section className="access-account-status">
                <div>
                  <span className="eyebrow">登录账号</span>
                  <strong>{activeUser.loginInitialized ? "已初始化" : "未初始化"}</strong>
                  <small>{activeUser.lastLoginAt ? `最近登录：${new Date(activeUser.lastLoginAt).toLocaleString()}` : "尚未登录"}{activeUser.mustChangePassword ? " · 下次登录需修改密码" : ""}</small>
                </div>
                <button className="button button-secondary button-small" type="button" onClick={() => setAccountUser(activeUser.employeeCode)}>
                  <KeyRound aria-hidden="true" />{activeUser.loginInitialized ? "重置密码" : "初始化账号"}
                </button>
              </section>
              <div className="role-usage-groups">
                {roleUsageGroups.map((group) => (
                  <section className="role-usage-group" key={group.label}>
                    <div className="role-usage-heading">
                      <h3>{group.label}</h3>
                      <p>{group.description}</p>
                    </div>
                    <div className="choice-grid role-choice-grid">
                      {group.roles.map((role) => (
                        <Choice
                          key={role.code}
                          role={role}
                          checked={selectedCodes.includes(role.code)}
                          onChange={() => toggle(role.code)}
                        />
                      ))}
                    </div>
                  </section>
                ))}
              </div>
              <div className="access-note">
                主角色用于显示岗位名称；新增角色会叠加权限，不会覆盖原角色能力。下方显示的是保存前的有效权限预览。
              </div>
				<div className="permission-summary">预览：{data.roles.filter((role) => selectedCodes.includes(role.code)).flatMap((role) => role.permissions).filter((code, index, all) => all.indexOf(code) === index).length} 项有效权限</div>
              <section className="scope-editor">
                <header><div><span className="eyebrow">数据范围</span><h3>产线与工序可见范围</h3><p>主管范围控制可查看和派工的工序；操作员范围控制可领取和报工的产线。</p></div><button className="button button-secondary button-small" type="button" disabled={saving || scopeLoading} onClick={saveScopes}><Settings2 aria-hidden="true" />保存范围</button></header>
                {scopeLoading ? <p>正在读取数据范围...</p> : <div className="scope-grid">
                  <fieldset><legend>主管产线</legend>{ROUTES.map((route) => <label key={route.code}><input type="checkbox" checked={userScope.supervisorRoutes.includes(route.code)} onChange={() => toggleRoute("supervisorRoutes", route.code)} />{route.label}</label>)}</fieldset>
                  <fieldset><legend>操作员产线</legend>{ROUTES.map((route) => <label key={route.code}><input type="checkbox" checked={userScope.operatorRoutes.includes(route.code)} onChange={() => toggleRoute("operatorRoutes", route.code)} />{route.label}</label>)}</fieldset>
                  {userScope.supervisorRoutes.length > 0 && <fieldset className="scope-operation-fieldset"><legend>主管工序</legend>{userScope.supervisorRoutes.flatMap((route) => OPERATIONS.map(([code, label]) => <label key={`${route}-${code}`}><input type="checkbox" checked={userScope.supervisorOperations.some((item) => item.routeType === route && item.operationCode === code)} onChange={() => toggleOperation(route, code)} />{ROUTES.find((item) => item.code === route)?.label} · {label}</label>))}</fieldset>}
                </div>}
              </section>
            </>
          ) : view === "forms" && formProfile ? (
            <>
              <header><div><span className="eyebrow">报工表单</span><h2>现场录入字段</h2><p>数量字段始终保留；这里只控制照片、设备和工位信息的显示与必填规则。</p></div><span className="permission-count">按工序生效</span></header>
              <section className="report-form-editor">
                <Field label="工序"><select value={selectedFormOperation} onChange={(event) => setSelectedFormOperation(event.target.value)}>{OPERATIONS.map(([code, label]) => <option key={code} value={code}>{label} · {code}</option>)}</select></Field>
                <div className="choice-grid">{([['showPhoto', '现场照片'], ['showDevice', '设备编号'], ['showWorkstation', '工位编号']] as const).map(([field, label]) => <label key={field} className="permission-choice"><input type="checkbox" checked={formProfile[field]} onChange={(event) => setFormProfile({ ...formProfile, [field]: event.target.checked, ...(event.target.checked ? {} : field === 'showPhoto' ? { requirePhoto: false } : field === 'showDevice' ? { requireDevice: false } : { requireWorkstation: false }) })} /><span><strong>显示{label}</strong><small>按本工序的现场需要显示</small></span></label>)}</div>
                <div className="choice-grid">{([['requirePhoto', '照片必须上传', 'showPhoto'], ['requireDevice', '设备必须填写', 'showDevice'], ['requireWorkstation', '工位必须填写', 'showWorkstation']] as const).map(([field, label, visibleField]) => <label key={field} className="permission-choice"><input type="checkbox" disabled={!formProfile[visibleField]} checked={formProfile[field]} onChange={(event) => setFormProfile({ ...formProfile, [field]: event.target.checked })} /><span><strong>{label}</strong><small>仅在已显示字段时可启用</small></span></label>)}</div>
                <button className="button button-primary" type="button" disabled={saving} onClick={saveReportForm}><Save aria-hidden="true" />{saving ? "正在保存" : "保存报工表单"}</button>
              </section>
            </>
          ) : activeRole ? (
            <>
              <header>
                <div>
                  <span className="eyebrow">角色权限</span>
                  <h2>{activeRole.name}</h2>
                  <p>{activeRole.description}</p>
                </div>
                <span className="permission-count">已选择 {selectedCodes.length} 项</span>
              </header>
              <div className="permission-groups">
					<Field label="检索权限"><input value={permissionQuery} onChange={(event) => setPermissionQuery(event.target.value)} placeholder="权限名称、编码或模块" /></Field>
                {permissionGroups.map(([moduleCode, permissions]) => {
					const visible = permissions.filter((permission) => `${moduleCode} ${permission.name} ${permission.code}`.toLowerCase().includes(permissionQuery.trim().toLowerCase()));
					if (visible.length === 0) return null;
					return (
                  <fieldset key={moduleCode}>
							<legend>{moduleCode} <button type="button" className="text-link" onClick={() => toggleModule(visible)}>{visible.every((permission) => selectedCodes.includes(permission.code)) ? "取消本模块" : "全选本模块"}</button></legend>
                    <div className="choice-grid">
							{visible.map((permission) => (
                        <label key={permission.code} className="permission-choice">
                          <input
                            type="checkbox"
                            checked={selectedCodes.includes(permission.code)}
                            onChange={() => toggle(permission.code)}
                          />
                          <span>
                            <strong>{permission.name}</strong>
                            <small>{permission.code}</small>
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>
					); })}
              </div>
            </>
          ) : null}
        </section>
      </div>

      {accountUser && (
        <Modal
          title="初始化登录账号"
          description="设置临时密码后即可登录；系统不会提供自行注册入口。"
          width="small"
          onClose={() => !saving && setAccountUser(null)}
        >
          <form onSubmit={resetAccount}>
            <div className="form-grid">
              <Field label="员工工号"><input value={accountUser} readOnly /></Field>
              <Field label="临时密码" required hint="至少 12 个字符；请通过受控渠道交付给员工">
                <input name="temporaryPassword" type="password" autoComplete="new-password" minLength={12} required autoFocus />
              </Field>
            </div>
            <SubmitActions pending={saving} submitLabel="确认初始化" onCancel={() => setAccountUser(null)} />
          </form>
        </Modal>
      )}

      {creating && (
        <Modal
          title="新建角色"
          description="创建后再配置该角色可以访问的功能。"
          width="small"
          onClose={() => !saving && setCreating(false)}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={createRole}>
            <div className="form-grid">
              <Field label="角色编码" required hint="使用大写英文和下划线">
                <input name="code" pattern="[A-Za-z0-9_]+" maxLength={64} required autoFocus />
              </Field>
              <Field label="角色名称" required>
                <input name="name" maxLength={120} required />
              </Field>
              <Field label="角色说明" required>
                <textarea name="description" rows={3} maxLength={500} required />
              </Field>
            </div>
            <SubmitActions
              pending={saving}
              submitLabel="创建角色"
              onCancel={() => setCreating(false)}
            />
          </form>
        </Modal>
      )}
    </>
  );
}

function Choice({
  role,
  checked,
  onChange
}: {
  role: AccessRole;
  checked: boolean;
  onChange: () => void;
}) {
  return (
    <label className="permission-choice role-choice">
      <input type="checkbox" checked={checked} onChange={onChange} />
      <span>
        <strong>{role.name}</strong>
        <small>{role.description}</small>
      </span>
    </label>
  );
}
