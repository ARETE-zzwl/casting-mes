import { describe, expect, it } from "vitest";
import type { PieceworkRate, Task } from "../types";
import { applicableRateForTask, firstDayOfNextMonth, previousLocalMonth, shanghaiSettlementDate } from "./PieceworkManagementPage";

const baseTask = {
  routeType: "MID_TEMP_WAX",
  productCode: "PRD-VALVE",
  operationCode: "WAX_INJECTION",
  compensationMode: "PIECE_PCS",
  completedAt: "2026-07-16T16:00:00Z"
} as Task;

const rate = (effectiveFrom: string, unitRate: number): PieceworkRate => ({
  id: effectiveFrom,
  operationCode: "WAX_INJECTION",
  operationName: "射蜡",
  routeType: "MID_TEMP_WAX",
  productCode: "PRD-VALVE",
  productName: "阀体",
  version: `V-${effectiveFrom}`,
  settlementUnit: "PCS",
  unitRate,
  effectiveFrom,
  active: true,
  createdAt: `${effectiveFrom}T00:00:00Z`
});

describe("piecework payroll dates and rate matching", () => {
  it("uses local calendar dates for monthly defaults", () => {
    const now = new Date("2026-08-03T03:00:00Z");
    expect(firstDayOfNextMonth(now)).toBe("2026-09-01");
    expect(previousLocalMonth(now)).toBe("2026-07");
  });

  it("matches the latest rate that was effective on the Shanghai completion date", () => {
    expect(shanghaiSettlementDate(baseTask.completedAt)).toBe("2026-07-17");
    expect(applicableRateForTask([rate("2026-07-01", 0.85), rate("2026-07-22", 2.5)], baseTask)?.unitRate).toBe(0.85);
  });

  it("does not allow a future rate to make a historical task payable", () => {
    expect(applicableRateForTask([rate("2026-07-22", 2.5)], baseTask)).toBeNull();
  });

  it("prefers the product-specific rate over the generic fallback", () => {
    const generic = { ...rate("2026-07-01", 0.85), id: "generic", productCode: null, productName: null };
    expect(applicableRateForTask([generic, rate("2026-07-01", 1.2)], baseTask)?.unitRate).toBe(1.2);
  });
});
