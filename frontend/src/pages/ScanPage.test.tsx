import { describe, expect, it } from "vitest";
import type { AccessUser, Task } from "../types";
import { assigneeLabel, batchCurrentTask } from "./ScanPage";

const users: AccessUser[] = [{
  employeeCode: "WX01",
  name: "射蜡员工01",
  unitCode: "MID_WAX",
  unitName: "中温蜡车间",
  primaryRole: "WAX_INJECTION_OPERATOR",
  roles: ["WAX_INJECTION_OPERATOR"],
  permissions: [],
  active: true
}];

describe("assigneeLabel", () => {
  it("shows the dispatch target as employee code and name", () => {
    expect(assigneeLabel("WX01", users)).toBe("WX01 · 射蜡员工01");
  });

  it("keeps the employee code when historical user data is unavailable", () => {
    expect(assigneeLabel("ARCHIVED01", users)).toBe("ARCHIVED01");
  });
});

describe("batchCurrentTask", () => {
  const task = (sequenceNo: number, status: Task["status"], assignedTo: string | null = null) => ({
    id: `task-${sequenceNo}`,
    sequenceNo,
    status,
    assignedTo,
    operationName: `工序${sequenceNo}`
  } as Task);

  it("keeps an unlaunched batch on its first operation", () => {
    expect(batchCurrentTask([task(3, "BLOCKED"), task(1, "BLOCKED"), task(2, "BLOCKED")], "PM01")?.operationName).toBe("工序1");
  });

  it("prioritizes the current user's assigned operation", () => {
    expect(batchCurrentTask([task(1, "READY"), task(2, "ASSIGNED", "WX01")], "WX01")?.operationName).toBe("工序2");
  });
});
