import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";
import type { AccessUser } from "../types";
import { PlatformPage } from "./PlatformPage";
import { RoleWorkspacePage } from "./RoleWorkspacePage";
import { AssetQrBindingPage } from "./AssetQrBindingPage";

function user(role: string, permissions: string[]): AccessUser {
  return { employeeCode: "TEST", name: "Test", unitCode: "OFFICE", unitName: "Office", primaryRole: role, roles: [role], permissions, active: true };
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("workspace authorization", () => {
  it("keeps mold receipt data and actions out of a carrier-only binding page", async () => {
    vi.spyOn(api.assetQrs, "list").mockResolvedValue([]);
    vi.spyOn(api.resources, "list").mockResolvedValue([]);
    const selections = vi.spyOn(api.molds, "orderSelections");
    const locations = vi.spyOn(api.molds, "locations");
    render(<AssetQrBindingPage operatorCode="TEST" user={user("CART_OPERATOR", ["QR_BIND", "CART_OPERATE"])} />);
    await screen.findByRole("heading", { name: "周转车扫码绑定" });
    expect(selections).not.toHaveBeenCalled();
    expect(locations).not.toHaveBeenCalled();
    expect(screen.queryByRole("heading", { name: "扫码新建模具并绑定" })).not.toBeInTheDocument();
  });
  it("does not query production tasks for a finance-only workspace", async () => {
    const tasks = vi.spyOn(api.tasks, "list");
    render(<MemoryRouter><RoleWorkspacePage user={user("FINANCE_ACCOUNTANT", ["PIECEWORK_MANAGE"])} /></MemoryRouter>);
    await screen.findByText("本岗位工作入口");
    expect(tasks).not.toHaveBeenCalled();
  });

  it("does not load or display administrator configuration for workflow participants", async () => {
    vi.spyOn(api.workflows, "list").mockResolvedValue([]);
    vi.spyOn(api.workflows, "definitions").mockResolvedValue([]);
    const configurations = vi.spyOn(api.configurations, "list");
    const roles = vi.spyOn(api.access, "roles");
    render(<PlatformPage operatorCode="TEST" user={user("CUSTOMER_MANAGER", ["WORKFLOW_MANAGE"])} />);
    await screen.findByText("审批模板目录");
    expect(configurations).not.toHaveBeenCalled();
    expect(roles).not.toHaveBeenCalled();
    expect(screen.queryByText("新建配置包")).not.toBeInTheDocument();
    expect(screen.queryByText("自定义审批模板")).not.toBeInTheDocument();
  });

  it("keeps administrator configuration available", async () => {
    vi.spyOn(api.workflows, "list").mockResolvedValue([]);
    vi.spyOn(api.workflows, "definitions").mockResolvedValue([]);
    const configurations = vi.spyOn(api.configurations, "list").mockResolvedValue([]);
    const roles = vi.spyOn(api.access, "roles").mockResolvedValue([]);
    render(<PlatformPage operatorCode="TEST" user={user("SYSTEM_ADMIN", ["WORKFLOW_MANAGE", "CONFIG_MANAGE"])} />);
    await screen.findByText("新建配置包");
    expect(configurations).toHaveBeenCalledOnce();
    expect(roles).toHaveBeenCalledOnce();
  });
});
