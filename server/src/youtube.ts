import type { Point } from "./geo";
import type { YtVideo } from "./filter";

const API = "https://www.googleapis.com/youtube/v3";
const SEARCH_RADIUS_KM = 50;

export class YouTubeError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly reason: string | null,
  ) {
    super(message);
    this.name = "YouTubeError";
  }
  get quotaExceeded(): boolean {
    return this.status === 403 && this.reason === "quotaExceeded";
  }
}

interface ApiErrorBody {
  error?: { code?: number; message?: string; errors?: Array<{ reason?: string; domain?: string }> };
}

async function call<T>(url: URL): Promise<T> {
  const res = await fetch(url.toString(), { headers: { accept: "application/json" } });
  if (!res.ok) {
    let reason: string | null = null;
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = (await res.json()) as ApiErrorBody;
      reason = body.error?.errors?.[0]?.reason ?? null;
      message = body.error?.message ?? message;
    } catch {
      /* non-JSON error body */
    }
    throw new YouTubeError(message, res.status, reason);
  }
  return (await res.json()) as T;
}

/** One search.list call: live videos geotagged within SEARCH_RADIUS_KM of `center`. Costs 1 of 100/day. */
export async function searchLiveNear(key: string, center: Point): Promise<string[]> {
  const url = new URL(`${API}/search`);
  url.searchParams.set("part", "snippet");
  url.searchParams.set("type", "video");
  url.searchParams.set("eventType", "live");
  url.searchParams.set("location", `${center.lat},${center.lon}`);
  url.searchParams.set("locationRadius", `${SEARCH_RADIUS_KM}km`);
  url.searchParams.set("maxResults", "50");
  url.searchParams.set("safeSearch", "strict");
  url.searchParams.set("key", key);
  const data = await call<{ items?: Array<{ id?: { videoId?: string } }> }>(url);
  const ids: string[] = [];
  for (const item of data.items ?? []) {
    const id = item.id?.videoId;
    if (id && !ids.includes(id)) ids.push(id);
  }
  return ids;
}

/** One videos.list call per 50 ids (the 10,000/day bucket, 1 unit each). */
export async function listVideos(key: string, ids: string[]): Promise<YtVideo[]> {
  const out: YtVideo[] = [];
  for (let i = 0; i < ids.length; i += 50) {
    const url = new URL(`${API}/videos`);
    url.searchParams.set("part", "snippet,liveStreamingDetails,status,contentDetails,recordingDetails");
    url.searchParams.set("id", ids.slice(i, i + 50).join(","));
    url.searchParams.set("key", key);
    const data = await call<{ items?: YtVideo[] }>(url);
    out.push(...(data.items ?? []));
  }
  return out;
}

export function ytStill(videoId: string): string {
  return `https://i.ytimg.com/vi/${videoId}/maxresdefault_live.jpg`;
}
