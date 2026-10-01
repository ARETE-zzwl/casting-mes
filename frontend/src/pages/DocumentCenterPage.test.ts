import { describe, expect, it } from "vitest";
import { documentFieldsFor, taskDocumentStatusLabel, validDocumentFields } from "./DocumentCenterPage";

describe("document field selection", () => {
  it("keeps only fields allowed by the selected document type", () => {
    expect(validDocumentFields("WORKSHOP_JOB_SHEET", ["TASK_NO", "SOP", "ASSIGNED_TO"])).toEqual([
      "TASK_NO", "ASSIGNED_TO"
    ]);
  });

  it("starts a workshop job sheet with its complete field set", () => {
    expect(documentFieldsFor("WORKSHOP_JOB_SHEET")).toContain("ENGINEERING_PARAMETERS");
    expect(documentFieldsFor("WORKSHOP_JOB_SHEET")).toContain("ASSIGNED_TO");
  });

  it("marks completed tasks as reprint-only", () => {
    expect(taskDocumentStatusLabel("COMPLETED")).toBe("已完成 · 仅补打");
    expect(taskDocumentStatusLabel("IN_PROGRESS")).toBe("当前任务");
  });
});
