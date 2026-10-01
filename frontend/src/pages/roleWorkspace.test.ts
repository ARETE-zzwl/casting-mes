import { describe, expect, it } from "vitest";
import { roleWorkspaceFor } from "./roleWorkspace";
import type { AccessUser } from "../types";

const cuttingOperator: AccessUser = {
  employeeCode: "CT01",
  name: "分割员工01",
  unitCode: "WAX_WORKSHOP",
  unitName: "中温蜡车间",
  primaryRole: "CUTTING_OPERATOR",
  roles: ["CUTTING_OPERATOR"],
  permissions: ["TASK_EXECUTE", "MOBILE_REPORT", "WORKBENCH_VIEW"],
  active: true
};

const finishedGoodsKeeper: AccessUser = {
  employeeCode: "G001",
  name: "成品仓管员",
  unitCode: "DEMO_FACTORY",
  unitName: "精铸一厂",
  primaryRole: "FINISHED_GOODS_KEEPER",
  roles: ["FINISHED_GOODS_KEEPER"],
  permissions: ["TASK_EXECUTE", "FINISHED_GOODS_WAREHOUSE_MANAGE"],
  active: true
};

const moldKeeper: AccessUser = {
  employeeCode: "M001",
  name: "模具仓管理员",
  unitCode: "DEMO_FACTORY",
  unitName: "精铸一厂",
  primaryRole: "MOLD_KEEPER",
  roles: ["MOLD_KEEPER"],
  permissions: ["MOLD_WAREHOUSE_MANAGE", "QR_BIND"],
  active: true
};

const systemAdmin: AccessUser = {
  employeeCode: "A001",
  name: "系统管理员",
  unitCode: "DEMO_COMPANY",
  unitName: "集团管理中心",
  primaryRole: "SYSTEM_ADMIN",
  roles: ["SYSTEM_ADMIN"],
  permissions: ["ACCESS_MANAGE", "QR_BIND"],
  active: true
};

describe("roleWorkspaceFor", () => {
  it("gives a cutting operator the task-execution workbench instead of the collaboration fallback", () => {
    const workspace = roleWorkspaceFor(cuttingOperator);

    expect(workspace.kind).toBe("operator");
    expect(workspace.roleLabel).toBe("分割操作工");
    expect(workspace.title).toBe("分割工作台");
  });

  it("gives the finished-goods keeper a dedicated entry for assigned final-count tasks", () => {
    const workspace = roleWorkspaceFor(finishedGoodsKeeper);

    expect(workspace.actions).toEqual(expect.arrayContaining([
      expect.objectContaining({ to: "/operator-tasks", label: "成品清点任务" })
    ]));
  });

  it("gives mold keepers and administrators a direct mold QR binding entry", () => {
    expect(roleWorkspaceFor(moldKeeper).actions).toEqual(expect.arrayContaining([
      expect.objectContaining({ to: "/qr-bind", permission: "QR_BIND" })
    ]));
    expect(roleWorkspaceFor(systemAdmin).actions).toEqual(expect.arrayContaining([
      expect.objectContaining({ to: "/qr-bind", permission: "QR_BIND" })
    ]));
  });
});
