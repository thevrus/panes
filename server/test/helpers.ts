import { vi } from "vitest";
import type { Env } from "../src/types";
import type { YtVideo } from "../src/filter";
/** Minimal in-memory KVNamespace: get (text/json), put with expirationTtl, delete. */
export function fakeKV(): KVNamespace & { store: Map<string, string> } {
  const store = new Map<string, string>();
  const kv = {
    store,
    async get(key: string, type?: unknown) {
      const v = store.get(key);
      if (v === undefined) return null;
      const t = typeof type === "string" ? type : (type as { type?: string } | undefined)?.type;
      return t === "json" ? JSON.parse(v) : v;
    },
    async put(key: string, value: string) {
      store.set(key, String(value));
    },
    async delete(key: string) {
      store.delete(key);
    },
  };
  return kv as unknown as KVNamespace & { store: Map<string, string> };
}

export function env(over: Partial<Env> = {}): Env & { CAMS: ReturnType<typeof fakeKV> } {
  return { CAMS: fakeKV(), YOUTUBE_API_KEY: "test-key", ...over } as Env & { CAMS: ReturnType<typeof fakeKV> };
}

export function ctx() {
  const pending: Promise<unknown>[] = [];
  return {
    waitUntil: (p: Promise<unknown>) => void pending.push(p),
    passThroughOnException: () => {},
    flush: () => Promise.all(pending),
  };
}

export interface FetchRoute {
  match: (url: URL) => boolean;
  status?: number;
  body: unknown;
}

/** Replace global fetch with a router; returns the list of URLs that were hit. */
export function stubFetch(routes: FetchRoute[]): { calls: URL[] } {
  const calls: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string | URL | Request) => {
      const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
      calls.push(url);
      const r = routes.find((x) => x.match(url));
      if (!r) return new Response("unrouted " + url.pathname, { status: 599 });
      return new Response(JSON.stringify(r.body), {
        status: r.status ?? 200,
        headers: { "content-type": "application/json" },
      });
    }),
  );
  return { calls };
}

export const isSearch = (u: URL) => u.pathname.endsWith("/youtube/v3/search");
export const isVideos = (u: URL) => u.pathname.endsWith("/youtube/v3/videos");

export const quotaExceededBody = {
  error: {
    code: 403,
    message: "The request cannot be completed because you have exceeded your quota.",
    errors: [{ reason: "quotaExceeded", domain: "youtube.quota" }],
  },
};

export function liveVideo(id: string, over: Partial<YtVideo> = {}): YtVideo & { snippet: NonNullable<YtVideo["snippet"]> } {
  return {
    id,
    snippet: { title: `Harbor cam ${id}`, channelTitle: "Harbor", liveBroadcastContent: "live" as const, thumbnails: { high: {} } },
    liveStreamingDetails: { actualStartTime: "2026-10-18T00:00:00Z" },
    status: { embeddable: true, privacyStatus: "public" },
    contentDetails: { definition: "hd" },
    ...over,
  };
}
