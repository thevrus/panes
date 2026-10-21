#!/usr/bin/env bun
// Bundles every panes/server/catalog-*.json (owned by other agents) into
// src/catalog.generated.json as {builtAt, sources: {<file stem>: entries}}, so the Worker
// ships the catalog as a module import. Malformed files become empty arrays, so the Worker always builds.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const server = resolve(here, "..");
const out = resolve(server, "src", "catalog.generated.json");

function load(name) {
  try {
    return JSON.parse(readFileSync(resolve(server, name), "utf8"));
  } catch (e) {
    console.warn(`[catalog] ${name} unreadable (${e.message}); using []`);
    return [];
  }
}

const files = readdirSync(server)
  .filter((f) => /^catalog-.+\.json$/.test(f))
  .sort();
const sources = {};
for (const f of files) sources[f.slice("catalog-".length, -".json".length)] = load(f);

writeFileSync(out, JSON.stringify({ builtAt: new Date().toISOString(), sources }));
const count = (x) => (Array.isArray(x) ? x.length : Array.isArray(x?.cams) ? x.cams.length : Array.isArray(x?.entries) ? x.entries.length : 0);
for (const f of files) console.log(`[catalog] loaded ${f}: ${count(sources[f.slice(8, -5)])} entries`);
console.log(`[catalog] wrote ${out}: ${files.length} source(s)`);
