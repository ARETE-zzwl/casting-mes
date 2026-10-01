import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";
import type { Order, OrderTrace } from "../types";
import { TracePage } from "./TracePage";

const order = {
  id: "order-1",
  orderNo: "SO-TRACE-001",
  customerId: "customer-1",
  customerCode: "CUS-001",
  customerName: "追溯测试客户",
  status: "RELEASED",
  priority: "NORMAL",
  routeType: "MID_TEMP_WAX",
  requestedDeliveryDate: null,
  remark: null,
  orderDrawingUrl: null,
  contractAttachmentUrl: null,
  createdBy: "FD01",
  processCardVersion: null,
  engineeringParameters: null,
  engineeringOperationParameters: null,
  engineeringConfirmedBy: null,
  engineeringConfirmedAt: null,
  defaultProcessCardVersion: null,
  defaultProcessReleasedBy: null,
  defaultProcessReleasedAt: null,
  createdAt: "2026-07-29T00:00:00Z",
  approvedAt: null,
  releasedAt: "2026-07-29T00:00:00Z",
  customerManagerCode: null,
  customerManagerReviewedAt: null,
  customerManagerReviewNote: null,
  generalManagerCode: null,
  generalManagerReviewedAt: null,
  generalManagerReviewNote: null,
  lines: [{
    id: "line-1",
    lineNo: 1,
    productId: "product-1",
    productCode: "PRD-CF8-001",
    productName: "CF8 阀体",
    routeType: "MID_TEMP_WAX",
    routeVersion: "V1",
    modelImageUrl: null,
    productMaterial: "CF8",
    orderedQuantity: 20,
    unit: "PCS",
    salesUnitPrice: null,
    salesPriceUnit: null,
    processCardVersion: null,
    engineeringParameters: null,
    engineeringOperationParameters: null,
    engineeringConfirmedBy: null,
    engineeringConfirmedAt: null,
    defaultProcessCardVersion: null,
    defaultProcessReleasedBy: null,
    defaultProcessReleasedAt: null
  }]
} as Order;

describe("TracePage", () => {
  beforeEach(() => {
    vi.spyOn(api.orders, "list").mockResolvedValue([order]);
    vi.spyOn(api.tasks, "list").mockResolvedValue([]);
    vi.spyOn(api.trace, "order").mockResolvedValue({
      order,
      workOrders: [{
        workOrder: { orderLineId: "line-1", batchId: "batch-1", batchNo: "PB-001", workOrderNo: "WO-001", plannedQuantity: 20, status: "RELEASED" },
        tasks: []
      }],
      timeline: []
    } as unknown as OrderTrace);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("shows the material for each traced order product", async () => {
    render(<MemoryRouter initialEntries={["/trace?orderId=order-1"]}><TracePage /></MemoryRouter>);

    expect(await screen.findByText((_, element) => element?.tagName === "P" && element.textContent?.includes("材质：CF8") === true)).toBeInTheDocument();
  });
});
