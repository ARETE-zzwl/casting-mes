import { useMemo, useState } from "react";
import { ArrowRight, BadgeDollarSign, Building2, CircleAlert, ClipboardCheck, TrendingUp, UsersRound } from "lucide-react";
import { Link } from "react-router-dom";
import type { EChartsOption } from "echarts";
import { api } from "../api";
import { EChart } from "../components/EChart";
import { ErrorNotice, LoadingState, PageHeader, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, RouteType } from "../types";

const routeLabels: Record<Exclude<RouteType, never>, string> = {
  MID_TEMP_WAX: "中温蜡线",
  LOW_TEMP_WAX: "低温蜡线",
  SAND_OUTSOURCE: "砂型外协"
};

const palette = ["#23675d", "#346b8c", "#a86513", "#a43e34"];

function defaultPeriod() {
  return new Date().toISOString().slice(0, 7);
}

function currency(amount: number) {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 2
  }).format(amount);
}

export function SalesManagementPage({ user }: { user: AccessUser }) {
  const [period, setPeriod] = useState(defaultPeriod);
  const [ownerFilter, setOwnerFilter] = useState("");
  const state = useAsyncData(
    () => api.sales.performance(user.employeeCode, period, ownerFilter || undefined),
    [user.employeeCode, period, ownerFilter]
  );
  const data = state.data;

  const trendOption = useMemo<EChartsOption>(() => ({
    color: palette,
    tooltip: {
      trigger: "axis",
      valueFormatter: (value) => currency(Number(value))
    },
    grid: { left: 68, right: 24, top: 28, bottom: 38 },
    xAxis: { type: "category", data: data?.monthlyTrend.map((item) => item.month.slice(5)) ?? [] },
    yAxis: { type: "value", axisLabel: { formatter: (value) => `¥${Number(value) / 10000}万` } },
    series: [{
      name: "已复核订单额",
      type: "bar",
      barMaxWidth: 34,
      data: data?.monthlyTrend.map((item) => item.recognizedAmount) ?? []
    }]
  }), [data]);

  if (state.loading) return <LoadingState label="正在汇总销售订单业绩" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  if (!data) return null;

  return (
    <>
      <PageHeader
        title={data.scope === "SELF" ? "我的销售业绩" : "销售管理"}
        description={`按商务复核日期统计已复核订单成交额，不等同于实际回款。数据更新于 ${new Date(data.generatedAt).toLocaleString("zh-CN")}`}
        action={<Link className="button button-secondary" to="/orders">查看客户订单 <ArrowRight aria-hidden="true" /></Link>}
      />

      <section className="sales-filter-bar" aria-label="销售业绩筛选">
        <label className="field">
          <span>统计月份</span>
          <input type="month" value={period} onChange={(event) => setPeriod(event.target.value)} />
        </label>
        {data.scope === "ALL" && (
          <label className="field sales-owner-filter">
            <span>销售人员</span>
            <input value={ownerFilter} onChange={(event) => setOwnerFilter(event.target.value)} placeholder="输入姓名筛选，可留空查看全员" />
          </label>
        )}
        <p>订单行没有录入单价、计价单位不匹配时不计入成交额，并在下方提示补齐。</p>
      </section>

      <section className="metric-grid" aria-label="销售业绩指标">
        <article className="metric metric-active">
          <div><span>已复核订单额</span><strong>{currency(data.overview.recognizedAmount)}</strong></div>
          <BadgeDollarSign aria-hidden="true" />
          <small>按订单行成交单价和计价单位换算</small>
        </article>
        <article className="metric">
          <div><span>已复核订单</span><strong>{data.overview.reviewedOrders}</strong></div>
          <ClipboardCheck aria-hidden="true" />
          <small>单均 {currency(data.overview.averageOrderAmount)}</small>
        </article>
        <article className="metric">
          <div><span>成交客户</span><strong>{data.overview.reviewedCustomers}</strong></div>
          <Building2 aria-hidden="true" />
          <small>本统计周期内完成商务复核</small>
        </article>
        <article className={data.overview.pendingReviewOrders || data.overview.excludedLineCount ? "metric metric-warning" : "metric"}>
          <div><span>待处理商务数据</span><strong>{data.overview.pendingReviewOrders + data.overview.excludedLineCount}</strong></div>
          <CircleAlert aria-hidden="true" />
          <small>{data.overview.pendingReviewOrders} 张待复核，{data.overview.excludedLineCount} 行待补价</small>
        </article>
      </section>

      <div className="management-dashboard-grid sales-dashboard-grid">
        <section className="section-block chart-panel chart-wide">
          <header className="section-heading">
            <div><h2>近六个月订单业绩</h2><p>仅展示已商务复核订单的成交额趋势。</p></div>
            <TrendingUp aria-hidden="true" />
          </header>
          <EChart option={trendOption} label="近六个月已复核订单额趋势" height={292} />
        </section>

        <section className="section-block sales-scope-note">
          <header className="section-heading"><div><h2>统计口径</h2><p>订单额与回款分开管理，避免把订单金额误认为现金流。</p></div></header>
          <dl>
            <div><dt>归属冻结</dt><dd>创建订单时带入客户负责人，后续改客户资料不改历史业绩。</dd></div>
            <div><dt>金额换算</dt><dd>支持件/只、套、公斤/吨同类单位；其他情况提示补齐计价信息。</dd></div>
            <div><dt>回款管理</dt><dd>本页不记录收款，后续可接应收与回款台账。</dd></div>
          </dl>
        </section>

        {data.scope === "ALL" && (
          <section className="section-block chart-wide">
            <header className="section-heading"><div><h2>销售人员业绩</h2><p>按所选月份、已复核订单汇总；待复核与待补价独立提示。</p></div><UsersRound aria-hidden="true" /></header>
            <div className="table-scroll"><table><thead><tr><th>销售人员</th><th>已复核订单</th><th>成交客户</th><th>订单额</th><th>待复核</th><th>待补价</th></tr></thead><tbody>
              {data.ownerRanking.length === 0 ? <tr><td colSpan={6} className="table-empty">本月暂无可统计订单</td></tr> : data.ownerRanking.map((owner) => <tr key={owner.salesOwner}><td><strong>{owner.salesOwner}</strong></td><td>{owner.reviewedOrders}</td><td>{owner.customerCount}</td><td className="sales-amount">{currency(owner.recognizedAmount)}</td><td>{owner.pendingReviewOrders}</td><td>{owner.excludedLineCount}</td></tr>)}
            </tbody></table></div>
          </section>
        )}

        <section className="section-block chart-wide">
          <header className="section-heading"><div><h2>最近复核订单</h2><p>可直接进入订单追溯，跟进生产与交付。</p></div></header>
          <div className="table-scroll"><table><thead><tr><th>订单号</th><th>客户</th>{data.scope === "ALL" && <th>销售人员</th>}<th>产线</th><th>状态</th><th>订单额</th><th>复核时间</th><th>追溯</th></tr></thead><tbody>
            {data.recentOrders.length === 0 ? <tr><td colSpan={data.scope === "ALL" ? 8 : 7} className="table-empty">本月暂无已复核订单</td></tr> : data.recentOrders.map((order) => <tr key={order.orderId}><td><strong>{order.orderNo}</strong>{order.excludedLineCount > 0 && <small className="sales-data-warning">{order.excludedLineCount} 行待补价</small>}</td><td>{order.customerName}</td>{data.scope === "ALL" && <td>{order.salesOwner}</td>}<td>{order.routeType ? routeLabels[order.routeType] : "待补路线"}</td><td><StatusBadge value={order.status} /></td><td className="sales-amount">{currency(order.recognizedAmount)}</td><td>{order.reviewedAt ? new Date(order.reviewedAt).toLocaleString("zh-CN") : "-"}</td><td><Link className="table-link" to={`/trace?orderId=${order.orderId}`}>查看</Link></td></tr>)}
          </tbody></table></div>
        </section>
      </div>
    </>
  );
}
