import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";
import type { AccessUser, InventoryBalance, InventoryMovement } from "../types";
import { InventoryPage } from "./InventoryPage";

const user: AccessUser = {
  employeeCode: "K001",
  name: "原材料仓管",
  unitCode: "RM-01",
  unitName: "原材料仓",
  primaryRole: "RAW_MATERIAL_WAREHOUSE_KEEPER",
  roles: ["RAW_MATERIAL_WAREHOUSE_KEEPER"],
  permissions: ["RAW_MATERIAL_WAREHOUSE_MANAGE"],
  active: true
};

const balances: InventoryBalance[] = [
  { id: "b1", warehouseCode: "RM-01", itemCode: "MAT-304", itemName: "304 不锈钢料", unit: "KG", quantity: 125, version: 1, updatedAt: "2026-08-08T01:00:00Z" },
  { id: "b2", warehouseCode: "RM-01", itemCode: "MAT-WAX", itemName: "中温蜡", unit: "KG", quantity: 0, version: 1, updatedAt: "2026-08-08T01:00:00Z" }
];

const movements: InventoryMovement[] = [
  { id: "m1", operationId: "op1", movementNo: "IM-RECEIPT", balanceId: "b1", warehouseCode: "RM-01", itemCode: "MAT-304", itemName: "304 不锈钢料", unit: "KG", movementType: "RECEIPT", quantity: 125, balanceAfter: 125, referenceType: "采购送货单", referenceNo: "PO-001", operatorCode: "K001", remark: "首批收货", occurredAt: "2026-08-07T01:00:00Z" },
  { id: "m2", operationId: "op2", movementNo: "IM-ISSUE", balanceId: "b1", warehouseCode: "RM-01", itemCode: "MAT-304", itemName: "304 不锈钢料", unit: "KG", movementType: "ISSUE", quantity: 10, balanceAfter: 115, referenceType: "投料单", referenceNo: "WO-001", operatorCode: "K002", remark: "浇筑投料", occurredAt: "2026-08-08T01:00:00Z" }
];

describe("InventoryPage", () => {
  beforeEach(() => {
    vi.spyOn(api.inventory, "balancePage").mockImplementation(async (input) => {
      const items = input.stockStatus === "POSITIVE" ? balances.filter((item) => item.quantity > 0) : balances;
      return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 };
    });
    vi.spyOn(api.inventory, "movementPage").mockImplementation(async (input) => {
      const items = input.movementType === "ISSUE" ? movements.filter((item) => item.movementType === "ISSUE") : movements;
      return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 };
    });
    vi.spyOn(api.resources, "list").mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("filters balances and movements by multiple operational conditions without changing the ledger", async () => {
    render(<MemoryRouter><InventoryPage user={user} /></MemoryRouter>);

    await screen.findByText("服务端筛选结果 2 / 共 2 个物料台账");
    fireEvent.change(screen.getByLabelText("库存状态"), { target: { value: "POSITIVE" } });
    await waitFor(() => expect(screen.getByText("服务端筛选结果 1 / 共 1 个物料台账")).toBeInTheDocument());
    expect(screen.queryByText("中温蜡")).not.toBeInTheDocument();

    const movementType = screen.getAllByLabelText("业务类型").find((element) => element.tagName === "SELECT");
    expect(movementType).toBeDefined();
    fireEvent.change(movementType!, { target: { value: "ISSUE" } });
    await waitFor(() => expect(screen.getByText("服务端筛选结果 1 / 共 1 笔；按单号、物料、类型、责任人和日期追溯。")).toBeInTheDocument());
    expect(screen.getByText("IM-ISSUE")).toBeInTheDocument();
    expect(screen.queryByText("IM-RECEIPT")).not.toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "清空" })[0]);
    await waitFor(() => expect(screen.getByText("服务端筛选结果 2 / 共 2 个物料台账")).toBeInTheDocument());
  });
});
