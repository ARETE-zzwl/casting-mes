import { beforeEach, expect, it, vi } from "vitest";
import { flushPendingReports, pendingReportCount, queueReport } from "./offlineQueue";

const calls = vi.hoisted(() => ({ report: vi.fn(), reportTree: vi.fn() }));
vi.mock("./api", () => ({ api: { tasks: calls } }));

function report(id: string, operatorCode = "W001", kind: "REPORT" | "TREE_REPORT" = "REPORT") {
  return {
    id, operatorCode, kind, taskId: `task-${id}`, createdAt: "2026-10-01T00:00:00Z",
    payload: { operationId: `operation-${id}`, goodQuantity: 2, scrapQuantity: 0, treeCount: 1, piecesPerTree: 2 }
  };
}

beforeEach(() => {
  localStorage.clear();
  calls.report.mockReset().mockResolvedValue({});
  calls.reportTree.mockReset().mockResolvedValue({});
});

it("counts and retries only the current employee's records on a shared device", async () => {
  queueReport(report("previous", "W001"));
  queueReport(report("current", "WR01"));
  expect(pendingReportCount("WR01")).toBe(1);
  expect(await flushPendingReports("WR01")).toBe(0);
  expect(calls.report).toHaveBeenCalledExactlyOnceWith("task-current", "WR01", report("current", "WR01").payload);
  expect(pendingReportCount("W001")).toBe(1);
  expect(pendingReportCount("WR01")).toBe(0);
});

it("retains records added while a retry is awaiting the server", async () => {
  let resolve!: (value: unknown) => void;
  calls.report.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
  queueReport(report("original"));
  const retry = flushPendingReports("W001");
  queueReport(report("new"));
  queueReport(report("other", "WR01"));
  resolve({});
  expect(await retry).toBe(1);
  expect(pendingReportCount("WR01")).toBe(1);
  expect(await flushPendingReports("W001")).toBe(0);
  expect(calls.report.mock.calls.map(([taskId]) => taskId)).toEqual(["task-original", "task-new"]);
});

it("keeps failed reports and removes successful tree reports without changing idempotency keys", async () => {
  calls.report.mockRejectedValueOnce(new TypeError("offline"));
  queueReport(report("failed"));
  queueReport(report("tree", "W001", "TREE_REPORT"));
  expect(await flushPendingReports("W001")).toBe(1);
  expect(calls.reportTree).toHaveBeenCalledExactlyOnceWith("task-tree", "W001", report("tree", "W001", "TREE_REPORT").payload);
  expect(await flushPendingReports("W001")).toBe(0);
  expect(calls.report.mock.calls[1]).toEqual(calls.report.mock.calls[0]);
});

it("shares an in-flight retry for the same employee", async () => {
  let resolve!: (value: unknown) => void;
  calls.report.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
  queueReport(report("original"));
  const first = flushPendingReports("W001");
  const second = flushPendingReports("W001");
  expect(calls.report).toHaveBeenCalledOnce();
  resolve({});
  expect(await first).toBe(0);
  expect(await second).toBe(0);
});
