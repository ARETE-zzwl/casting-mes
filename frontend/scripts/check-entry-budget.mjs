import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";

const dist = new URL("../dist/", import.meta.url);
const manifest = JSON.parse(await readFile(new URL(".vite/manifest.json", dist), "utf8"));
const entry = Object.keys(manifest).find((key) => manifest[key].isEntry);
assert.ok(entry, "Missing Vite entry manifest");
const visited = new Set();
function collect(key) {
  if (visited.has(key)) return;
  visited.add(key);
  for (const imported of manifest[key].imports ?? []) collect(imported);
}
collect(entry);
let raw = 0;
let gzip = 0;
for (const key of visited) {
  const bytes = await readFile(new URL(manifest[key].file, dist));
  raw += bytes.length;
  gzip += gzipSync(bytes).length;
}
console.log(`Initial JavaScript including static imports: ${raw} bytes; gzip ${gzip} bytes; ${visited.size} chunks`);
assert.ok(raw <= 450_000, `Initial JS ${raw} exceeds 450000-byte budget`);
assert.ok(gzip <= 140_000, `Initial gzip JS ${gzip} exceeds 140000-byte budget`);
