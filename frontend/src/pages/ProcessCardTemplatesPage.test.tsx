import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ProcessCardTemplatesPage } from "./ProcessCardTemplatesPage";

const mocks = vi.hoisted(() => ({
  listTemplates: vi.fn(),
  listProducts: vi.fn(),
  createTemplate: vi.fn(),
  publishTemplate: vi.fn()
}));

vi.mock("../api", () => ({
  api: {
    processCardTemplates: { list: mocks.listTemplates, create: mocks.createTemplate, publish: mocks.publishTemplate },
    products: { list: mocks.listProducts }
  }
}));

describe("ProcessCardTemplatesPage", () => {
  beforeEach(() => {
    mocks.listTemplates.mockResolvedValue([]);
    mocks.listProducts.mockResolvedValue([{
      id: "product-1", code: "PRD-001", name: "阀体", routeType: "MID_TEMP_WAX", active: true
    }]);
  });

  it("uses product selection and per-operation inputs instead of JSON editing", async () => {
    render(<ProcessCardTemplatesPage operatorCode="E001" />);

    const picker = await screen.findByLabelText(/^产品/);
    fireEvent.change(picker, { target: { value: "PRD-001 · 阀体 · MID_TEMP_WAX" } });

    expect(await screen.findByText("建立版本", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "新建首版" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "AI 生成工艺建议" })).toBeEnabled();
    expect(screen.getAllByRole("textbox").filter((element) => element.getAttribute("name")?.startsWith("operation-")).length).toBe(8);
    expect(screen.queryByText(/JSON/)).not.toBeInTheDocument();
  });
});
