import { RefreshCw } from "lucide-react";
import { api } from "../api";
import { ErrorNotice, formatQuantity, LoadingState, PageHeader } from "../components/ui";
import { useAsyncData } from "../hooks";
import { useEffect, useState } from "react";

export function ReportLedgerPage({ operatorCode }: { operatorCode: string }) {
  const [orderId, setOrderId] = useState("");
  const [workerCode, setWorkerCode] = useState("");
  const [operationCode, setOperationCode] = useState("");
  const [reportDate, setReportDate] = useState("");
  const [page, setPage] = useState(0);
  const orders = useAsyncData(api.orders.list, []);
  const reports = useAsyncData(() => api.execution.reportLedgerPage({
    viewerCode: operatorCode,
    orderId: orderId || undefined,
    workerCode: workerCode || undefined,
    operationCode: operationCode || undefined,
    from: reportDate ? `${reportDate}T00:00:00Z` : undefined,
    to: reportDate ? `${reportDate}T23:59:59Z` : undefined,
    page
  }), [operatorCode, orderId, workerCode, operationCode, reportDate, page]);

  const items = reports.data?.items ?? [];
  const operations = Array.from(new Map(items.map((item) => [item.operationCode, item])).values());
  useEffect(() => { setPage(0); }, [orderId, workerCode, operationCode, reportDate]);

  if (orders.loading || reports.loading) return <LoadingState label="正在加载报工记录总账" />;
  if (orders.error || reports.error) return <ErrorNotice error={orders.error ?? reports.error} onRetry={() => { void orders.reload(); void reports.reload(); }} />;

  return <>
    <PageHeader title="报工记录总账" description="电子报工、主管代报与纸质单审核入账统一留痕；主管仅查看负责产线，总经理和管理员可查看全厂。" action={<button className="icon-button" type="button" aria-label="刷新报工总账" onClick={reports.reload}><RefreshCw aria-hidden="true" /></button>} />
    <section className="section-block" aria-label="报工筛选">
      <div className="filter-bar">
        <select value={orderId} onChange={(event) => { setOrderId(event.target.value); setPage(0); }} aria-label="按订单筛选报工"><option value="">全部订单</option>{(orders.data ?? []).map((order) => <option key={order.id} value={order.id}>{order.orderNo} · {order.customerName}</option>)}</select>
        <input value={workerCode} onChange={(event) => { setWorkerCode(event.target.value.toUpperCase()); setPage(0); }} placeholder="员工工号" aria-label="按员工筛选报工" />
        <select value={operationCode} onChange={(event) => { setOperationCode(event.target.value); setPage(0); }} aria-label="按工序筛选报工"><option value="">全部工序</option>{operations.map((item) => <option key={item.operationCode} value={item.operationCode}>{item.operationName}</option>)}</select>
        <input type="date" value={reportDate} onChange={(event) => { setReportDate(event.target.value); setPage(0); }} aria-label="按日期筛选报工" />
      </div>
      <div className="table-scroll">
        <table><thead><tr><th>时间</th><th>订单 / 产品</th><th>工序 / 任务</th><th>员工</th><th>合格 / 报废</th><th>设备 / 工位</th><th>留痕</th></tr></thead>
          <tbody>{items.map((item) => <tr key={item.id}><td>{new Date(item.occurredAt).toLocaleString("zh-CN", { hour12: false })}</td><td><strong>{item.orderNo}</strong><small>{item.productCode} · {item.productName}</small></td><td>{item.operationName}<small>{item.taskNo}</small></td><td>{item.operatorCode}{item.recordedBy && item.recordedBy !== item.operatorCode && <small>代录 {item.recordedBy}</small>}</td><td>{formatQuantity(item.goodQuantity)} / {formatQuantity(item.scrapQuantity)}</td><td>{item.deviceCode ?? "-"}<small>{item.workstationCode ?? ""}</small></td><td>{item.photoUrl ? <a className="text-link" href={item.photoUrl} target="_blank" rel="noreferrer">查看照片</a> : "-"}</td></tr>)}{items.length === 0 && <tr><td colSpan={7}>暂无符合条件的已入账报工记录。</td></tr>}</tbody>
        </table>
      </div>
      {(reports.data?.totalPages ?? 0) > 1 && <nav className="warehouse-pagination" aria-label="报工总账分页"><button type="button" className="button button-secondary button-small" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button><span>第 {page + 1} / {reports.data!.totalPages} 页，共 {reports.data!.totalElements} 条</span><button type="button" className="button button-secondary button-small" disabled={page + 1 >= reports.data!.totalPages} onClick={() => setPage(page + 1)}>下一页</button></nav>}
    </section>
  </>;
}
