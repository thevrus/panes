import { afterEach, describe, expect, it, vi } from "vitest";
import { markDeadCatalogStreams } from "../src/index";
import * as catalog from "../src/catalog";
import { env, isSearch, isVideos, liveVideo, quotaExceededBody, stubFetch } from "./helpers";

const yt = (videoId: string) => ({
  id: `yt:${videoId}`,
  kind: "youtube" as const,
  videoId,
  still: null,
  title: videoId,
  channel: "",
  lat: 0,
  lon: 0,
  height: 0,
  attribution: "YouTube",
  clean: null,
  stillIsFrame: null,
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("markDeadCatalogStreams", () => {
  it("records ended / missing catalog ids with videos.list only (no search)", async () => {
    vi.spyOn(catalog, "loadCatalog").mockReturnValue([yt("a"), yt("b"), yt("c")]);
    const { calls } = stubFetch([
      { match: isVideos, body: { items: [liveVideo("a"), liveVideo("b", { liveStreamingDetails: { actualStartTime: "x", actualEndTime: "y" } })] } },
    ]);
    const e = env();
    expect(await markDeadCatalogStreams(e)).toEqual(["b", "c"]);
    expect(JSON.parse(e.CAMS.store.get("catalog:dead")!)).toEqual(["b", "c"]);
    expect(calls.filter(isSearch)).toHaveLength(0);
    expect(calls.filter(isVideos)).toHaveLength(1);
  });

  it("keeps yesterday's list when the API fails", async () => {
    vi.spyOn(catalog, "loadCatalog").mockReturnValue([yt("a")]);
    stubFetch([{ match: isVideos, status: 403, body: quotaExceededBody }]);
    const e = env();
    e.CAMS.store.set("catalog:dead", JSON.stringify(["old"]));
    expect(await markDeadCatalogStreams(e)).toEqual([]);
    expect(JSON.parse(e.CAMS.store.get("catalog:dead")!)).toEqual(["old"]);
  });

  it("does nothing without a key or an empty catalog", async () => {
    vi.spyOn(catalog, "loadCatalog").mockReturnValue([]);
    const { calls } = stubFetch([]);
    expect(await markDeadCatalogStreams(env())).toEqual([]);
    expect(await markDeadCatalogStreams(env({ YOUTUBE_API_KEY: undefined }))).toEqual([]);
    expect(calls).toHaveLength(0);
  });
});
