import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { api } from "./api";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

const mockUsers = [
  {
    employeeCode: "A001",
    name: "系统管理员",
    unitCode: "DEMO_COMPANY",
    unitName: "示范精密铸造有限公司",
    primaryRole: "SYSTEM_ADMIN",
    roles: ["SYSTEM_ADMIN", "PRODUCTION_MANAGER"],
    permissions: [
      "ACCESS_MANAGE",
      "CUSTOMER_VIEW",
      "DASHBOARD_VIEW",
      "FULFILLMENT_MANAGE",
      "INVENTORY_MANAGE",
      "MASTERDATA_MANAGE",
      "ORDER_MANAGE",
      "OUTSOURCING_MANAGE",
      "PIECEWORK_MANAGE",
      "PLANNING_VIEW",
      "PLATFORM_ADMIN",
      "QUALITY_MANAGE",
      "SOP_MANAGE",
      "TASK_DISPATCH",
      "TASK_EXECUTE",
      "TRACE_VIEW",
      "WORKBENCH_VIEW",
      "WORKFLOW_MANAGE"
    ],
    active: true
  },
  {
    employeeCode: "W001",
    name: "射蜡操作员",
    unitCode: "WAX_TEAM",
    unitName: "精铸甲班",
    primaryRole: "OPERATOR",
    roles: ["OPERATOR"],
    permissions: ["TASK_EXECUTE", "WORKBENCH_VIEW"],
    active: true
  }
];

function mockSession() {
  const code = localStorage.getItem("mes.operatorCode") || "A001";
  return { user: mockUsers.find((user) => user.employeeCode === code) ?? mockUsers[0], mustChangePassword: false };
}

describe("Casting MES application", () => {
  beforeEach(() => {
    localStorage.setItem("mes.operatorCode", "A001");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request) => {
        const path = String(input);
        if (path === "/api/auth/config") return jsonResponse({ authenticationRequired: false });
        if (path === "/api/access/users") return jsonResponse(mockUsers);
        if (
          path.startsWith("/api/orders") ||
          path.startsWith("/api/work-orders") ||
          path.startsWith("/api/tasks")
        ) {
          return jsonResponse([]);
        }
        return jsonResponse({ status: "UP" });
      })
    );
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("renders the operational shell and empty order state", async () => {
    render(
      <MemoryRouter initialEntries={["/orders"]}>
        <App />
      </MemoryRouter>
    );

    expect(await screen.findByText("铸造 MES")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(await screen.findByText("暂无客户订单")).toBeInTheDocument();
    expect(screen.getByLabelText("当前模拟用户")).toHaveValue("A001");
  });

  it("shows real login rather than a role selector when a session is required", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
      if (String(input) === "/api/auth/config") return jsonResponse({ authenticationRequired: true });
      return jsonResponse({ status: 401 }, 401);
    }));
    render(<MemoryRouter><App /></MemoryRouter>);
    expect(await screen.findByRole("button", { name: "登录系统" })).toBeInTheDocument();
    expect(screen.queryByLabelText("当前模拟用户")).not.toBeInTheDocument();
  });

  it("ignores a stored administrator selection after authenticating as a worker", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
      if (String(input) === "/api/auth/config") return jsonResponse({ authenticationRequired: true });
      if (String(input) === "/api/auth/me") return jsonResponse({ user: mockUsers[1], mustChangePassword: false });
      return jsonResponse([]);
    }));
    render(<MemoryRouter initialEntries={["/workbench"]}><App /></MemoryRouter>);
    expect(await screen.findByRole("button", { name: "退出登录" })).toBeInTheDocument();
    expect(screen.queryByLabelText("当前模拟用户")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "账户权限" })).not.toBeInTheDocument();
  });

  it("fails closed when the authentication configuration is malformed", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ status: "UP" })));
    render(<MemoryRouter><App /></MemoryRouter>);
    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取登录配置");
    expect(screen.queryByLabelText("当前模拟用户")).not.toBeInTheDocument();
  });

  it("does not mount a restricted lazy page after a role switch", async () => {
    render(<MemoryRouter initialEntries={["/orders"]}><App /></MemoryRouter>);
    expect(await screen.findByText("暂无客户订单")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("当前模拟用户"), { target: { value: "W001" } });
    expect(await screen.findByRole("heading", { name: "生产流程指南" })).toBeInTheDocument();
    expect(screen.queryByText("暂无客户订单")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "客户订单" })).not.toBeInTheDocument();
  });

  it("keeps backend error code and field details", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "请求参数校验失败",
              details: [{ field: "orderNo", message: "不能为空" }]
            }
          },
          422
        )
      )
    );

    await expect(api.orders.list()).rejects.toMatchObject({
      code: "VALIDATION_ERROR",
      status: 422,
      message: "请求参数校验失败",
      details: [{ field: "orderNo", message: "不能为空" }]
    });
  });

  it("shows the current customer directory", async () => {
    const customers = [{
      id: "customer-1",
      code: "C1001",
      name: "华东精密铸造",
      contactName: "张工",
      contactPhone: "13800000000",
      salesOwner: "销售01",
      active: true,
      createdAt: "2026-07-23T08:00:00Z"
    }];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        if (path === "/api/auth/config") return jsonResponse({ authenticationRequired: false });
        if (path === "/api/access/users") return jsonResponse(mockUsers);
        if (path === "/api/customers") return jsonResponse(customers);
        return jsonResponse([]);
      })
    );

    render(
      <MemoryRouter initialEntries={["/customers"]}>
        <App />
      </MemoryRouter>
    );

    expect(await screen.findByText("C1001")).toBeInTheDocument();
    expect(screen.getByText("华东精密铸造")).toBeInTheDocument();
  });

  it("loads the platform operations workspace and navigation", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request) => {
        const path = String(input);
        if (path === "/api/auth/config") return jsonResponse({ authenticationRequired: false });
        if (path === "/api/access/users") return jsonResponse(mockUsers);
        if (path === "/api/operations/overview") {
          return jsonResponse({
            failedIntegrationJobs: 1,
            unreadNotifications: 2,
            occupiedResources: 3,
            exhaustedResources: 0,
            pendingWorkflows: 4,
            publishedConfigurations: 5,
            generatedAt: "2026-07-23T08:00:00Z"
          });
        }
        return jsonResponse([]);
      })
    );

    render(
      <MemoryRouter initialEntries={["/administration"]}>
        <App />
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "平台运维" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /平台运维/ })).toHaveClass("active");
    expect(screen.getByText("接口失败")).toBeInTheDocument();
    expect(screen.getByText("登记资源")).toBeInTheDocument();
  });

  it("keeps the operator workspace simple and blocks starting before SOP confirmation", async () => {
    localStorage.setItem("mes.operatorCode", "W001");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request) => {
        const path = String(input);
        if (path === "/api/auth/config") return jsonResponse({ authenticationRequired: false });
        if (path === "/api/access/users") return jsonResponse(mockUsers);
        if (path.startsWith("/api/tasks")) {
          return jsonResponse([
            {
              id: "task-1",
              taskNo: "TASK-001",
              workOrderId: "wo-1",
              workOrderNo: "WO-001",
              orderId: "order-1",
              batchId: "batch-1",
              batchNo: "BATCH-001",
              sequenceNo: 1,
              operationCode: "WAX_INJECTION",
              operationName: "射蜡",
              plannedQuantity: 10,
              goodQuantity: 0,
              scrapQuantity: 0,
              status: "ASSIGNED",
              assignedTo: "W001",
              startedAt: null,
              completedAt: null,
              createdAt: "2026-07-23T08:00:00Z"
            }
          ]);
        }
        if (path === "/api/sops/WAX_INJECTION") {
          return jsonResponse({
            id: "sop-1",
            operationCode: "WAX_INJECTION",
            operationName: "射蜡",
            version: "V1.0",
            safetyNotice: "佩戴防护手套，确认射蜡机急停有效。",
            preparationNote: "核对模具编号并清洁型腔。",
            status: "PUBLISHED",
            updatedBy: "E001",
            updatedAt: "2026-07-23T08:00:00Z",
            steps: [
              { stepNo: 1, title: "核对模具", instruction: "确认模具与任务一致。" },
              { stepNo: 2, title: "设置参数", instruction: "按工艺卡设置温度压力。" }
            ],
            qualityPoints: ["蜡件表面完整", "尺寸符合首件要求"],
            keyParameters: [
              { name: "蜡液温度", value: "58-62°C" },
              { name: "保压时间", value: "12-16 秒" }
            ]
          });
        }
        return jsonResponse([]);
      })
    );

    render(
      <MemoryRouter initialEntries={["/workbench"]}>
        <App />
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "射蜡" })).toBeInTheDocument();
    const desktopNavigation = screen.getByRole("navigation", { name: "主导航" });
    expect(within(desktopNavigation).getAllByRole("link")).toHaveLength(2);
    expect(within(desktopNavigation).getByRole("link", { name: /我的工作台/ })).toBeInTheDocument();
    expect(within(desktopNavigation).getByRole("link", { name: /流程指南/ })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "移动端主导航" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /客户订单/ })).not.toBeInTheDocument();
    expect(await screen.findByText("安全提示")).toBeInTheDocument();
    expect(screen.queryByText("开工前安全确认")).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "开始作业" })).toBeEnabled();
  });

  it("shows a role-aware process guide to an operator", async () => {
    localStorage.setItem("mes.operatorCode", "W001");

    render(
      <MemoryRouter initialEntries={["/guide"]}>
        <App />
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "生产流程指南" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "射蜡操作员", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "一线操作员", level: 3 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "订单到交付完整流程" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "11 道生产工序" })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "进入我的工作台" })[0]).toHaveAttribute(
      "href",
      "/workbench"
    );
  });
});
