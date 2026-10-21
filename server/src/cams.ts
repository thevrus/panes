import type { Cam, CellRecord, Env } from "./types";
import type { Cell } from "./geo";
import { catalogNear, loadCatalog, type CatalogCam } from "./catalog";
import { heightOf, isUsableLive } from "./filter";
import { canSearch, recordSearch, SEARCH_DAILY_CAP } from "./quota";
import { listVideos, searchLiveNear, YouTubeError, ytStill } from "./youtube";

export const FRESH_MS = 12 * 3600 * 1000;
/** KV keeps a stale copy for a week so we can serve it when YouTube is down or out of quota. */
const KEEP_TTL_S = 7 * 86400;
/** Catalog youtube entries whose stream the daily cron found ended; refreshed by the cron. */
export const DEAD_KEY = "catalog:dead";

export const cellKey = (cell: Cell) => `cell:${cell.key}`;

async function readCell(kv: KVNamespace, cell: Cell): Promise<CellRecord | null> {
  try {
    return await kv.get<CellRecord>(cellKey(cell), "json");
  } catch {
    return null;
  }
}

const isFresh = (rec: CellRecord, now: number) => now - rec.updatedAt < FRESH_MS;

async function deadIds(kv: KVNamespace): Promise<Set<string>> {
  try {
    return new Set((await kv.get<string[]>(DEAD_KEY, "json")) ?? []);
  } catch {
    return new Set();
  }
}

/** Catalog cams minus the YouTube streams the cron found ended. The dead list is only written by the cron, which needs the key. */
export async function liveCatalog(env: Env, cams: CatalogCam[] = loadCatalog()): Promise<CatalogCam[]> {
  const dead = env.YOUTUBE_API_KEY ? await deadIds(env.CAMS) : new Set<string>();
  // A followed cam's catalog videoId ends daily by design; the app plays the channel's current stream.
  return cams.filter((c) => c.follow || !(c.kind === "youtube" && c.videoId && dead.has(c.videoId)));
}

function stripInternal(c: CatalogCam): Cam {
  const { channelId, follow, ...cam } = c;
  return follow && channelId ? { ...cam, channelId } : cam;
}

/**
 * Build the merged list for a cell: curated catalog within 80 km, plus one YouTube search
 * (when the key is set and the daily search budget allows) resolved through one videos.list.
 * Never throws for YouTube reasons; `source` says whether YouTube contributed.
 */
export async function buildCell(env: Env, cell: Cell, now = Date.now()): Promise<{ record: CellRecord; ytError: YouTubeError | Error | null }> {
  const catalog = await liveCatalog(env, catalogNear(cell.center));
  const cams: Cam[] = catalog.map(stripInternal);
  const known = new Set(cams.map((c) => c.id));

  let source: CellRecord["source"] = "catalog-only";
  let ytError: YouTubeError | Error | null = null;

  if (env.YOUTUBE_API_KEY && (await canSearch(env.CAMS, now))) {
    try {
      await recordSearch(env.CAMS, 1, now);
      const ids = await searchLiveNear(env.YOUTUBE_API_KEY, cell.center);
      const fresh = ids.filter((id) => !known.has(`yt:${id}`));
      const videos = fresh.length ? await listVideos(env.YOUTUBE_API_KEY, fresh) : [];
      for (const v of videos) {
        if (!isUsableLive(v)) continue;
        const loc = v.recordingDetails?.location;
        const hasLoc = typeof loc?.latitude === "number" && typeof loc?.longitude === "number";
        cams.push({
          id: `yt:${v.id}`,
          kind: "youtube",
          videoId: v.id,
          still: ytStill(v.id),
          title: v.snippet?.title ?? "",
          channel: v.snippet?.channelTitle ?? "",
          lat: hasLoc ? loc!.latitude! : cell.center.lat,
          lon: hasLoc ? loc!.longitude! : cell.center.lon,
          height: heightOf(v),
          attribution: "YouTube",
          clean: null,
          stillIsFrame: null,
        });
      }
      source = "merged";
    } catch (err) {
      ytError = err instanceof Error ? err : new Error(String(err));
      if (err instanceof YouTubeError && err.quotaExceeded) {
        // Google says the day is spent; pin our counter so no other cell retries today.
        await recordSearch(env.CAMS, SEARCH_DAILY_CAP, now);
      }
    }
  }

  return { record: { cell: cell.key, updatedAt: now, cams, source }, ytError };
}

async function writeCell(kv: KVNamespace, cell: Cell, record: CellRecord): Promise<void> {
  try {
    await kv.put(cellKey(cell), JSON.stringify(record), { expirationTtl: KEEP_TTL_S });
  } catch {
    /* 1 write/s/key; the next request will write it */
  }
}

/**
 * Resolve the record to serve for a cell.
 *  - fresh in KV: serve it
 *  - stale in KV: serve it now, refresh in the background (stale-while-revalidate)
 *  - missing: build inline; if YouTube failed, still serve what the catalog has
 * Only a merged result or a catalog-only result that replaced nothing better is written back,
 * so a quota outage never overwrites a good merged list with a thinner one.
 */
export async function resolveCell(env: Env, cell: Cell, ctx: Pick<ExecutionContext, "waitUntil">, now = Date.now()): Promise<CellRecord> {
  // Keyless, a cell is a pure function of the bundled catalog: compute it, never touch KV.
  if (!env.YOUTUBE_API_KEY) return (await buildCell(env, cell, now)).record;
  const cached = await readCell(env.CAMS, cell);
  if (cached && isFresh(cached, now)) return cached;

  const refresh = async (): Promise<CellRecord> => {
    const { record } = await buildCell(env, cell, now);
    if (record.source === "merged" || !cached || cached.source === "catalog-only") {
      await writeCell(env.CAMS, cell, record);
      return record;
    }
    // YouTube unavailable and we hold an older merged list: keep serving it, but bump
    // updatedAt so we do not hammer YouTube on every request while it is down.
    const kept = { ...cached, updatedAt: now - FRESH_MS + 15 * 60 * 1000 };
    await writeCell(env.CAMS, cell, kept);
    return kept;
  };

  if (cached) {
    ctx.waitUntil(refresh().catch(() => undefined));
    return cached;
  }
  return refresh();
}
