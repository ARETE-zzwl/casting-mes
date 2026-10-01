import QRCode from "qrcode";
import type { AssetQrLabel } from "../types";

export const MOLD_LABEL_DXF_MAX_LABELS = 6;

const LABEL_WIDTH = 50;
const LABEL_HEIGHT = 30;
const QR_BOX = 24;
const QR_QUIET_MODULES = 4;
const LABEL_PADDING = 2;
const COLUMN_PITCH = 54;
const ROW_PITCH = 34;
const COLUMNS = 2;
const PNG_DPI = 300;
const PIXELS_PER_MILLIMETER = PNG_DPI / 25.4;

export function createMoldLabelDxf(labels: AssetQrLabel[]) {
	assertMoldLabelJob(labels);
	const entities = labels.flatMap((label, index) => labelEntities(label, index));
	return [
		"0", "SECTION", "2", "HEADER", "9", "$ACADVER", "1", "AC1009", "0", "ENDSEC",
		"0", "SECTION", "2", "TABLES", ...layerTable(), "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES",
    "999", "Mold QR label: 50 x 30 mm. Apply fill or hatch to the QR layer before marking.",
    ...entities,
    "0", "ENDSEC", "0", "EOF", ""
	].join("\n");
}

export async function createMoldLabelPng(labels: AssetQrLabel[]) {
	assertMoldLabelJob(labels);
	const rows = Math.ceil(labels.length / COLUMNS);
	const widthMm = labels.length === 1 ? LABEL_WIDTH : LABEL_WIDTH * COLUMNS + (COLUMN_PITCH - LABEL_WIDTH);
	const heightMm = LABEL_HEIGHT * rows + (rows - 1) * (ROW_PITCH - LABEL_HEIGHT);
	const canvas = document.createElement("canvas");
	canvas.width = Math.round(widthMm * PIXELS_PER_MILLIMETER);
	canvas.height = Math.round(heightMm * PIXELS_PER_MILLIMETER);
	const context = canvas.getContext("2d");
	if (!context) throw new Error("当前浏览器无法生成 PNG 标牌");
	context.imageSmoothingEnabled = false;
	context.fillStyle = "#fff";
	context.fillRect(0, 0, canvas.width, canvas.height);

	labels.forEach((label, index) => drawPngLabel(context, label, index));
	return withPngResolution(await canvasToBlob(canvas));
}

function labelEntities(label: AssetQrLabel, index: number) {
  const column = index % COLUMNS;
  const row = Math.floor(index / COLUMNS);
  const originX = column * COLUMN_PITCH;
  const originY = -row * ROW_PITCH;
  const qr = QRCode.create(`MES:ASSET_QR:${label.qrToken}`, { errorCorrectionLevel: "M" });
  const moduleSize = QR_BOX / (qr.modules.size + QR_QUIET_MODULES * 2);
  const qrX = originX + LABEL_PADDING;
  const qrY = originY + (LABEL_HEIGHT - QR_BOX) / 2;
  const qrModules: string[] = [];

  for (let rowIndex = 0; rowIndex < qr.modules.size; rowIndex += 1) {
    for (let columnIndex = 0; columnIndex < qr.modules.size; columnIndex += 1) {
      if (qr.modules.get(rowIndex, columnIndex)) {
        const x = qrX + (QR_QUIET_MODULES + columnIndex) * moduleSize;
        const y = qrY + (QR_QUIET_MODULES + qr.modules.size - rowIndex - 1) * moduleSize;
        qrModules.push(closedPolyline("QR", rectangle(x, y, moduleSize, moduleSize)));
      }
    }
  }

  return [
    closedPolyline("FRAME", rectangle(originX, originY, LABEL_WIDTH, LABEL_HEIGHT)),
    ...qrModules,
    text("TEXT", "MOLD QR", originX + 28, originY + 22, 2.3),
    text("TEXT", label.labelNo, originX + 28, originY + 17.8, 1.55),
    text("TEXT", "50 x 30 mm", originX + 28, originY + 13.8, 1.55)
  ];
}

function layerTable() {
	return [
		"0", "TABLE", "2", "LAYER", "70", "4",
		...layer("0", 7), ...layer("FRAME", 8), ...layer("QR", 7), ...layer("TEXT", 7),
		"0", "ENDTAB"
	];
}

function layer(name: string, color: number) {
  return ["0", "LAYER", "2", name, "70", "0", "62", String(color), "6", "CONTINUOUS"];
}

function rectangle(x: number, y: number, width: number, height: number): Array<[number, number]> {
  return [[x, y], [x + width, y], [x + width, y + height], [x, y + height]];
}

function closedPolyline(layerName: string, points: Array<[number, number]>) {
	return [
		"0", "POLYLINE", "8", layerName, "66", "1", "70", "1", "10", "0", "20", "0", "30", "0",
		...points.flatMap(([x, y]) => ["0", "VERTEX", "8", layerName, "10", coordinate(x), "20", coordinate(y), "30", "0"]),
		"0", "SEQEND", "8", layerName
	].join("\n");
}

function text(layerName: string, value: string, x: number, y: number, height: number) {
	return [
		"0", "TEXT", "8", layerName,
    "10", coordinate(x), "20", coordinate(y), "30", "0", "40", coordinate(height),
    "1", dxfText(value), "7", "STANDARD", "72", "0", "73", "0"
  ].join("\n");
}

function coordinate(value: number) {
  return value.toFixed(4);
}

function dxfText(value: string) {
	return value.replace(/[\r\n]/g, " ").replace(/[^\x20-\x7e]/g, "?");
}

function assertMoldLabelJob(labels: AssetQrLabel[]) {
	if (labels.length === 0) throw new Error("请至少选择一张模具标签");
	if (labels.length > MOLD_LABEL_DXF_MAX_LABELS) {
		throw new Error(`DXF 一次最多导出 ${MOLD_LABEL_DXF_MAX_LABELS} 张，适配 110 × 110 mm 场镜`);
	}
}

function drawPngLabel(context: CanvasRenderingContext2D, label: AssetQrLabel, index: number) {
	const column = index % COLUMNS;
	const row = Math.floor(index / COLUMNS);
	const originX = column * COLUMN_PITCH * PIXELS_PER_MILLIMETER;
	const originY = row * ROW_PITCH * PIXELS_PER_MILLIMETER;
	const scale = PIXELS_PER_MILLIMETER;
	context.strokeStyle = "#111";
	context.lineWidth = 0.2 * scale;
	context.strokeRect(originX + context.lineWidth / 2, originY + context.lineWidth / 2, LABEL_WIDTH * scale - context.lineWidth, LABEL_HEIGHT * scale - context.lineWidth);

	const qr = QRCode.create(`MES:ASSET_QR:${label.qrToken}`, { errorCorrectionLevel: "M" });
	const moduleSize = QR_BOX * scale / (qr.modules.size + QR_QUIET_MODULES * 2);
	const qrX = originX + LABEL_PADDING * scale;
	const qrY = originY + (LABEL_HEIGHT - QR_BOX) / 2 * scale;
	context.fillStyle = "#000";
	for (let rowIndex = 0; rowIndex < qr.modules.size; rowIndex += 1) {
		for (let columnIndex = 0; columnIndex < qr.modules.size; columnIndex += 1) {
			if (qr.modules.get(rowIndex, columnIndex)) {
				context.fillRect(qrX + (QR_QUIET_MODULES + columnIndex) * moduleSize, qrY + (QR_QUIET_MODULES + rowIndex) * moduleSize, moduleSize, moduleSize);
			}
		}
	}

	context.fillStyle = "#111";
	context.font = `700 ${2.3 * scale}px Arial`;
	context.fillText("MOLD QR", originX + 28 * scale, originY + 10 * scale);
	context.font = `${1.55 * scale}px Arial`;
	context.fillText(label.labelNo, originX + 28 * scale, originY + 15 * scale);
	context.fillText("50 x 30 mm / 300 DPI", originX + 28 * scale, originY + 20 * scale);
}

function canvasToBlob(canvas: HTMLCanvasElement) {
	return new Promise<Blob>((resolve, reject) => {
		canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("PNG 标牌生成失败")), "image/png");
	});
}

async function withPngResolution(blob: Blob) {
	const bytes = new Uint8Array(await blob.arrayBuffer());
	const ihdrLength = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(8);
	const insertAt = 8 + 12 + ihdrLength;
	const density = Math.round(PNG_DPI / 0.0254);
	const data = new Uint8Array(9);
	const view = new DataView(data.buffer);
	view.setUint32(0, density);
	view.setUint32(4, density);
	data[8] = 1;
	const physicalPixels = pngChunk("pHYs", data);
	return new Blob([bytes.slice(0, insertAt), physicalPixels, bytes.slice(insertAt)], { type: "image/png" });
}

function pngChunk(type: string, data: Uint8Array) {
	const bytes = new Uint8Array(data.length + 12);
	const view = new DataView(bytes.buffer);
	view.setUint32(0, data.length);
	bytes.set(new TextEncoder().encode(type), 4);
	bytes.set(data, 8);
	view.setUint32(data.length + 8, crc32(bytes.slice(4, data.length + 8)));
	return bytes;
}

function crc32(bytes: Uint8Array) {
	let value = 0xffffffff;
	for (const byte of bytes) {
		value ^= byte;
		for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
	}
	return (value ^ 0xffffffff) >>> 0;
}
