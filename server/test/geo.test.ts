import { describe, expect, it } from "vitest";
import { distanceKm, parsePoint, snapToCell } from "../src/geo";

describe("parsePoint", () => {
  it("accepts in-range decimals", () => {
    expect(parsePoint("26.71", "-80.05")).toEqual({ lat: 26.71, lon: -80.05 });
    expect(parsePoint("-90", "180")).toEqual({ lat: -90, lon: 180 });
  });
  it.each([
    [null, "-80"],
    ["26", null],
    ["91", "0"],
    ["-90.1", "0"],
    ["0", "180.5"],
    ["0", "-181"],
    ["abc", "0"],
    ["NaN", "0"],
    ["1e2", "0"],
    ["", ""],
    ["Infinity", "0"],
  ])("rejects lat=%s lon=%s", (lat, lon) => {
    expect(parsePoint(lat, lon)).toBeNull();
  });
});

describe("snapToCell", () => {
  it("floors to 0.5 degree cells and formats the key", () => {
    expect(snapToCell({ lat: 26.71, lon: -80.05 }).key).toBe("26.5,-80.5");
    expect(snapToCell({ lat: 26.99, lon: -80.0 }).key).toBe("26.5,-80.0");
    expect(snapToCell({ lat: 26.5, lon: -80.0 }).key).toBe("26.5,-80.0");
    expect(snapToCell({ lat: -0.2, lon: 0.3 }).key).toBe("-0.5,0.0");
    expect(snapToCell({ lat: 0, lon: 0 }).key).toBe("0.0,0.0");
  });
  it("folds the north pole and the antimeridian into the last cell", () => {
    expect(snapToCell({ lat: 90, lon: 180 }).key).toBe("89.5,179.5");
  });
  it("centers the cell", () => {
    expect(snapToCell({ lat: 26.71, lon: -80.05 }).center).toEqual({ lat: 26.75, lon: -80.25 });
  });
});

describe("distanceKm", () => {
  it("matches a known pair within 1%", () => {
    // West Palm Beach -> Miami, about 109 km.
    const d = distanceKm({ lat: 26.7153, lon: -80.0534 }, { lat: 25.7617, lon: -80.1918 });
    expect(d).toBeGreaterThan(105);
    expect(d).toBeLessThan(110);
  });
  it("is zero for the same point", () => {
    expect(distanceKm({ lat: 1, lon: 1 }, { lat: 1, lon: 1 })).toBe(0);
  });
});
