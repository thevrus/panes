/**
 * Approximate per-UTC-day counter of search.list calls. Google resets the Search Queries
 * bucket at midnight Pacific Time, not UTC, so a UTC day is a conservative stand-in: our day
 * rolls over 7-8 hours before Google's, and the hard cap below leaves 10 calls of headroom.
 * KV is eventually consistent and allows one write per key per second, so two colos can
 * both read 89 and both search; that is why the cap is 90 and not 100.
 */
export const SEARCH_DAILY_CAP = 90;

function utcDay(now = Date.now()): string {
  return new Date(now).toISOString().slice(0, 10);
}

export function quotaKey(now = Date.now()): string {
  return `quota:search:${utcDay(now)}`;
}

export async function searchCallsToday(kv: KVNamespace, now = Date.now()): Promise<number> {
  try {
    const raw = await kv.get(quotaKey(now));
    const n = raw ? Number(raw) : 0;
    return Number.isFinite(n) ? n : 0;
  } catch {
    return SEARCH_DAILY_CAP; // fail closed: no KV, no search
  }
}

export async function canSearch(kv: KVNamespace, now = Date.now()): Promise<boolean> {
  return (await searchCallsToday(kv, now)) < SEARCH_DAILY_CAP;
}

/** Record `by` more search calls (by = SEARCH_DAILY_CAP+ pins the day closed after a quotaExceeded). */
export async function recordSearch(kv: KVNamespace, by = 1, now = Date.now()): Promise<number> {
  const next = (await searchCallsToday(kv, now)) + by;
  try {
    // 2 days: outlives the UTC day plus the PT offset, then garbage-collects itself.
    await kv.put(quotaKey(now), String(next), { expirationTtl: 2 * 86400 });
  } catch {
    /* 429 on a hot key; the count is approximate by design */
  }
  return next;
}
