import type { Cam } from "./types";
import { distanceKm, type Point } from "./geo";

/** Lower is better. true (known clean) > null (unknown) > false (burned-in overlays). */
export function cleanTier(clean: boolean | null | undefined): number {
  if (clean === true) return 0;
  if (clean === false) return 2;
  return 1;
}

/** Lower is better. Buckets so that a 1080p cam 40 km away beats a 480p cam next door. */
export function qualityTier(height: number): number {
  if (height >= 2160) return 0;
  if (height >= 1440) return 1;
  if (height >= 1080) return 2;
  if (height >= 720) return 3;
  if (height > 0) return 4;
  return 5; // unknown
}

/** Best first: clean tier, then quality tier, then distance from the requested point. */
export function rankCams(cams: Cam[], from: Point): Cam[] {
  return cams
    .map((cam) => ({ cam, d: distanceKm(from, cam) }))
    .sort((a, b) => {
      const c = cleanTier(a.cam.clean) - cleanTier(b.cam.clean);
      if (c !== 0) return c;
      const q = qualityTier(a.cam.height) - qualityTier(b.cam.height);
      if (q !== 0) return q;
      return a.d - b.d;
    })
    .map((x) => x.cam);
}
