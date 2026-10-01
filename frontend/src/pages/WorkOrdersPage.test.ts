import { describe, expect, it } from "vitest";
import { batchProgressStatus, productionMargin } from "./WorkOrdersPage";

describe("productionMargin", () => {
  it("calculates the surplus from the work-order total rather than an individual batch", () => {
    expect(productionMargin(1100, 1000)).toEqual({ quantity: 100, rate: 10 });
  });

  it("does not report a negative production margin for a split batch", () => {
    expect(productionMargin(550, 1000)).toEqual({ quantity: 0, rate: 0 });
  });

  it("uses the real current task instead of calling a released work order started", () => {
    expect(batchProgressStatus({ taskCount: 11, completedTaskCount: 0, currentOperationName: "射蜡", currentTaskStatus: "READY" }))
      .toEqual({ status: "READY", operation: "射蜡" });
  });
});
