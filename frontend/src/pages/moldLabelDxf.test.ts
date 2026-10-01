import { describe, expect, it } from "vitest";
import { createMoldLabelDxf, MOLD_LABEL_DXF_MAX_LABELS } from "./moldLabelDxf";

describe("createMoldLabelDxf", () => {
  const label = {
    id: "label-1",
    labelNo: "QRL-20260821-1234ABCD",
    qrToken: "1234567890ABCDEF1234567890ABCDEF",
    intendedAssetType: "MOLD" as const,
    assetId: null,
    assetCode: null,
    assetName: null,
    status: "UNBOUND" as const,
    printCount: 0,
    lastPrintedBy: null,
    lastPrintedAt: null,
    boundBy: null,
    boundAt: null,
    createdBy: "A001",
    createdAt: "2026-08-21T00:00:00Z"
  };

  it("creates a 50 by 30 millimeter DXF job containing vector QR modules and readable label text", () => {
    const dxf = createMoldLabelDxf([label]);

    expect(dxf).toContain("AC1009");
    expect(dxf).toContain("POLYLINE");
    expect(dxf).toContain("VERTEX");
    expect(dxf).not.toContain("LWPOLYLINE");
    expect(dxf).toContain("QR");
    expect(dxf).toContain("QRL-20260821-1234ABCD");
    expect(dxf).toContain("50.0000");
    expect(dxf).toContain("30.0000");
    expect(dxf).toContain("EOF");
  });

  it("limits one laser job to the six labels that fit a 110 by 110 millimeter field", () => {
    expect(() => createMoldLabelDxf(Array.from({ length: MOLD_LABEL_DXF_MAX_LABELS + 1 }, () => label))).toThrow("一次最多导出 6 张");
  });
});
