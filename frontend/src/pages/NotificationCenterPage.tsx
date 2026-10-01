import { BellRing, Check, ChevronRight, CircleAlert, RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import { EmptyState, ErrorNotice, LoadingState, PageHeader, StatusBadge, formatDate } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, NotificationItem } from "../types";

type ViewFilter = "ALL" | "UNREAD";

const categoryLabel: Record<string, string> = {
  TASK: "生产任务",
  QUALITY: "质量",
  INVENTORY: "库存",
  WORKFLOW: "流程",
  OUTSOURCING: "外协",
  SYSTEM: "系统"
};

export function NotificationCenterPage({ user }: { user: AccessUser }) {
  const navigate = useNavigate();
  const state = useAsyncData(() => api.notifications.list(user.employeeCode), [user.employeeCode]);
  const [filter, setFilter] = useState<ViewFilter>("ALL");
  const [category, setCategory] = useState("ALL");
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const notifications = state.data ?? [];
  const unreadCount = notifications.filter((item) => item.status === "UNREAD").length;
  const exceptionCount = notifications.filter(isException).length;
  const categories = useMemo(() => [...new Set(notifications.map((item) => item.category))], [notifications]);
  const visible = notifications.filter((item) =>
    (filter === "ALL" || item.status === "UNREAD") && (category === "ALL" || item.category === category)
  );

  async function markRead(item: NotificationItem) {
    if (item.status === "READ") return;
    setPendingId(item.id); setActionError(null);
    try {
      await api.notifications.markRead(item.id, user.employeeCode);
      await state.reload();
    } catch (caught) {
      setActionError(caught);
    } finally {
      setPendingId(null);
    }
  }

  async function openNotification(item: NotificationItem) {
    await markRead(item);
    if (item.businessLink?.startsWith("/")) navigate(item.businessLink);
  }

  if (state.loading) return <LoadingState label="正在加载消息中心" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  return <>
    <PageHeader title="消息中心" description="接收与本人相关的生产、工程、质量、库存和流程待办；总经理与管理员同时接收全厂异常抄送。" action={<button className="icon-button" type="button" onClick={state.reload} aria-label="刷新消息"><RefreshCw aria-hidden="true" /></button>} />
    {actionError != null && <ErrorNotice error={actionError} onRetry={() => setActionError(null)} />}
    <section className="notification-summary" aria-label="消息概览">
      <div><BellRing aria-hidden="true" /><span>全部消息</span><strong>{notifications.length}</strong></div>
      <div><CircleAlert aria-hidden="true" /><span>未读待办</span><strong>{unreadCount}</strong></div>
      <div><CircleAlert aria-hidden="true" /><span>异常与预警</span><strong>{exceptionCount}</strong></div>
    </section>
    <section className="section-block">
      <div className="notification-toolbar">
        <div className="segmented-control" role="group" aria-label="消息状态">
          <button type="button" className={filter === "ALL" ? "active" : ""} onClick={() => setFilter("ALL")}>全部</button>
          <button type="button" className={filter === "UNREAD" ? "active" : ""} onClick={() => setFilter("UNREAD")}>未读 {unreadCount}</button>
        </div>
        <label className="field"><span>类别</span><select value={category} onChange={(event) => setCategory(event.target.value)}><option value="ALL">全部类别</option>{categories.map((value) => <option key={value} value={value}>{categoryLabel[value] ?? value}</option>)}</select></label>
      </div>
      {visible.length === 0 ? <EmptyState title={filter === "UNREAD" ? "没有未读消息" : "暂无消息"} description={filter === "UNREAD" ? "已处理的消息仍可在“全部”中查看。" : "工程催办、交接异常、库存与生产预警会显示在这里。"} /> : <div className="notification-list">{visible.map((item) => <article key={item.id} className={`notification-item ${item.status === "UNREAD" ? "unread" : ""}`}><div className="notification-item-main"><div><StatusBadge value={item.status} /><span className="notification-category">{categoryLabel[item.category] ?? item.category}</span></div><strong>{item.title}</strong><p>{item.content}</p><small>{item.notificationNo} · {formatDate(item.createdAt, true)}</small></div><div className="notification-actions">{item.status === "UNREAD" && <button className="icon-button compact success" type="button" disabled={pendingId === item.id} onClick={() => void markRead(item)} aria-label={`标记已读 ${item.title}`} title="标记已读"><Check aria-hidden="true" /></button>}{item.businessLink?.startsWith("/") && <button className="button button-secondary button-small" type="button" disabled={pendingId === item.id} onClick={() => void openNotification(item)}>进入处理<ChevronRight aria-hidden="true" /></button>}</div></article>)}</div>}
    </section>
  </>;
}

function isException(item: NotificationItem) {
  return item.category === "QUALITY" || /异常|预警|缺口|阻塞/.test(item.title);
}
