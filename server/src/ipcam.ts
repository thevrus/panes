import { loadCatalog } from "./catalog";

/** Catalog urls of this shape are served by the /v1/ipcam route below. */
export const IPCAM_PATH = /^\/v1\/ipcam\/([a-z0-9_-]{1,40})\.m3u8$/;

/** Aliases the catalog names; the route serves nothing else, so it is not an open proxy. */
function known(alias: string): boolean {
  return loadCatalog().some((c) => c.url?.match(IPCAM_PATH)?.[1] === alias);
}

/**
 * ipcamlive hands out a per-session stream id, so a fixed m3u8 url goes stale. Resolve the current
 * one and answer with a one-variant master playlist pointing at ipcamlive directly: the player then
 * refreshes the media playlist and loads segments from ipcamlive, and hits us once per play.
 */
export async function ipcamMaster(alias: string): Promise<string | null> {
  if (!known(alias)) return null;
  const res = await fetch(`https://ipcamlive.com/player/getcamerastreamstate.php?alias=${alias}`);
  if (!res.ok) return null;
  const d = ((await res.json().catch(() => null)) as { details?: Record<string, string> } | null)?.details;
  if (d?.streamavailable !== "1" || !d.streamid || !/^https?:\/\/[a-z0-9.-]+\.ipcamlive\.com\/$/.test(d.address ?? "")) return null;
  const media = `${d.address!.replace(/^http:/, "https:")}streams/${d.streamid}/stream.m3u8`;
  return `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\n${media}\n`;
}
