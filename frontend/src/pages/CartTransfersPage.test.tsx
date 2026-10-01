import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../api";
import type { CartTransfer, ResourceAsset } from "../types";
import { CartTransfersPage } from "./CartTransfersPage";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it("loads authorized transfer sources without requiring the next worker's task and resets after loading", async () => {
  vi.spyOn(api.cartTransfers, "list").mockResolvedValue([]);
  vi.spyOn(api.resources, "list").mockResolvedValue([{ id: "cart", assetCode: "CART-01", assetName: "Cart", assetType: "CARRIER", status: "AVAILABLE" } as ResourceAsset]);
  vi.spyOn(api.cartTransfers, "readySources").mockResolvedValue([{ id: "source", taskNo: "TK-01", operationName: "组树", routeType: "MID_TEMP_WAX", productName: "Valve", productMaterial: "CF8", orderNo: "SO-01", batchNo: "PB-01", availableQuantity: 6 }]);
  const tasks = vi.spyOn(api.tasks, "list");
  const load = vi.spyOn(api.cartTransfers, "load").mockResolvedValue({ id: "transfer" } as CartTransfer);
  render(<CartTransfersPage operatorCode="TA01" />);
  await screen.findByRole("option", { name: /Valve.*CF8/ });
  fireEvent.change(screen.getByLabelText("来源工序任务", { exact: false }), { target: { value: "source" } });
  fireEvent.change(screen.getByLabelText("周转车编码", { exact: false }), { target: { value: "CART-01" } });
  fireEvent.change(screen.getByLabelText("装车数量", { exact: false }), { target: { value: "6" } });
  fireEvent.change(screen.getByLabelText("操作工位 / 地点", { exact: false }), { target: { value: "TREE-TRANSFER" } });
  fireEvent.click(screen.getByRole("button", { name: "确认装车并生成流转记录" }));
  await waitFor(() => expect(load).toHaveBeenCalledOnce());
  await waitFor(() => expect(screen.getByLabelText("周转车编码", { exact: false })).toHaveValue(""));
  expect(load).toHaveBeenCalledWith(expect.objectContaining({ sourceTaskId: "source", quantity: 6, loadedBy: "TA01" }));
  expect(tasks).not.toHaveBeenCalled();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
