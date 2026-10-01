import { AlertTriangle, ArrowRight, BellRing, CheckCircle2, Clock3, ListChecks, ScanLine } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { ErrorNotice, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, Task } from "../types";
import { WorkerWorkbenchPage } from "./WorkerWorkbenchPage";
import { roleWorkspaceFor } from "./roleWorkspace";

function canUse(user: AccessUser, permission?: string | string[]) {
  if (!permission) return true;
  const permissions = Array.isArray(permission) ? permission : [permission];
  return permissions.some((code) => user.permissions.includes(code));
}

function taskSummary(task: Task) {
  return `${task.productName}${task.productMaterial ? ` · ${task.productMaterial}` : ""} · ${task.taskNo} · ${task.batchNo}`;
}

function RoleOverview({ user }: { user: AccessUser }) {
  const profile = roleWorkspaceFor(user);
  const canReadTasks = canUse(user, ["WORKBENCH_VIEW", "TASK_DISPATCH", "PLANNING_VIEW", "ORDER_MANAGE", "MASTERDATA_MANAGE", "WORKSHOP_DISPLAY_VIEW"]);
  const state = useAsyncData(
    () => canReadTasks ? api.tasks.list(undefined, undefined, undefined, profile.kind === "supervisor" ? user.employeeCode : undefined) : Promise.resolve<Task[]>([]),
    [profile.kind, user.employeeCode, canReadTasks]
  );
  const tasks = state.data ?? [];
  const assigned = tasks.filter((task) => task.assignedTo === user.employeeCode && task.status !== "COMPLETED");
  const active = tasks.filter((task) => task.status === "IN_PROGRESS");
  const ready = tasks.filter((task) => task.status === "READY");
  const visibleActions = [...profile.actions.filter((action) => canUse(user, action.permission)), ...(canUse(user, "NOTIFICATION_VIEW") ? [{ to: "/notifications", label: "消息中心", description: "查看工程催办、异常预警和与我相关的处理待办", icon: BellRing }] : []), { to: "/scan", label: "扫一扫", description: "核验模具、周转车或电子任务码，再进入有权限的后续操作", icon: ScanLine }];
  const canManageTasks = canUse(user, "TASK_DISPATCH");
  const queue = profile.kind === "warehouse" || profile.kind === "quality" ? assigned : tasks.filter((task) => task.status !== "COMPLETED").slice(0, 6);
  const mobileQuickActions = [
    visibleActions.find((action) => action.to === "/scan"),
    visibleActions.find((action) => action.to === "/notifications"),
    visibleActions.find((action) => action.to !== "/scan" && action.to !== "/notifications")
  ].filter((action, index, actions): action is NonNullable<typeof action> => Boolean(action) && actions.findIndex((item) => item?.to === action?.to) === index);

  if (state.loading) return <LoadingState label="正在准备岗位工作台" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  return <>
    <section className={`role-workspace-hero role-tone-${profile.tone}`} aria-label={`${profile.roleLabel}工作台`}>
      <div>
        <span>{profile.roleLabel}</span>
        <h1>{profile.title}</h1>
        <p>{profile.focus}</p>
      </div>
      <div className="role-workspace-identity">
        <strong>{user.name}</strong>
        <small>{user.employeeCode} · {user.unitName}</small>
      </div>
    </section>

    <section className="role-metric-strip" aria-label="岗位待办概览">
      <div><span>与我相关</span><strong>{assigned.length}</strong><small>已分配未完成</small></div>
      <div><span>生产进行中</span><strong>{active.length}</strong><small>权限范围内在制任务</small></div>
      <div><span>等待处理</span><strong>{ready.length}</strong><small>可进入下一步</small></div>
    </section>

    <nav className="role-mobile-quick-actions" aria-label="岗位快捷入口">
      {mobileQuickActions.map(({ to, label, icon: Icon }) => <Link key={to} to={to}><Icon aria-hidden="true" /><span>{label}</span></Link>)}
    </nav>

    <div className="role-workspace-grid">
      <section className="role-command-panel" aria-labelledby="role-command-title">
        <header><div><span>常用操作</span><h2 id="role-command-title">本岗位工作入口</h2></div></header>
        <div className="role-command-grid">
          {visibleActions.map(({ to, label, description, icon: Icon }) => <Link key={to} to={to}>
            <Icon aria-hidden="true" />
            <div><strong>{label}</strong><small>{description}</small></div>
            <ArrowRight aria-hidden="true" />
          </Link>)}
        </div>
      </section>

      <section className="role-queue-panel" aria-labelledby="role-queue-title">
        <header><div><span>{canManageTasks ? "执行队列" : "只读概览"}</span><h2 id="role-queue-title">{profile.kind === "warehouse" ? "我待处理的仓库任务" : profile.kind === "quality" ? "我待处理的质量任务" : canManageTasks ? "当前生产队列" : "当前生产概览"}</h2></div><ListChecks aria-hidden="true" /></header>
        {queue.length === 0 ? <div className="role-queue-empty"><CheckCircle2 aria-hidden="true" />当前没有需要处理的任务</div> : <div className="role-queue-list">{queue.map((task: Task) => canManageTasks ? <Link key={task.id} to="/tasks"><span className="role-queue-operation">{task.operationName}</span><small>{taskSummary(task)}</small><StatusBadge value={task.status} /></Link> : <div key={task.id}><span className="role-queue-operation">{task.operationName}</span><small>{taskSummary(task)}</small><StatusBadge value={task.status} /></div>)}</div>}
      </section>
    </div>

    <section className="role-workspace-note" aria-label="岗位提示">
      {profile.kind === "supervisor" || profile.kind === "planner" ? <AlertTriangle aria-hidden="true" /> : <Clock3 aria-hidden="true" />}
      <div><strong>{profile.kind === "supervisor" || profile.kind === "planner" ? "现场处理原则" : "岗位操作原则"}</strong><span>{profile.kind === "supervisor" ? "可直接核对并代报浇筑、脱壳分割等工序，但须保留实际执行账号和审核记录。" : "只处理本岗位有权查看的数据；敏感订单字段、仓库与交付功能按权限隔离。"}</span></div>
    </section>
  </>;
}

export function RoleWorkspacePage({ user }: { user: AccessUser }) {
  const profile = roleWorkspaceFor(user);
  return profile.kind === "operator"
    ? <WorkerWorkbenchPage user={user} profile={profile} />
    : <RoleOverview user={user} />;
}
