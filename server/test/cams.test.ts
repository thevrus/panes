import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import type { Cam } from "../src/types";
import { buildCell, cellKey, DEAD_KEY, FRESH_MS, resolveCell } from "../src/cams";
import { snapToCell } from "../src/geo";
import { quotaKey, SEARCH_DAILY_CAP, searchCallsToday } from "../src/quota";
import * as catalog from "../src/catalog";
import { ctx, env, isSearch, isVideos, liveVideo, quotaExceededBody, stubFetch } from "./helpers";

const cell = snapToCell({ lat: 26.7, lon: -80.05 });
const NOW = Date.parse("2026-10-18T12:00:00Z");

const catalogCam: Cam = {
  id: "yt:cat1",
  kind: "youtube",
  videoId: "cat1",
  still: null,
  title: "Catalog beach",
  channel: "",
  lat: 26.7,
  lon: -80.05,
  height: 1080,
  attribution: "YouTube",
  clean: true,
  stillIsFrame: true,
};

function useCatalog(cams: catalog.CatalogCam[] = [catalogCam]) {
  vi.spyOn(catalog, "catalogNear").mockReturnValue(cams);
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("buildCell", () => {
  it("merges catalog with filtered YouTube results using one search and one videos call", async () => {
    useCatalog();
    const { calls } = stubFetch([
      { match: isSearch, body: { items: [{ id: { videoId: "live1" } }, { id: { videoId: "ended" } }, { id: { videoId: "noembed" } }, { id: { videoId: "cat1" } }] } },
      {
        match: isVideos,
        body: {
          items: [
            liveVideo("live1", { recordingDetails: { location: { latitude: 26.8, longitude: -80.1 } } }),
            liveVideo("ended", { liveStreamingDetails: { actualStartTime: "x", actualEndTime: "y" } }),
            liveVideo("noembed", { status: { embeddable: false } }),
          ],
        },
      },
    ]);
    const e = env();
    const { record, ytError } = await buildCell(e, cell, NOW);
    expect(ytError).toBeNull();
    expect(record.source).toBe("merged");
    expect(record.cams.map((c) => c.id)).toEqual(["yt:cat1", "yt:live1"]);
    const yt = record.cams[1]!;
    expect(yt).toMatchObject({ kind: "youtube", videoId: "live1", lat: 26.8, lon: -80.1, height: 1080, clean: null, stillIsFrame: null, still: "https://i.ytimg.com/vi/live1/maxresdefault_live.jpg" });
    expect(calls.filter(isSearch)).toHaveLength(1);
    expect(calls.filter(isVideos)).toHaveLength(1);
    // catalog id is not re-fetched
    expect(calls.find(isVideos)!.searchParams.get("id")).toBe("live1,ended,noembed");
    expect(calls.find(isSearch)!.searchParams.get("key")).toBe("test-key");
    expect(calls.find(isSearch)!.searchParams.get("locationRadius")).toBe("50km");
    expect(await searchCallsToday(e.CAMS, NOW)).toBe(1);
  });

  it("uses the cell center when recordingDetails is missing", async () => {
    useCatalog([]);
    stubFetch([
      { match: isSearch, body: { items: [{ id: { videoId: "v" } }] } },
      { match: isVideos, body: { items: [liveVideo("v")] } },
    ]);
    const { record } = await buildCell(env(), cell, NOW);
    expect(record.cams[0]).toMatchObject({ lat: cell.center.lat, lon: cell.center.lon });
  });

  it("falls back to catalog-only on quotaExceeded and pins the counter", async () => {
    useCatalog();
    stubFetch([{ match: isSearch, status: 403, body: quotaExceededBody }]);
    const e = env();
    const { record, ytError } = await buildCell(e, cell, NOW);
    expect(record.source).toBe("catalog-only");
    expect(record.cams.map((c) => c.id)).toEqual(["yt:cat1"]);
    expect(ytError).toBeTruthy();
    expect(await searchCallsToday(e.CAMS, NOW)).toBeGreaterThanOrEqual(SEARCH_DAILY_CAP);
  });

  it("stops calling search at the daily cap", async () => {
    useCatalog();
    const { calls } = stubFetch([{ match: isSearch, body: { items: [] } }]);
    const e = env();
    e.CAMS.store.set(quotaKey(NOW), String(SEARCH_DAILY_CAP));
    const { record } = await buildCell(e, cell, NOW);
    expect(calls).toHaveLength(0);
    expect(record.source).toBe("catalog-only");
  });

  it("counts up to the cap then stops", async () => {
    useCatalog([]);
    const { calls } = stubFetch([{ match: isSearch, body: { items: [] } }]);
    const e = env();
    e.CAMS.store.set(quotaKey(NOW), String(SEARCH_DAILY_CAP - 2));
    await buildCell(e, snapToCell({ lat: 10, lon: 10 }), NOW);
    await buildCell(e, snapToCell({ lat: 11, lon: 11 }), NOW);
    await buildCell(e, snapToCell({ lat: 12, lon: 12 }), NOW);
    expect(calls).toHaveLength(2);
    expect(await searchCallsToday(e.CAMS, NOW)).toBe(SEARCH_DAILY_CAP);
  });

  it("is catalog-only without an API key and never touches the network", async () => {
    useCatalog();
    const { calls } = stubFetch([]);
    const { record } = await buildCell(env({ YOUTUBE_API_KEY: undefined }), cell, NOW);
    expect(record.source).toBe("catalog-only");
    expect(calls).toHaveLength(0);
  });

  it("drops catalog youtube ids the cron marked dead", async () => {
    useCatalog();
    stubFetch([]);
    const e = env();
    e.CAMS.store.set("catalog:dead", JSON.stringify(["cat1"]));
    e.CAMS.store.set(quotaKey(NOW), String(SEARCH_DAILY_CAP)); // no search, catalog only
    const { record } = await buildCell(e, cell, NOW);
    expect(record.cams).toEqual([]);
  });

  it("keyless: serves the catalog without touching KV", async () => {
    useCatalog();
    stubFetch([]);
    const e = env({ YOUTUBE_API_KEY: undefined });
    const record = await resolveCell(e, cell, { waitUntil: () => {} }, NOW);
    expect(record.cams.length).toBeGreaterThan(0);
    expect(e.CAMS.store.size).toBe(0);
  });
});

describe("resolveCell", () => {
  it("serves a fresh cached cell without touching YouTube", async () => {
    useCatalog();
    const { calls } = stubFetch([]);
    const e = env();
    const cached = { cell: cell.key, updatedAt: NOW - 1000, cams: [catalogCam], source: "merged" as const };
    e.CAMS.store.set(cellKey(cell), JSON.stringify(cached));
    const out = await resolveCell(e, cell, ctx(), NOW);
    expect(out).toEqual(cached);
    expect(calls).toHaveLength(0);
  });

  it("serves stale immediately and refreshes in the background", async () => {
    useCatalog();
    stubFetch([
      { match: isSearch, body: { items: [{ id: { videoId: "new" } }] } },
      { match: isVideos, body: { items: [liveVideo("new")] } },
    ]);
    const e = env();
    const stale = { cell: cell.key, updatedAt: NOW - FRESH_MS - 1, cams: [catalogCam], source: "merged" as const };
    e.CAMS.store.set(cellKey(cell), JSON.stringify(stale));
    const c = ctx();
    const out = await resolveCell(e, cell, c, NOW);
    expect(out).toEqual(stale);
    await c.flush();
    const after = JSON.parse(e.CAMS.store.get(cellKey(cell))!);
    expect(after.updatedAt).toBe(NOW);
    expect(after.cams.map((x: { id: string }) => x.id)).toEqual(["yt:cat1", "yt:new"]);
  });

  it("keeps the last merged list when YouTube fails during a refresh", async () => {
    useCatalog([]);
    stubFetch([{ match: isSearch, status: 503, body: { error: { message: "backend" } } }]);
    const e = env();
    const stale = { cell: cell.key, updatedAt: NOW - FRESH_MS - 1, cams: [catalogCam], source: "merged" as const };
    e.CAMS.store.set(cellKey(cell), JSON.stringify(stale));
    const c = ctx();
    await resolveCell(e, cell, c, NOW);
    await c.flush();
    const after = JSON.parse(e.CAMS.store.get(cellKey(cell))!);
    expect(after.cams.map((x: { id: string }) => x.id)).toEqual(["yt:cat1"]);
    expect(after.source).toBe("merged");
  });
});

describe("fetch handler", () => {
  const req = (q: string, headers: Record<string, string> = {}, method = "GET") =>
    new Request(`https://panes.example/v1/cams${q}`, { method, headers });

  it("400s on bad input", async () => {
    const res = await worker.fetch(req("?lat=99&lon=0"), env(), ctx() as unknown as ExecutionContext);
    expect(res.status).toBe(400);
    expect((await res.json()) as { error: string }).toMatchObject({ error: "bad_request" });
  });

  it("404s off-path and 405s non-GET", async () => {
    expect((await worker.fetch(new Request("https://panes.example/"), env(), ctx() as unknown as ExecutionContext)).status).toBe(404);
    expect((await worker.fetch(req("?lat=1&lon=1", {}, "POST"), env(), ctx() as unknown as ExecutionContext)).status).toBe(405);
  });

  it("returns the contract shape, ranked, with cache headers; never 5xx when YouTube is out of quota", async () => {
    useCatalog([catalogCam, { ...catalogCam, id: "hls:x", kind: "hls", videoId: undefined, url: "https://h/x.m3u8", height: 2160, clean: false, lat: 26.71 }]);
    stubFetch([{ match: isSearch, status: 403, body: quotaExceededBody }]);
    const res = await worker.fetch(req("?lat=26.7&lon=-80.05"), env(), ctx() as unknown as ExecutionContext);
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("public, max-age=3600");
    expect(res.headers.get("content-type")).toMatch(/application\/json/);
    const body = (await res.json()) as { cell: string; updatedAt: number; cams: Array<Record<string, unknown>> };
    expect(body.cell).toBe("26.5,-80.5");
    expect(typeof body.updatedAt).toBe("number");
    // clean:true 1080 beats clean:false 4K
    expect(body.cams.map((c) => c.id)).toEqual(["yt:cat1", "hls:x"]);
    expect(Object.keys(body.cams[0]!).sort()).toEqual(
      ["attribution", "channel", "clean", "height", "id", "kind", "lat", "lon", "still", "stillIsFrame", "title", "videoId"].sort(),
    );
    expect(body.cams[1]).toMatchObject({ kind: "hls", url: "https://h/x.m3u8" });
    expect(body.cams[1]).not.toHaveProperty("videoId");
    expect(body.cams[0]).not.toHaveProperty("source");
    expect(body.cams[0]).not.toHaveProperty("channelId");
  });

  it("sends channelId only for followed cams, and keeps them past the dead list", async () => {
    useCatalog([{ ...catalogCam, channelId: "UC1", follow: true }]);
    stubFetch([{ match: isSearch, status: 403, body: quotaExceededBody }]);
    const res = await worker.fetch(req("?lat=26.7&lon=-80.05"), env(), ctx() as unknown as ExecutionContext);
    const body = (await res.json()) as { cams: Array<Record<string, unknown>> };
    expect(body.cams[0]).toMatchObject({ id: "yt:cat1", channelId: "UC1" });
    expect(body.cams[0]).not.toHaveProperty("follow");
  });

  it("enforces X-Panes-Key only when the secret is set", async () => {
    useCatalog([]);
    stubFetch([{ match: isSearch, body: { items: [] } }]);
    const locked = env({ PANES_APP_KEY: "s3cret" });
    expect((await worker.fetch(req("?lat=1&lon=1"), locked, ctx() as unknown as ExecutionContext)).status).toBe(401);
    expect((await worker.fetch(req("?lat=1&lon=1", { "x-panes-key": "wrong" }), locked, ctx() as unknown as ExecutionContext)).status).toBe(401);
    expect((await worker.fetch(req("?lat=1&lon=1", { "X-Panes-Key": "s3cret" }), locked, ctx() as unknown as ExecutionContext)).status).toBe(200);
    expect((await worker.fetch(req("?lat=1&lon=1"), env(), ctx() as unknown as ExecutionContext)).status).toBe(200);
  });

  it("lists the cells catalog cams reach, without the cams the cron marked dead", async () => {
    vi.spyOn(catalog, "loadCatalog").mockReturnValue([catalogCam, { ...catalogCam, id: "yt:gone", videoId: "gone", lat: 47.6, lon: -122.3 }]);
    const e = env();
    await e.CAMS.put(DEAD_KEY, JSON.stringify(["gone"]));
    const get = (init?: RequestInit) => worker.fetch(new Request("https://panes.example/v1/coverage", init), e, ctx() as unknown as ExecutionContext);
    const res = await get();
    expect(res.status).toBe(200);
    const { cells } = (await res.json()) as { cells: Array<[number, number]> };
    expect(cells).toContainEqual([26.5, -80.5]);
    expect(cells.every(([lat]) => lat < 30)).toBe(true);
    expect((await get({ method: "POST" })).status).toBe(405);
  });

});
