import type { Cam, CatalogEntry } from "./types";
import { distanceKm, snapToCell, type Point } from "./geo";
import { ytStill } from "./youtube";
import generated from "./catalog.generated.json";

// 120 km lets a city with no cams of its own (West Palm Beach) reach the nearest skyline (Miami).
const CATALOG_RADIUS_KM = 120;

/** Accepts a bare array, `{cams:[...]}` or `{entries:[...]}`; anything else is an empty catalog. */
export function unwrap(raw: unknown): CatalogEntry[] {
  if (Array.isArray(raw)) return raw as CatalogEntry[];
  if (raw && typeof raw === "object") {
    const o = raw as Record<string, unknown>;
    for (const k of ["cams", "entries", "items"]) if (Array.isArray(o[k])) return o[k] as CatalogEntry[];
  }
  return [];
}

function slug(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 64);
}

/** Normalize one catalog entry into a Cam, or null when it is unusable. Carries channelId through for the cron. */
export function toCam(e: CatalogEntry, attributionDefault: string): CatalogCam | null {
  if (e.live === false || e.embeddable === false) return null;
  const lat = e.lat;
  const lon = e.lon ?? e.lng;
  if (typeof lat !== "number" || typeof lon !== "number" || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const kind = e.kind ?? (e.videoId ? "youtube" : e.url ? "hls" : undefined);
  const title = (e.title ?? "").trim();
  if (!title) return null;
  const base = {
    still: e.still ?? null,
    title,
    channel: (e.channel ?? e.source ?? "").trim(),
    lat,
    lon,
    height: typeof e.height === "number" && e.height > 0 ? e.height : 0,
    attribution: e.attribution ?? attributionDefault,
    clean: typeof e.clean === "boolean" ? e.clean : null,
    stillIsFrame: typeof e.stillIsFrame === "boolean" ? e.stillIsFrame : null,
  };
  if (kind === "youtube" && e.videoId) {
    return {
      id: `yt:${e.videoId}`,
      kind,
      videoId: e.videoId,
      ...base,
      still: base.still ?? ytStill(e.videoId),
      ...(e.channelId ? { channelId: e.channelId } : {}),
      ...(e.follow && e.channelId ? { follow: true } : {}),
    };
  }
  // Hotlinking rule: an HLS URL is served only with consent recorded as licensed:true (written
  // consent, or the owner's personal-use exception noted in `license`). "/..." urls are this Worker's.
  if (kind === "hls" && e.licensed === true && e.url && /^(https?:\/\/|\/v1\/)/.test(e.url)) {
    const s = (e.id ?? "").replace(/^hls:/, "") || slug(title);
    return { id: `hls:${s}`, kind, url: e.url, ...base };
  }
  return null;
}

export type CatalogCam = Cam & { channelId?: string; follow?: boolean };

/** Merge, normalize and deduplicate a catalog bundle. */
export function buildCatalog(raw: unknown): CatalogCam[] {
  const bundle = raw as { sources?: Record<string, unknown> } | unknown[] | null | undefined;
  const groups: Array<[unknown, string]> = Array.isArray(bundle)
    ? [[bundle, "catalog"]]
    : Object.entries(bundle?.sources ?? {}).map(([name, entries]): [unknown, string] => [
        entries,
        name === "erdman" ? "video-monitoring.com" : "YouTube",
      ]);
  const seen = new Set<string>();
  const out: CatalogCam[] = [];
  for (const [group, attribution] of groups) {
    for (const e of unwrap(group)) {
      const cam = toCam(e, attribution);
      if (cam && !seen.has(cam.id)) {
        seen.add(cam.id);
        out.push(cam);
      }
    }
  }
  return out;
}

let cache: CatalogCam[] | null = null;

/** The bundled catalog, built once per isolate. */
export function loadCatalog(): CatalogCam[] {
  return (cache ??= buildCatalog(generated));
}

export function catalogNear(center: Point, catalog: CatalogCam[] = loadCatalog(), radiusKm = CATALOG_RADIUS_KM): CatalogCam[] {
  return catalog.filter((c) => distanceKm(center, c) <= radiusKm);
}

/**
 * South-west corners of every 0.5 degree cell whose /v1/cams answer includes at least one of [cams]: the same
 * center-to-camera test as catalogNear, run from the camera's side.
 */
export function coveredCells(cams: Point[], radiusKm = CATALOG_RADIUS_KM): Array<[number, number]> {
  const out = new Map<string, [number, number]>();
  for (const cam of cams) {
    // A degree of latitude is ~111 km; longitude shrinks with cos(lat). Half a cell of slack on each side.
    const dLat = radiusKm / 111 + 0.5;
    const dLon = radiusKm / (111 * Math.max(0.1, Math.cos((cam.lat * Math.PI) / 180))) + 0.5;
    const sw = snapToCell({ lat: Math.max(-90, cam.lat - dLat), lon: Math.max(-180, cam.lon - dLon) });
    const ne = snapToCell({ lat: Math.min(90, cam.lat + dLat), lon: Math.min(180, cam.lon + dLon) });
    for (let lat = sw.lat; lat <= ne.lat; lat += 0.5)
      for (let lon = sw.lon; lon <= ne.lon; lon += 0.5) {
        const cell = snapToCell({ lat: lat + 0.25, lon: lon + 0.25 });
        if (!out.has(cell.key) && distanceKm(cell.center, cam) <= radiusKm) out.set(cell.key, [cell.lat, cell.lon]);
      }
  }
  return [...out.values()].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
}
