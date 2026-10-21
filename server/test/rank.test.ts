import { describe, expect, it } from "vitest";
import { cleanTier, qualityTier, rankCams } from "../src/rank";
import type { Cam } from "../src/types";

const cam = (id: string, lat: number, lon: number, height: number, clean: boolean | null = null): Cam => ({
  id,
  kind: "hls",
  url: `https://x/${id}.m3u8`,
  still: null,
  title: id,
  channel: "",
  lat,
  lon,
  height,
  attribution: "",
  clean,
  stillIsFrame: null,
});

const here = { lat: 26.7, lon: -80.05 };

describe("qualityTier", () => {
  it("buckets heights", () => {
    expect([2160, 1440, 1080, 720, 480, 0].map(qualityTier)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(qualityTier(1088)).toBe(2);
  });
});

describe("cleanTier", () => {
  it("orders true > null > false", () => {
    expect(cleanTier(true)).toBeLessThan(cleanTier(null));
    expect(cleanTier(null)).toBeLessThan(cleanTier(false));
    expect(cleanTier(undefined)).toBe(cleanTier(null));
  });
});

describe("rankCams", () => {
  it("sorts by quality tier first, then distance", () => {
    const near480 = cam("near480", 26.7, -80.05, 480);
    const far1080 = cam("far1080", 27.0, -80.05, 1080);
    const nearer1080 = cam("nearer1080", 26.8, -80.05, 1080);
    const out = rankCams([near480, far1080, nearer1080], here).map((c) => c.id);
    expect(out).toEqual(["nearer1080", "far1080", "near480"]);
  });
  it("treats 4K above 1080 above unknown", () => {
    const out = rankCams([cam("u", 26.7, -80.05, 0), cam("hd", 26.7, -80.05, 1080), cam("uhd", 27.5, -80.05, 2160)], here);
    expect(out.map((c) => c.id)).toEqual(["uhd", "hd", "u"]);
  });
  it("puts clean tier before quality and distance: true > null > false", () => {
    const dirty4k = cam("dirty4k", 26.7, -80.05, 2160, false);
    const unknown1080 = cam("unknown1080", 26.7, -80.05, 1080, null);
    const cleanFar480 = cam("cleanFar480", 27.2, -80.05, 480, true);
    const cleanNear480 = cam("cleanNear480", 26.71, -80.05, 480, true);
    const out = rankCams([dirty4k, unknown1080, cleanFar480, cleanNear480], here).map((c) => c.id);
    expect(out).toEqual(["cleanNear480", "cleanFar480", "unknown1080", "dirty4k"]);
  });
  it("does not mutate the input", () => {
    const list = [cam("a", 27, -80, 480), cam("b", 26.7, -80.05, 1080)];
    rankCams(list, here);
    expect(list.map((c) => c.id)).toEqual(["a", "b"]);
  });
});
