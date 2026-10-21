// Wire contract (promised to the app team; do not change the shape).
export type CamKind = "youtube" | "hls";

export interface Cam {
  id: string; // "yt:<videoId>" | "hls:<slug>"
  kind: CamKind;
  videoId?: string; // youtube only
  url?: string; // hls only (m3u8)
  still: string | null;
  title: string;
  channel: string;
  lat: number;
  lon: number;
  height: number; // 2160 | 1440 | 1080 | 720 | 480 | 0 (unknown)
  attribution: string;
  /** No burned-in overlays (map, logo, QR, ticker). null = unknown (all YouTube search results). */
  clean: boolean | null;
  /** Whether `still` is a real camera frame (true) or a promo graphic (false). null = unknown. Not used for ranking. */
  stillIsFrame: boolean | null;
  /** Set when the channel restarts its stream under a new videoId: play the channel's current live stream instead. */
  channelId?: string;
}

export interface CamsResponse {
  cell: string;
  updatedAt: number;
  cams: Cam[];
}

/** What we keep in KV per cell. `source` is diagnostic only and never sent to clients. */
export interface CellRecord extends CamsResponse {
  source: "merged" | "catalog-only";
}

/**
 * Tolerant catalog entry. Both catalog files (catalog-erdman.json, catalog-youtube.json)
 * are written by other agents; the normalizer in catalog.ts accepts either an array or
 * `{ cams: [...] }` / `{ entries: [...] }`, and `lng` as an alias of `lon`.
 */
export interface CatalogEntry {
  id?: string;
  kind?: CamKind;
  videoId?: string;
  channelId?: string;
  url?: string;
  still?: string | null;
  title?: string;
  channel?: string;
  source?: string;
  lat?: number;
  lon?: number;
  lng?: number;
  height?: number;
  attribution?: string;
  live?: boolean;
  embeddable?: boolean;
  clean?: boolean | null;
  stillIsFrame?: boolean | null;
  /**
   * Required for hls entries. Erdman (video-monitoring.com) forbids hotlinking its video, so an
   * hls entry ships only once it carries licensed:true after written consent. YouTube entries
   * play through YouTube's official embed and need no flag.
   */
  licensed?: boolean;
  /** The channel restarts its stream daily under a new videoId: send channelId so the app embeds the channel's current live. */
  follow?: boolean;
}

export interface Env {
  CAMS: KVNamespace;
  YOUTUBE_API_KEY?: string;
  /** Optional shared app token. When set, requests must carry it in X-Panes-Key. */
  PANES_APP_KEY?: string;
}
