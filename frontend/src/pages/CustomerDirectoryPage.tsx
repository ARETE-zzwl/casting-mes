import { useMemo, useState } from "react";
import { Check, Search } from "lucide-react";
import { api } from "../api";
import { EmptyState, ErrorNotice, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser } from "../types";

export function CustomerDirectoryPage({ user }: { user: AccessUser }) {
  const state = useAsyncData(async () => {
    const [customers, requests] = await Promise.all([api.customers.list(), api.customers.sensitiveRequests()]);
    return { customers, requests };
  }, []);
  const [query, setQuery] = useState("");
  const [actionId, setActionId] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const canApprove = user.permissions.includes("CUSTOMER_CHANGE_APPROVE");
  const customers = state.data?.customers ?? [];
  const requests = state.data?.requests ?? [];
  const filteredCustomers = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return normalized ? customers.filter((customer) => `${customer.code} ${customer.name} ${customer.contactName ?? ""} ${customer.salesOwner ?? ""}`.toLowerCase().includes(normalized)) : customers;
  }, [customers, query]);

  async function approve(id: string) {
    setActionId(id); setError(null);
    try {
      await api.customers.approveSensitiveRequest(id, user.employeeCode);
      await state.reload();
    } catch (caught) { setError(caught); }
    finally { setActionId(null); }
  }

  if (state.loading) return <LoadingState label="正在加载客户资料" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  return <>
    <PageHeader title="客户管理" description="客户主数据可查询；新建与敏感变更通过一次业务复核后生效。" />
    {error && <ErrorNotice error={error} />}
    <section className="section-block">
      <div className="section-heading"><div><h2>客户检索</h2><p>可按客户编码、名称、联系人或销售人员快速定位。</p></div></div>
      <label className="filter-search"><Search aria-hidden="true" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="检索客户、联系人或销售人员" /></label>
      {filteredCustomers.length === 0 ? <EmptyState title="未找到客户" description="请调整检索条件，或在订单受理中提交客户建档申请。" /> : <div className="table-scroll"><table><thead><tr><th>客户编码</th><th>客户名称</th><th>联系人</th><th>联系电话</th><th>销售人员</th><th>状态</th></tr></thead><tbody>{filteredCustomers.map((customer) => <tr key={customer.id}><td className="primary-cell">{customer.code}</td><td>{customer.name}</td><td>{customer.contactName || "-"}</td><td>{customer.contactPhone || "-"}</td><td>{customer.salesOwner || "-"}</td><td><StatusBadge value={customer.active ? "ACTIVE" : "INACTIVE"} /></td></tr>)}</tbody></table></div>}
    </section>
    <section className="section-block">
      <div className="section-heading"><div><h2>客户建档申请</h2><p>审批通过后系统自动建档；申请和审批全过程保留操作人、时间与结果。</p></div></div>
      {requests.length === 0 ? <EmptyState title="暂无客户建档申请" description="前台提交申请后会在这里等待业务复核。" /> : <div className="table-scroll"><table><thead><tr><th>申请单号</th><th>拟建客户</th><th>联系人</th><th>销售人员</th><th>申请人</th><th>状态</th><th className="actions-cell">操作</th></tr></thead><tbody>{requests.map((request) => <tr key={request.id}><td className="primary-cell">{request.requestNo}</td><td>{request.proposedName}</td><td>{request.proposedContactName || "-"} {request.proposedContactPhone || ""}</td><td>{request.proposedSalesOwner || "-"}</td><td>{request.requestedBy}</td><td><StatusBadge value={request.status} /></td><td className="actions-cell">{canApprove && request.status === "PENDING" ? <button className="button button-secondary button-small" type="button" disabled={actionId === request.id} onClick={() => void approve(request.id)}><Check aria-hidden="true" />{actionId === request.id ? "审批中" : "通过并建档"}</button> : request.reviewedBy || "-"}</td></tr>)}</tbody></table></div>}
    </section>
  </>;
}
