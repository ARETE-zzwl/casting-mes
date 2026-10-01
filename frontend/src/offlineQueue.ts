import { api } from "./api";

type PendingReport = {
  id: string;
  kind: "REPORT" | "TREE_REPORT";
  taskId: string;
  operatorCode: string;
  payload: Record<string, number | string>;
  createdAt: string;
};

const STORAGE_KEY = "casting-mes.pending-reports.v1";
const activeFlushes = new Map<string, Promise<number>>();

function read(): PendingReport[] {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "[]") as PendingReport[]; }
  catch { return []; }
}
function write(items: PendingReport[]) { localStorage.setItem(STORAGE_KEY, JSON.stringify(items)); }
export function pendingReportCount(operatorCode: string) { return read().filter((item) => item.operatorCode === operatorCode).length; }
export function queueReport(item: PendingReport) { write([...read(), item]); }

export function deviceCode() {
  const key = "casting-mes.device-code";
  const existing = localStorage.getItem(key);
  if (existing) return existing;
  const value = `WEB-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  localStorage.setItem(key, value);
  return value;
}

async function flushEmployeeReports(operatorCode: string) {
  const completedIds = new Set<string>();
  for (const item of read().filter((report) => report.operatorCode === operatorCode)) {
    try {
      if (item.kind === "TREE_REPORT") await api.tasks.reportTree(item.taskId, item.operatorCode, item.payload as { operationId: string; treeCount: number; piecesPerTree: number; scrapQuantity: number; deviceCode?: string; workstationCode?: string });
      else await api.tasks.report(item.taskId, item.operatorCode, item.payload as { operationId: string; goodQuantity: number; scrapQuantity: number; deviceCode?: string; workstationCode?: string });
      completedIds.add(item.id);
    } catch { /* Keep failed reports for the next explicit or online retry. */ }
  }
  // Re-read after network calls so new reports and other employees' records survive.
  write(read().filter((item) => item.operatorCode !== operatorCode || !completedIds.has(item.id)));
  return pendingReportCount(operatorCode);
}

export function flushPendingReports(operatorCode: string) {
  const existing = activeFlushes.get(operatorCode);
  if (existing) return existing;
  const pending = flushEmployeeReports(operatorCode).finally(() => activeFlushes.delete(operatorCode));
  activeFlushes.set(operatorCode, pending);
  return pending;
}
