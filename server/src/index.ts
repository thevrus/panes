import { Hono } from "hono";
import type { CamsResponse, Env } from "./types";
import { parsePoint, snapToCell } from "./geo";
import { rankCams } from "./rank";
import { liveCatalog, resolveCell, DEAD_KEY } from "./cams";
import { coveredCells, loadCatalog } from "./catalog";
import { listVideos, YouTubeError } from "./youtube";
import { isUsableLive } from "./filter";
import { IPCAM_PATH, ipcamMaster } from "./ipcam";

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...JSON_HEADERS, ...extra } });
}

function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.byteLength !== bb.byteLength) return false;
  let diff = 0;
  for (let i = 0; i < ab.byteLength; i++) diff |= ab[i]! ^ bb[i]!;
  return diff === 0;
}

const app = new Hono<{ Bindings: Env }>();

app.notFound(() => json({ error: "not_found" }, 404));
app.onError(() => json({ error: "internal" }, 500));

for (const path of ["/v1/cams", "/v1/coverage"]) {
  app.all(path, async (c, next) => {
    if (c.req.method !== "GET") return json({ error: "method_not_allowed" }, 405, { allow: "GET" });
    if (c.env.PANES_APP_KEY) {
      const given = c.req.header("x-panes-key") ?? "";
      if (!timingSafeEqual(given, c.env.PANES_APP_KEY)) return json({ error: "unauthorized" }, 401);
    }
    await next();
  });
}

app.get("/v1/cams", async (c) => {
  const env = c.env;
  const ctx = c.executionCtx;
  const url = new URL(c.req.url);
  const point = parsePoint(url.searchParams.get("lat"), url.searchParams.get("lon"));
  if (!point) return json({ error: "bad_request", detail: "lat in [-90,90] and lon in [-180,180] required" }, 400);

  const cell = snapToCell(point);
  const record = await resolveCell(env, cell, ctx);
  // Catalog urls like /v1/ipcam/<alias>.m3u8 are relative to this Worker.
  const cams = rankCams(record.cams, point).map((cam) => (cam.url?.startsWith("/") ? { ...cam, url: url.origin + cam.url } : cam));
  const body: CamsResponse = { cell: record.cell, updatedAt: record.updatedAt, cams };
  return json(body, 200, {
    "cache-control": "public, max-age=3600",
    vary: "X-Panes-Key",
    "x-panes-source": record.source,
  });
});

/** The 0.5 degree cells (south-west corners) whose /v1/cams answer has a live catalog camera, so the app's picker lists only places with something to show. */
app.get("/v1/coverage", async (c) => {
  const cells = coveredCells(await liveCatalog(c.env));
  return json({ cells }, 200, { "cache-control": "public, max-age=3600", vary: "X-Panes-Key" });
});

app.get("/v1/ipcam/:file", async (c) => {
  const alias = c.req.path.match(IPCAM_PATH)?.[1];
  const body = alias ? await ipcamMaster(alias) : null;
  if (!body) return json({ error: "not_found" }, 404);
  return new Response(body, { headers: { "content-type": "application/vnd.apple.mpegurl", "cache-control": "public, max-age=30" } });
});

export default {
  fetch: app.fetch,
  /**
   * Daily: check catalog YouTube entries with one videos.list per 50 ids (10,000/day bucket)
   * and record the ids whose broadcast has ended or became non-embeddable. buildCell drops them.
   * Re-resolving a channel's *new* live videoId needs search.list (the scarce 100/day bucket),
   * so that stays a documented TODO; see README.
   */
  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(markDeadCatalogStreams(env));
  },
} satisfies ExportedHandler<Env>;

export async function markDeadCatalogStreams(env: Env): Promise<string[]> {
  if (!env.YOUTUBE_API_KEY) return [];
  const ids = loadCatalog()
    .filter((c) => c.kind === "youtube" && c.videoId)
    .map((c) => c.videoId!);
  if (!ids.length) return [];
  try {
    const videos = await listVideos(env.YOUTUBE_API_KEY, ids);
    const alive = new Set(videos.filter(isUsableLive).map((v) => v.id));
    const dead = ids.filter((id) => !alive.has(id));
    await env.CAMS.put(DEAD_KEY, JSON.stringify(dead), { expirationTtl: 3 * 86400 });
    return dead;
  } catch (err) {
    // Quota or outage: keep yesterday's list (it expires after 3 days on its own).
    if (!(err instanceof YouTubeError)) throw err;
    return [];
  }
}
