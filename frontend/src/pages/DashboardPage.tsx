import { useMemo } from "react";
import {
  ArrowRight,
  BadgeDollarSign,
  CircleAlert,
  Factory,
  ListChecks
} from "lucide-react";
import { Link } from "react-router-dom";
import type { EChartsOption } from "echarts";
import { api } from "../api";
import { EChart } from "../components/EChart";
import { ErrorNotice, LoadingState, PageHeader } from "../components/ui";
import { useAsyncData } from "../hooks";

const palette = ["#23675d", "#346b8c", "#a86513", "#a43e34", "#6d5b8c"];
const taskLabels: Record<string, string> = {
  READY: "待派工",
  ASSIGNED: "已分派",
  IN_PROGRESS: "生产中",
  COMPLETED: "已完成"
};
const outsourcingLabels: Record<string, string> = {
  DRAFT: "草稿",
  SENT: "已发出",
  IN_PROGRESS: "执行中",
  RECEIVED: "已收货",
  CLOSED: "已关闭",
  CANCELLED: "已取消"
};

export function DashboardPage() {
  const state = useAsyncData(api.reporting.dashboard, []);
  const data = state.data;

  const dailyOption = useMemo<EChartsOption>(() => ({
    color: palette,
    aria: { enabled: true, decal: { show: true } },
    tooltip: { trigger: "axis" },
    legend: { bottom: 0, data: ["合格数", "报废数"] },
    grid: { left: 48, right: 20, top: 20, bottom: 48 },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: data?.dailyOutput.map((item) => item.date.slice(5)) ?? []
    },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      {
        name: "合格数",
        type: "line",
        smooth: false,
        symbolSize: 7,
        areaStyle: { opacity: 0.08 },
        data: data?.dailyOutput.map((item) => item.goodQuantity) ?? []
      },
      {
        name: "报废数",
        type: "line",
        symbolSize: 7,
        data: data?.dailyOutput.map((item) => item.scrapQuantity) ?? []
      }
    ]
  }), [data]);

  const taskOption = useMemo<EChartsOption>(() => ({
    color: palette,
    aria: { enabled: true, decal: { show: true } },
    tooltip: { trigger: "item" },
    legend: { bottom: 0 },
    series: [{
      name: "任务状态",
      type: "pie",
      radius: ["48%", "70%"],
      center: ["50%", "43%"],
      label: { formatter: "{b}\n{c}" },
      data: data?.taskStatuses.map((item) => ({
        name: taskLabels[item.status] ?? item.status,
        value: item.count
      })) ?? []
    }]
  }), [data]);

  const qualityOption = useMemo<EChartsOption>(() => ({
    color: [palette[0], palette[3]],
    aria: { enabled: true, decal: { show: true } },
    tooltip: { trigger: "item" },
    series: [{
      name: "质量结果",
      type: "pie",
      radius: ["46%", "72%"],
      center: ["50%", "50%"],
      label: { formatter: "{b} {c}" },
      data: [
        { name: "合格", value: data?.quality.acceptedQuantity ?? 0 },
        { name: "不合格", value: data?.quality.rejectedQuantity ?? 0 }
      ]
    }]
  }), [data]);

  const outsourcingOption = useMemo<EChartsOption>(() => ({
    color: palette,
    aria: { enabled: true, decal: { show: true } },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    grid: { left: 72, right: 20, top: 18, bottom: 30 },
    xAxis: { type: "value", minInterval: 1 },
    yAxis: {
      type: "category",
      data: data?.outsourcingStatuses.map((item) => outsourcingLabels[item.name] ?? item.name) ?? []
    },
    series: [{
      name: "外协单",
      type: "bar",
      barMaxWidth: 24,
      data: data?.outsourcingStatuses.map((item) => item.value) ?? []
    }]
  }), [data]);

  if (state.loading) return <LoadingState label="正在汇总经营数据" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;
  if (!data) return null;

  const totalInspected = data.quality.acceptedQuantity + data.quality.rejectedQuantity;
  const passRate = totalInspected
    ? Math.round((data.quality.acceptedQuantity / totalInspected) * 1000) / 10
    : 0;

  return (
    <>
      <PageHeader
        title="经营看板"
        description={`生产、质量、库存、计件与外协实时汇总 · ${new Date(data.generatedAt).toLocaleTimeString("zh-CN")}`}
        action={
          <Link className="button button-primary" to="/tasks">
            进入任务执行 <ArrowRight aria-hidden="true" />
          </Link>
        }
      />

      <section className="metric-grid" aria-label="关键经营指标">
        <article className="metric">
          <div><span>客户订单</span><strong>{data.overview.orders}</strong></div>
          <Factory aria-hidden="true" />
          <small>{data.overview.draftOrders} 张等待审批</small>
        </article>
        <article className="metric metric-active">
          <div><span>现场任务</span><strong>{data.overview.activeTasks}</strong></div>
          <ListChecks aria-hidden="true" />
          <small>{data.overview.readyTasks} 项等待派工</small>
        </article>
        <article className="metric metric-warning">
          <div><span>一次合格率</span><strong>{passRate}%</strong></div>
          <CircleAlert aria-hidden="true" />
          <small>{data.overview.rejectedInspections} 条不合格检验</small>
        </article>
        {data.overview.confirmedPieceworkAmount !== null && <article className="metric">
          <div>
            <span>已确认计件</span>
            <strong>¥{data.overview.confirmedPieceworkAmount.toFixed(2)}</strong>
          </div>
          <BadgeDollarSign aria-hidden="true" />
          <small>{data.overview.openOutsourcing} 张外协单执行中</small>
        </article>}
      </section>

      <div className="management-dashboard-grid">
        <section className="section-block chart-panel chart-wide">
          <header className="section-heading">
            <div><h2>近七日报工趋势</h2><p>来自不可覆盖的生产报工流水</p></div>
          </header>
          <EChart option={dailyOption} label="近七日合格数和报废数趋势图" height={300} />
        </section>

        <section className="section-block chart-panel">
          <header className="section-heading">
            <div><h2>任务状态</h2><p>当前全部工序任务分布</p></div>
            <Link to="/tasks">查看任务</Link>
          </header>
          {data.taskStatuses.length > 0 ? (
            <EChart option={taskOption} label="任务状态分布图" />
          ) : (
            <div className="compact-empty">暂无工序任务</div>
          )}
        </section>

        <section className="section-block chart-panel">
          <header className="section-heading">
            <div><h2>质量构成</h2><p>累计检验合格与不合格数量</p></div>
            <Link to="/quality">质量工作台</Link>
          </header>
          {totalInspected > 0 ? (
            <EChart option={qualityOption} label="质量合格与不合格数量构成图" />
          ) : (
            <div className="compact-empty">暂无质量检验数据</div>
          )}
        </section>

        <section className="section-block chart-panel">
          <header className="section-heading">
            <div><h2>外协状态</h2><p>供应商订单执行分布</p></div>
            <Link to="/outsourcing">外协台账</Link>
          </header>
          {data.outsourcingStatuses.length > 0 ? (
            <EChart option={outsourcingOption} label="外协订单状态条形图" />
          ) : (
            <div className="compact-empty">暂无外协订单</div>
          )}
        </section>

        <section className="section-block risk-panel">
          <header className="section-heading">
            <div><h2>经营风险</h2><p>从交易事实即时计算</p></div>
          </header>
          {data.risks.length === 0 ? (
            <div className="compact-empty">当前没有待处理风险</div>
          ) : (
            <div className="risk-list">
              {data.risks.map((risk) => (
                <Link key={`${risk.title}-${risk.link}`} to={risk.link}>
                  <span className={`risk-dot risk-${risk.severity.toLowerCase()}`} />
                  <strong>{risk.title}</strong>
                  <b>{risk.count}</b>
                  <ArrowRight aria-hidden="true" />
                </Link>
              ))}
            </div>
          )}
        </section>
      </div>
    </>
  );
}
