import { describe, expect, it } from "vitest";
import { buildCatalog, catalogNear, coveredCells, toCam, unwrap } from "../src/catalog";
import { distanceKm, snapToCell } from "../src/geo";

const hls = {
  kind: "hls" as const,
  title: "Jupiter Inlet",
  url: "https://example.com/jupiter.m3u8",
  lat: 26.94,
  lon: -80.07,
  height: 1080,
  clean: true,
  stillIsFrame: true,
};

describe("toCam", () => {
  it("drops an hls entry without licensed:true (hotlinking rule)", () => {
    expect(toCam(hls, "x")).toBeNull();
    expect(toCam({ ...hls, licensed: false }, "x")).toBeNull();
  });
  it("keeps a licensed hls entry and passes clean/stillIsFrame through", () => {
    const cam = toCam({ ...hls, licensed: true }, "video-monitoring.com");
    expect(cam).toMatchObject({ id: "hls:jupiter-inlet", kind: "hls", url: hls.url, height: 1080, clean: true, stillIsFrame: true, attribution: "video-monitoring.com" });
  });
  it("keeps youtube entries without a licensed flag and defaults the still", () => {
    const cam = toCam({ kind: "youtube", videoId: "abc123", channelId: "UC1", title: "Beach", lat: 1, lng: 2 }, "YouTube");
    expect(cam).toMatchObject({ id: "yt:abc123", videoId: "abc123", channelId: "UC1", lon: 2, still: "https://i.ytimg.com/vi/abc123/maxresdefault_live.jpg", clean: null, stillIsFrame: null, height: 0 });
  });
  it("drops live:false, embeddable:false, missing coords, missing title", () => {
    expect(toCam({ ...hls, licensed: true, live: false }, "x")).toBeNull();
    expect(toCam({ kind: "youtube", videoId: "a", title: "t", lat: 1, lon: 1, embeddable: false }, "x")).toBeNull();
    expect(toCam({ kind: "youtube", videoId: "a", title: "t" }, "x")).toBeNull();
    expect(toCam({ kind: "youtube", videoId: "a", title: " ", lat: 1, lon: 1 }, "x")).toBeNull();
  });
});

describe("buildCatalog / catalogNear", () => {
  it("builds from an empty or missing bundle", () => {
    expect(buildCatalog({})).toEqual([]);
    expect(buildCatalog({ sources: { erdman: null, youtube: undefined } })).toEqual([]);
    expect(unwrap("garbage")).toEqual([]);
  });
  it("merges both files, dedupes by id, and filters by 120 km", () => {
    const cat = buildCatalog({
      sources: {
        erdman: [{ ...hls, licensed: true }, { ...hls, licensed: true }],
        youtube: { cams: [{ kind: "youtube", videoId: "far", title: "Key West", lat: 24.55, lon: -81.78 }] },
      },
    });
    expect(cat.map((c) => c.id)).toEqual(["hls:jupiter-inlet", "yt:far"]);
    expect(catalogNear({ lat: 26.75, lon: -80.25 }, cat).map((c) => c.id)).toEqual(["hls:jupiter-inlet"]);
  });
  it("merges two sources with dedupe by id, drops unlicensed hls, applies per-source attribution", () => {
    const yt = { kind: "youtube" as const, videoId: "dup", title: "Beach", lat: 26.9, lon: -80.1 };
    const cat = buildCatalog({
      sources: {
        erdman: [{ ...hls, licensed: true }, { ...hls, title: "Unlicensed", url: "https://example.com/u.m3u8" }, yt],
        wpb: [yt, { ...yt, videoId: "other" }],
      },
    });
    expect(cat.map((c) => c.id)).toEqual(["hls:jupiter-inlet", "yt:dup", "yt:other"]);
    expect(cat.map((c) => c.attribution)).toEqual(["video-monitoring.com", "video-monitoring.com", "YouTube"]);
  });
});

describe("coveredCells", () => {
  it("matches a brute-force scan: exactly the cells whose center is within reach of a camera", () => {
    const cams = [{ lat: 47.6, lon: -122.3 }, { lat: -33.86, lon: 151.21 }, { lat: 64.1, lon: -21.9 }];
    const brute = new Set<string>();
    for (let lat = -90; lat < 90; lat += 0.5)
      for (let lon = -180; lon < 180; lon += 0.5) {
        const cell = snapToCell({ lat: lat + 0.25, lon: lon + 0.25 });
        if (cams.some((c) => distanceKm(cell.center, c) <= 120)) brute.add(cell.key);
      }
    const got = coveredCells(cams).map(([lat, lon]) => snapToCell({ lat: lat + 0.25, lon: lon + 0.25 }).key);
    expect(new Set(got)).toEqual(brute);
    expect(got.length).toBe(brute.size);
  });
});
