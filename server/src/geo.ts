import * as z from "zod/mini";

const CELL_DEG = 0.5;

export interface Point {
  lat: number;
  lon: number;
}

export interface Cell {
  key: string; // "26.5,-80.0"
  lat: number; // south-west corner
  lon: number;
  center: Point;
}

const coord = (limit: number) =>
  z.pipe(
    z.pipe(z.string().check(z.trim(), z.regex(/^-?\d+(\.\d+)?$/)), z.transform(Number)),
    z.number().check(z.gte(-limit), z.lte(limit)),
  );

/** `?lat&lon` query: plain decimals (no exponent, no Infinity, no hex) inside the valid range. */
const PointQuery = z.object({ lat: coord(90), lon: coord(180) });

/** Parse and validate lat/lon query params. Returns null on any bad input. */
export function parsePoint(lat: string | null | undefined, lon: string | null | undefined): Point | null {
  return PointQuery.safeParse({ lat, lon }).data ?? null;
}

/** Snap to the 0.5 degree cell containing the point (floor). lat=90 / lon=180 fold into the last cell. */
export function snapToCell(p: Point): Cell {
  const lat = Math.min(Math.floor(p.lat / CELL_DEG) * CELL_DEG, 90 - CELL_DEG);
  const lon = Math.min(Math.floor(p.lon / CELL_DEG) * CELL_DEG, 180 - CELL_DEG);
  return {
    key: `${fmt(lat)},${fmt(lon)}`,
    lat,
    lon,
    center: { lat: lat + CELL_DEG / 2, lon: lon + CELL_DEG / 2 },
  };
}

function fmt(n: number): string {
  // Normalize -0 so a key never reads "-0.0".
  return (n === 0 ? 0 : n).toFixed(1);
}

const EARTH_KM = 6371.0088;

/** Great-circle distance in km (haversine). */
export function distanceKm(a: Point, b: Point): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(s)));
}
