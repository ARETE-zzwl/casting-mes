import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import type { EChartsOption } from "echarts";
import { AlertTriangle, Boxes, CircleAlert, ClipboardList, Factory, PackageSearch, RefreshCw, ScanLine, TimerReset, X } from "lucide-react";
import { api } from "../api";
import { EChart } from "../components/EChart";
import { ErrorNotice, LoadingState, StatusBadge } from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, AssetQrLabel, ResourceAsset, ShellRecord, Task } from "../types";

type DisplayZone = {
  key: "WAX" | "SHELL";
  title: string;
  subtitle: string;
  workstationCode: string;
  operations: string[];
  operationLabels: Record<string, string>;
};

type ScreenScan = {
  kind: "TASK" | "BATCH" | "ASSET" | "UNKNOWN";
  title: string;
  subtitle: string;
  facts: string[];
};

const waxZone: DisplayZone = {
  key: "WAX",
  title: "蜡间生产大屏",
  subtitle: "射蜡、修蜡、组树现场态势",
  workstationCode: "WAX-DISPLAY-01",
  operations: ["WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY"],
  operationLabels: { WAX_INJECTION: "射蜡", WAX_REPAIR: "修蜡", TREE_ASSEMBLY: "组树" }
};

const shellZone: DisplayZone = {
  key: "SHELL",
  title: "制壳车间大屏",
  subtitle: "自动化制壳、手动制壳的批次层数与干燥状态",
  workstationCode: "SHELL-DISPLAY-01",
  operations: ["SHELL_BUILDING", "MANUAL_SHELL_BUILDING"],
  operationLabels: { SHELL_BUILDING: "自动化制壳", MANUAL_SHELL_BUILDING: "手动制壳" }
};

const statusWeight: Record<Task["status"], number> = {
  IN_PROGRESS: 0,
  READY: 1,
  ASSIGNED: 2,
  BLOCKED: 3,
  COMPLETED: 4
};

function zoneFor(user: AccessUser) {
  return user.unitCode === "SHELL_WORKSHOP" ? shellZone : waxZone;
}

function formatQuantity(value: number) {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(value);
}

function normaliseScan(value: string) {
  return value.trim().toUpperCase();
}

function displayTask(task: Task) {
  return `${task.productCode} · ${task.productName}${task.productMaterial ? ` · ${task.productMaterial}` : ""}`;
}

function routeLabel(routeType: Task["routeType"]) {
  return routeType === "MID_TEMP_WAX" ? "中温蜡" : routeType === "LOW_TEMP_WAX" ? "低温蜡" : "砂型外协";
}

function waitsForPreviousOperation(task: Task, allTasks: Task[]) {
  if (task.status !== "BLOCKED") return false;
  const previous = allTasks
    .filter((candidate) => candidate.batchId === task.batchId && candidate.sequenceNo < task.sequenceNo)
    .sort((left, right) => right.sequenceNo - left.sequenceNo)[0];
  return previous != null && previous.status !== "COMPLETED";
}

function resolveScreenScan(value: string, tasks: Task[], assets: ResourceAsset[], labels: AssetQrLabel[]): ScreenScan | null {
  const scanned = normaliseScan(value);
  if (!scanned) return null;
  const taskCode = scanned.startsWith("MES:TASK:") ? scanned.slice("MES:TASK:".length).trim() : scanned;
  const task = tasks.find((item) => item.id.toUpperCase() === taskCode || item.taskNo.toUpperCase() === taskCode);
  if (task) {
    return { kind: "TASK", title: task.taskNo, subtitle: `${task.operationName} · ${displayTask(task)}`, facts: [`批次 ${task.batchNo}`, `计划 ${formatQuantity(task.plannedQuantity)}`, `累计合格 ${formatQuantity(task.goodQuantity)}`, task.assignedTo ? `执行 ${task.assignedTo}` : "待派工"] };
  }
  const batchCode = scanned.startsWith("MES:BATCH:") ? scanned.slice("MES:BATCH:".length).trim() : scanned;
  const batchTasks = tasks.filter((item) => item.batchId.toUpperCase() === batchCode || item.batchNo.toUpperCase() === batchCode).sort((left, right) => left.sequenceNo - right.sequenceNo);
  if (batchTasks.length) {
    const current = batchTasks.find((item) => item.status !== "COMPLETED") ?? batchTasks.at(-1)!;
    return { kind: "BATCH", title: batchTasks[0].batchNo, subtitle: displayTask(batchTasks[0]), facts: [`共 ${batchTasks.length} 道工序`, `当前 ${current.operationName}`, `状态 ${current.status}`, current.assignedTo ? `执行 ${current.assignedTo}` : "待派工"] };
  }
  const label = labels.find((item) => item.qrToken.toUpperCase() === scanned.replace(/^MES:ASSET_QR:/, "") || item.labelNo?.toUpperCase() === scanned);
  const asset = assets.find((item) => item.id === label?.assetId) ?? assets.find((item) => item.assetCode.toUpperCase() === scanned || item.id.toUpperCase() === scanned);
  if (!asset) return { kind: "UNKNOWN", title: "未识别编码", subtitle: "未匹配到生产任务、生产批次、模具或周转车资产。", facts: [scanned] };
  return { kind: "ASSET", title: asset.assetCode, subtitle: `${asset.assetName} · ${asset.assetType}`, facts: [asset.locationCode ? `位置 ${asset.locationCode}` : "未登记库位", `状态 ${asset.status}`, asset.assetType === "MOLD" ? `模具状态 ${asset.moldCustodyStatus}` : "资产核验完成"] };
}

export function WorkshopDisplayPage({ user }: { user: AccessUser }) {
  const zone = zoneFor(user);
  const [now, setNow] = useState(() => new Date());
  const [scanValue, setScanValue] = useState("");
  const [scanResult, setScanResult] = useState<ScreenScan | null>(null);
  const [scanSequence, setScanSequence] = useState(0);
  const [lastSyncedAt, setLastSyncedAt] = useState<Date | null>(null);
  const scanInputRef = useRef<HTMLInputElement>(null);
  const state = useAsyncData(async () => {
    const [tasks, assets, labels] = await Promise.all([api.tasks.list(), api.resources.list(), api.assetQrs.list()]);
    const shellTasks = zone.key === "SHELL" ? tasks.filter((task) => zone.operations.includes(task.operationCode) && task.status !== "COMPLETED") : [];
    const shellRecords = Object.fromEntries(await Promise.all(shellTasks.map(async (task) => [task.id, await api.labor.shellRecords(task.id)] as const)));
    return { tasks, assets, labels, shellRecords };
  }, [zone.key]);

  useEffect(() => {
    if (state.data) setLastSyncedAt(new Date());
  }, [state.data]);

  useEffect(() => {
    const clock = window.setInterval(() => setNow(new Date()), 1000);
    const refresh = window.setInterval(() => void state.reload(), 20_000);
    return () => { window.clearInterval(clock); window.clearInterval(refresh); };
  }, [state.reload]);

  useEffect(() => {
    if (!scanResult) return;
    const timeout = window.setTimeout(() => {
      setScanResult(null);
      scanInputRef.current?.focus();
    }, scanResult.kind === "UNKNOWN" ? 5_000 : 12_000);
    return () => window.clearTimeout(timeout);
  }, [scanResult]);

  const visibleTasks = useMemo(() => (state.data?.tasks ?? [])
    .filter((task) => zone.operations.includes(task.operationCode))
    .sort((left, right) => statusWeight[left.status] - statusWeight[right.status] || left.createdAt.localeCompare(right.createdAt)), [state.data?.tasks, zone]);
  const activeTasks = visibleTasks.filter((task) => task.status !== "COMPLETED");
  const inProgress = visibleTasks.filter((task) => task.status === "IN_PROGRESS");
  const readyTasks = visibleTasks.filter((task) => task.status === "READY");
  const blockedTasks = visibleTasks.filter((task) => task.status === "BLOCKED");
  const dependencyWaitingTasks = blockedTasks.filter((task) => waitsForPreviousOperation(task, state.data?.tasks ?? []));
  const actionableBlockedTasks = blockedTasks.filter((task) => !waitsForPreviousOperation(task, state.data?.tasks ?? []));
  const queueTasks = activeTasks.filter((task) => !waitsForPreviousOperation(task, state.data?.tasks ?? []));
  const unassignedReadyTasks = readyTasks.filter((task) => !task.assignedTo);
  const goodQuantity = visibleTasks.reduce((total, task) => total + task.goodQuantity, 0);
  const scrapQuantity = visibleTasks.reduce((total, task) => total + task.scrapQuantity, 0);
  const alerts = activeTasks.filter((task) => task.scrapQuantity > 0);
  const risks = [
    ...actionableBlockedTasks.map((task) => ({ key: `blocked-${task.id}`, task, tone: "阻塞", detail: "前序已完成，请主管核对工序状态或交接异常" })),
    ...alerts.map((task) => ({ key: `scrap-${task.id}`, task, tone: `报废 ${formatQuantity(task.scrapQuantity)}`, detail: "请核对质量与补产安排" }))
  ];
  const shellRecords = state.data?.shellRecords ?? {};
  const latestShellRecord = (task: Task): ShellRecord | undefined => shellRecords[task.id]?.at(-1);
  const nextLayerTasks = activeTasks.filter((task) => latestShellRecord(task)?.nextAction === "WAIT_NEXT_LAYER");
  const operationFlow = zone.operations.map((code, index) => {
    const tasks = visibleTasks.filter((task) => task.operationCode === code);
    const inWork = tasks.filter((task) => task.status === "IN_PROGRESS" || task.status === "ASSIGNED").length;
    const planned = tasks.reduce((total, task) => total + task.plannedQuantity, 0);
    const good = tasks.reduce((total, task) => total + task.goodQuantity, 0);
    return { code, index, label: zone.operationLabels[code], inWork, good, progress: planned > 0 ? Math.min(100, Math.round((good / planned) * 100)) : 0 };
  });
  const outputOption = useMemo<EChartsOption>(() => {
    const grid = { left: 42, right: 18, top: 20, bottom: 34 };
    const categoryAxis = { axisLine: { lineStyle: { color: "#405254" } }, axisLabel: { color: "#aebfba", fontSize: 11 } };
    const valueAxis = { minInterval: 1, splitLine: { lineStyle: { color: "#293b3d" } }, axisLine: { show: false }, axisLabel: { color: "#aebfba", fontSize: 11 } };
    if (zone.key === "SHELL") {
      const batches = activeTasks.slice(0, 8);
      return {
        animation: false,
        aria: { enabled: true },
        color: ["#3eb99c"],
        grid,
        tooltip: { trigger: "axis", axisPointer: { type: "shadow" }, valueFormatter: (value) => `第 ${value} 次` },
        xAxis: { type: "value", max: Math.max(6, ...batches.map((task) => latestShellRecord(task)?.layerCount ?? 0)) + 1, ...valueAxis },
        yAxis: { type: "category", data: batches.map((task) => task.batchNo), ...categoryAxis },
        series: [{ name: "当前制壳层数", type: "bar", barMaxWidth: 18, data: batches.map((task) => latestShellRecord(task)?.layerCount ?? 0), label: { show: true, position: "right", color: "#dceae5", formatter: ({ value }) => value ? `第 ${value} 次` : "待首层" } }]
      };
    }
    return {
      animation: false,
      aria: { enabled: true },
      color: ["#3eb99c", "#d77b48"],
      grid,
      legend: { right: 8, top: 0, textStyle: { color: "#aebfba", fontSize: 11 }, itemWidth: 12, itemHeight: 7 },
      tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
      xAxis: { type: "category", data: zone.operations.map((code) => zone.operationLabels[code]), ...categoryAxis },
      yAxis: { type: "value", ...valueAxis },
      series: [
        { name: "合格", type: "bar", barMaxWidth: 26, data: zone.operations.map((code) => visibleTasks.filter((task) => task.operationCode === code).reduce((total, task) => total + task.goodQuantity, 0)) },
        { name: "报废", type: "bar", barMaxWidth: 26, data: zone.operations.map((code) => visibleTasks.filter((task) => task.operationCode === code).reduce((total, task) => total + task.scrapQuantity, 0)) }
      ]
    };
  }, [activeTasks, shellRecords, visibleTasks, zone]);
  const executionOption = useMemo<EChartsOption>(() => {
    const statusLabels: Array<[string, string, number]> = [
      ["READY", "待接收", readyTasks.length],
      ["ASSIGNED", "已派工", activeTasks.filter((task) => task.status === "ASSIGNED").length],
      ["IN_PROGRESS", "作业中", inProgress.length],
      ["WAITING", "等待前序", dependencyWaitingTasks.length],
      ["BLOCKED", "异常阻塞", actionableBlockedTasks.length]
    ];
    return {
      animation: false,
      aria: { enabled: true },
      color: ["#4db4a1", "#e2b24a", "#3e8ec0", "#607c79", "#d77b48"],
      tooltip: { trigger: "item" },
      series: [{
        type: "pie",
        radius: ["48%", "72%"],
        center: ["50%", "52%"],
        avoidLabelOverlap: true,
        label: { color: "#c9d8d2", fontSize: 11, formatter: "{b} {c}" },
        labelLine: { lineStyle: { color: "#526563" } },
        data: statusLabels.map(([, name, value]) => ({ name, value }))
      }]
    };
  }, [actionableBlockedTasks.length, activeTasks, dependencyWaitingTasks.length, inProgress.length, readyTasks.length]);

  function inspectScan() {
    if (!state.data) return;
    const result = resolveScreenScan(scanValue, state.data.tasks, state.data.assets, state.data.labels);
    setScanResult(result);
    setScanSequence((sequence) => sequence + 1);
    if (!result) return;
    const task = state.data.tasks.find((item) => item.taskNo === result.title);
    const batch = state.data.tasks.find((item) => item.batchNo === result.title);
    const asset = state.data.assets.find((item) => item.assetCode === result.title);
    const entity = task ? { type: "TASK" as const, id: task.id } : batch ? { type: "BATCH" as const, id: batch.batchId } : asset ? { type: "ASSET" as const, id: asset.id } : null;
    if (entity) void api.scanEvents.record({ operationId: crypto.randomUUID(), entityType: entity.type, entityId: entity.id, intent: "DISPLAY_VIEW", operatorCode: user.employeeCode, scannedValue: scanValue, workstationCode: zone.workstationCode }).catch(() => undefined);
    setScanValue("");
    window.setTimeout(() => scanInputRef.current?.focus(), 0);
  }

  function submitScan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    inspectScan();
  }

  if (state.loading && !state.data) return <LoadingState label="正在同步车间大屏数据" />;
  if (state.error && !state.data) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const ScanIcon = scanResult?.kind === "TASK" ? ClipboardList : scanResult?.kind === "BATCH" ? Boxes : scanResult?.kind === "ASSET" ? PackageSearch : CircleAlert;
  const scanTitle = scanResult?.kind === "TASK" ? "生产任务已定位" : scanResult?.kind === "BATCH" ? "生产批次已定位" : scanResult?.kind === "ASSET" ? "现场资产已定位" : "编码未识别";

  return <div className={`workshop-display workshop-display-${zone.key.toLowerCase()}`}>
    <header className="workshop-display-header">
      <div><span className="workshop-display-kicker"><Factory aria-hidden="true" /> 铸造 MES · 现场只读屏</span><h1>{zone.title}</h1><p>{zone.subtitle}</p></div>
      <div className="workshop-display-clock"><span className="workshop-display-live"><i aria-hidden="true" /> 实时连接</span><strong>{now.toLocaleTimeString("zh-CN", { hour12: false })}</strong><span>{now.toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" })}</span><small>{lastSyncedAt ? `数据更新于 ${lastSyncedAt.toLocaleTimeString("zh-CN", { hour12: false })}` : "正在同步数据"}</small><button className="display-icon-button" type="button" onClick={() => void state.reload()} aria-label="刷新车间数据" title="刷新车间数据"><RefreshCw aria-hidden="true" /></button></div>
    </header>

    <section className="workshop-display-metrics" aria-label="车间实时指标">
      <article><span>{zone.key === "SHELL" ? "可执行制壳批次" : "可执行任务"}</span><strong>{queueTasks.length}</strong><small>{inProgress.length} 项正在作业</small></article>
      <article><span>{zone.key === "SHELL" ? "待下一次制壳" : "待接收任务"}</span><strong>{zone.key === "SHELL" ? nextLayerTasks.length : readyTasks.length}</strong><small>{zone.key === "SHELL" ? "已报层次，等待继续制壳" : `${unassignedReadyTasks.length} 项待派工，其余可接收`}</small></article>
      <article><span>{zone.key === "SHELL" ? "已报制壳层" : "累计合格"}</span><strong>{zone.key === "SHELL" ? Object.values(shellRecords).reduce((total, records) => total + records.length, 0) : formatQuantity(goodQuantity)}</strong><small>{zone.key === "SHELL" ? "当前在制批次的逐层记录" : "本车间已入账报工"}</small></article>
      <article className={risks.length ? "has-alert" : ""}><span>需主管处理</span><strong>{risks.length}</strong><small>{risks.length ? `${actionableBlockedTasks.length} 项状态异常，${alerts.length} 项报废需关注` : `${dependencyWaitingTasks.length} 项正常等待前序`}</small></article>
    </section>

    <section className="workshop-flow-rail" aria-label="工序流动概览">
      <header><span>实时工序流动</span><small>绿色表示累计合格进度，标签显示现场在制数量</small></header>
      <div>{operationFlow.map((stage) => <article key={stage.code}><div><span>{String(stage.index + 1).padStart(2, "0")}</span><strong>{stage.label}</strong><b>{stage.inWork} 在制</b></div><div className="workshop-flow-track"><span style={{ width: `${stage.progress}%` }} /></div><small>累计合格 {formatQuantity(stage.good)} · {stage.progress}%</small></article>)}</div>
    </section>

    <section className="workshop-display-main-grid">
      <section className="workshop-queue" aria-labelledby="workshop-queue-title">
        <header><div><span>执行队列</span><h2 id="workshop-queue-title">当前工序与产品</h2></div><span>{queueTasks.length} 项可执行 · {dependencyWaitingTasks.length} 项等待前序</span></header>
        <div className="workshop-queue-list">
          {queueTasks.length === 0 ? <p className="workshop-empty">当前没有可执行任务；等待前序的工序已从现场队列折叠。</p> : queueTasks.slice(0, 8).map((task) => { const record = zone.key === "SHELL" ? latestShellRecord(task) : undefined; return <article key={task.id} className={`workshop-task workshop-task-${task.status.toLowerCase()}`}><div className="workshop-task-operation"><span>{zone.operationLabels[task.operationCode] ?? task.operationName}</span><StatusBadge value={task.status} /></div><strong>{task.productName}</strong><p>{task.productCode}{task.productMaterial ? ` · ${task.productMaterial}` : ""}</p><span className={`workshop-task-route workshop-task-route-${task.routeType.toLowerCase()}`}>{routeLabel(task.routeType)}</span>{record && <div className="workshop-shell-layer"><strong>第 {record.layerCount} 次制壳</strong><span>{record.nextAction === "WAIT_NEXT_LAYER" ? "等待下次制壳" : "已申请流转"}</span><small>干燥 {record.dryingMinutes} 分钟 · {record.method === "AUTOMATED" ? "自动线" : "手工制壳"}</small></div>}{zone.key === "SHELL" && !record && <div className="workshop-shell-layer pending"><strong>待首层制壳</strong><span>尚未报备层次</span></div>}<div className="workshop-task-facts"><span>{task.batchNo}</span><span>{formatQuantity(task.goodQuantity)} / {formatQuantity(task.plannedQuantity)}</span><span>{task.assignedTo ? `执行 ${task.assignedTo}` : "待派工"}</span></div><div className="workshop-progress"><span style={{ width: `${Math.min(100, task.plannedQuantity ? (task.goodQuantity / task.plannedQuantity) * 100 : 0)}%` }} /></div></article>; })}</div>
      </section>

      <aside className="workshop-display-side">
        <section className="workshop-scanner" aria-labelledby="workshop-scan-title">
          <div><ScanLine aria-hidden="true" /><div><span>扫码定位</span><h2 id="workshop-scan-title">外设扫码枪</h2></div></div>
          <p>扫码枪输入后按回车；支持任务、批次、模具和周转车资产码。</p>
          <form onSubmit={submitScan}><input ref={scanInputRef} value={scanValue} onChange={(event) => setScanValue(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); inspectScan(); } }} placeholder="等待扫码枪输入" autoFocus aria-label="车间大屏扫码输入" /><button type="submit" aria-label="核验扫码" title="核验扫码"><ScanLine aria-hidden="true" /></button></form>
          {scanValue && <small>已接收编码，等待回车核验</small>}
          {scanResult && <article className="workshop-scan-result"><span>扫码结果已投放至主屏</span><strong>{scanResult.title}</strong><p>将自动恢复常规大屏。</p></article>}
          {scanResult === null && scanValue === "" && <div className="workshop-scan-idle"><TimerReset aria-hidden="true" /> 扫码后仅展示追溯信息，不改变任务状态。</div>}
        </section>

        <section className="workshop-output-chart" aria-labelledby="workshop-output-title">
          <header><div><span>{zone.key === "SHELL" ? "层次进度" : "工序产出"}</span><h2 id="workshop-output-title">{zone.key === "SHELL" ? "批次当前制壳层数" : "累计合格与报废"}</h2></div><small>自动汇总</small></header>
          <EChart option={outputOption} label={zone.key === "SHELL" ? `${zone.title}各批次当前制壳层数柱状图` : `${zone.title}各工序累计合格与报废柱状图`} height={190} />
        </section>

        <section className="workshop-output-chart workshop-execution-chart" aria-labelledby="workshop-execution-title">
          <header><div><span>任务状态</span><h2 id="workshop-execution-title">当前执行分布</h2></div><small>仅在制任务</small></header>
          <EChart option={executionOption} label={`${zone.title}当前任务状态分布图`} height={184} />
        </section>

        <section className="workshop-alerts" aria-labelledby="workshop-alert-title"><header><div><AlertTriangle aria-hidden="true" /><h2 id="workshop-alert-title">现场风险</h2></div><span>{risks.length}</span></header>{risks.length === 0 ? <p>当前车间没有阻塞或已入账报废记录。</p> : <div>{risks.slice(0, 5).map((risk) => <article key={risk.key}><strong>{risk.task.operationName} · {risk.task.productName}</strong><span>{risk.tone}</span><small>{risk.task.batchNo} · {risk.detail}</small></article>)}</div>}</section>
      </aside>
    </section>

    {state.error != null && <div className="workshop-display-error"><ErrorNotice error={state.error} onRetry={state.reload} /></div>}
    <footer><Boxes aria-hidden="true" /> 自动刷新 20 秒 · 当前显示账号 {user.employeeCode} · 扫码仅定位追溯，不变更任务状态</footer>
    {scanResult && <section className={`workshop-scan-overlay workshop-scan-${scanResult.kind.toLowerCase()}`} key={scanSequence} role="status" aria-live="assertive">
      <div className="workshop-scan-overlay-frame">
        <header><span><ScanLine aria-hidden="true" /> 扫码定位</span><button type="button" onClick={() => { setScanResult(null); scanInputRef.current?.focus(); }} aria-label="关闭扫码展示" title="关闭扫码展示"><X aria-hidden="true" /></button></header>
        <div className="workshop-scan-target"><ScanIcon aria-hidden="true" /><i aria-hidden="true" /></div>
        <div className="workshop-scan-overlay-copy"><span>{scanTitle}</span><h2>{scanResult.title}</h2><p>{scanResult.subtitle}</p><div>{scanResult.facts.map((fact) => <strong key={fact}>{fact}</strong>)}</div></div>
        <footer><span>{scanResult.kind === "UNKNOWN" ? "5 秒后恢复大屏" : "12 秒后恢复大屏"}</span><i aria-hidden="true" /></footer>
      </div>
    </section>}
  </div>;
}
