/**
 * Title filter for YouTube search results. search.list with eventType=live and a location
 * returns anything geotagged nearby: local news desks, gaming streams, lofi radios, church
 * services. Panes is a scenic screensaver, so we drop titles that match one of these words.
 * Kept deliberately small and word-bounded; false negatives are cheap (the clean/quality
 * ranking buries them), false positives would hide a good camera, so no "live" or "city".
 */
const NON_SCENIC = [
  "news",
  "breaking",
  "gaming",
  "gameplay",
  "playing",
  "music",
  "lofi",
  "lo-fi",
  "radio",
  "hits",
  "dj",
  "church",
  "worship",
  "sermon",
  "mass",
  "prayer",
  "podcast",
  "talk show",
  "interview",
  "asmr",
  "crypto",
  "bitcoin",
  "forex",
  "trading",
  "stocks",
  "poker",
  "casino",
  "slots",
  "giveaway",
  "chatting",
  "just chatting",
  "sports betting",
  "24/7 music",
  "24/7 hits",
  "24/7 radio",
];

const NON_SCENIC_RE = new RegExp(
  `(^|[^a-z0-9])(${NON_SCENIC.map((w) => w.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&")).join("|")})([^a-z0-9]|$)`,
  "i",
);

export function isScenicTitle(title: string): boolean {
  return !NON_SCENIC_RE.test(title);
}

/** Subset of the videos.list resource we read. */
export interface YtVideo {
  id: string;
  snippet?: {
    title?: string;
    channelTitle?: string;
    liveBroadcastContent?: "live" | "none" | "upcoming";
    thumbnails?: Record<string, { url?: string; width?: number; height?: number } | undefined>;
  };
  liveStreamingDetails?: { actualStartTime?: string; actualEndTime?: string };
  status?: { embeddable?: boolean; privacyStatus?: string };
  contentDetails?: { definition?: "hd" | "sd" };
  recordingDetails?: { location?: { latitude?: number; longitude?: number } };
}

/** Keep only streams that are live right now, embeddable, public and scenic-looking. */
export function isUsableLive(v: YtVideo): boolean {
  const live = v.liveStreamingDetails;
  if (!live?.actualStartTime || live.actualEndTime) return false;
  if (v.snippet?.liveBroadcastContent && v.snippet.liveBroadcastContent !== "live") return false;
  if (v.status?.embeddable !== true) return false;
  if (v.status?.privacyStatus && v.status.privacyStatus !== "public") return false;
  if (!isScenicTitle(v.snippet?.title ?? "")) return false;
  return true;
}

/**
 * Height proxy. The Data API does not expose stream resolution. contentDetails.definition
 * gives hd/sd; the thumbnail set now has fhd/qhd/uhd keys that only exist for 1080p+ sources,
 * which lets us tell 1080 from 1440 and 2160. sd -> 480, hd without those keys -> 1080.
 */
export function heightOf(v: YtVideo): number {
  const t = v.snippet?.thumbnails ?? {};
  if (t.uhd) return 2160;
  if (t.qhd) return 1440;
  if (t.fhd) return 1080;
  const def = v.contentDetails?.definition;
  if (def === "hd") return 1080;
  if (def === "sd") return 480;
  return 0;
}
