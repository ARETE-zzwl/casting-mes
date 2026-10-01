import { describe, expect, it } from "vitest";
import { operatingRuleSummaries, productionLineGuides } from "./processGuideData";

describe("生产线流程指南", () => {
  it("分别保留三条生产线且不混入错误的首道工序", () => {
    const mid = productionLineGuides.find((line) => line.code === "MID_TEMP_WAX");
    const low = productionLineGuides.find((line) => line.code === "LOW_TEMP_WAX");
    const sand = productionLineGuides.find((line) => line.code === "SAND_OUTSOURCE");

    expect(mid?.steps.map((step) => step.operation)).toContain("射蜡");
    expect(low?.steps.map((step) => step.operation)).toContain("人工制壳");
    expect(low?.summary).toContain("不产生射蜡修蜡组树计件工资");
    expect(sand?.steps.map((step) => step.operation)).toEqual(["订单与工程确认", "外协发出", "外协进度", "来料检验与交付"]);
  });

  it("每条说明均给出现场可用功能，且关键规则可读", () => {
    expect(productionLineGuides.every((line) => line.steps.every((step) => step.owner && step.tool && step.action))).toBe(true);
    expect(operatingRuleSummaries.map((rule) => rule.title)).toEqual(expect.arrayContaining(["顺序与拆批", "异常不堵产", "电子与纸质并行"]));
  });
});
