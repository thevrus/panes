# panes-cams

Cloudflare Worker behind the Panes Android TV app. TVs call `GET /v1/cams?lat&lon`; the Worker
merges a curated catalog with YouTube live search for the viewer's 0.5 degree cell, caches it in
KV for 12 hours, and ranks it. TVs never talk to the YouTube API; the key stays here.

## Layout

```
src/index.ts      fetch + scheduled handlers, auth header, response headers
src/cams.ts       per-cell pipeline: catalog + YouTube, KV cache, stale-while-revalidate
src/catalog.ts    normalizes catalog-*.json (licensed gate, dedupe, 120 km)
src/youtube.ts    search.list + videos.list clients, quotaExceeded detection
src/filter.ts     live/embeddable/public/scenic filter, height proxy
src/rank.ts       clean tier > quality tier > distance
src/quota.ts      per-UTC-day search.list counter in KV (cap 90)
src/geo.ts        param validation, cell snapping, haversine
scripts/build-catalog.mjs  bundles ../catalog-*.json into src/catalog.generated.json
test/             vitest (plain, pure functions + in-memory KV + stubbed fetch)
```

## Setup

```sh
cd panes/server
bun install
cp .dev.vars.example .dev.vars      # fill in YOUTUBE_API_KEY (and PANES_APP_KEY if you want the header check)
bun run typecheck
bun run test
bun run dev                          # http://localhost:5173/v1/cams?lat=26.7&lon=-80.05
```

`bun run dev|test|typecheck|deploy` first runs `bun run catalog`, which bundles
every `catalog-*.json` into `src/catalog.generated.json`
(gitignored). Missing or malformed files become `[]`, so the Worker always builds; rerun the
script (or any of those commands) after the catalogs change.

Local cron: `curl "http://localhost:5173/cdn-cgi/local/scheduled?format=json"`
([docs](https://developers.cloudflare.com/workers/configuration/cron-triggers/)).

## Deploy (you run these; nothing here deploys itself)

```sh
bunx wrangler login
bunx wrangler kv namespace create CAMS          # paste the id into wrangler.jsonc kv_namespaces[0].id
bunx wrangler secret put YOUTUBE_API_KEY        # required for YouTube search; without it the Worker is catalog-only
bunx wrangler secret put PANES_APP_KEY          # optional shared app token (see Abuse protection)
bun run deploy
```

Secrets are never in `wrangler.jsonc` or git. `.dev.vars*` and `.env*` are gitignored
([secrets docs](https://developers.cloudflare.com/workers/configuration/secrets/)). Restrict the
YouTube key in Google Cloud to the YouTube Data API v3 only.

## Contract

`GET /v1/cams?lat=<float>&lon=<float>` -> `200 application/json`, `Cache-Control: public, max-age=3600`

```json
{"cell":"26.5,-80.0","updatedAt":1792355414910,"cams":[
  {"id":"yt:<videoId>","kind":"youtube","videoId":"<videoId>","still":"https://i.ytimg.com/vi/<videoId>/maxresdefault_live.jpg",
   "title":"...","channel":"...","lat":26.7,"lon":-80.05,"height":1080,"attribution":"YouTube","clean":null,"stillIsFrame":null},
  {"id":"hls:<slug>","kind":"hls","url":"https://.../index.m3u8","still":"..."|null,
   "title":"...","channel":"...","lat":26.9,"lon":-80.07,"height":1080,"attribution":"...","clean":true,"stillIsFrame":true}
]}
```

- `clean`: no burned-in overlays (map, logo, QR, ticker). Catalog value, `null` for YouTube search results.
- `stillIsFrame`: whether `still` is a real camera frame or a promo graphic. Catalog value, `null` for YouTube search results. Not used for ranking.
- `height`: 2160 | 1440 | 1080 | 720 | 480 | 0 (unknown).
- Order: `clean` tier (true > null > false), then height tier (2160 / 1440 / 1080 / 720 / <720 / unknown), then distance from the requested point. Ranking happens per request on the cached cell, so two TVs in one cell get the same cams in their own order.
- Errors: `400 {"error":"bad_request"}` (lat outside [-90,90], lon outside [-180,180], missing, non-numeric), `401` when `PANES_APP_KEY` is set and `X-Panes-Key` does not match, `404` off `/v1/cams`, `405` non-GET. No 5xx for YouTube reasons: quota, outage or a missing key degrade to the last cached list or catalog-only (`x-panes-source: catalog-only` header for debugging).

`GET /v1/coverage` -> `{"cells":[[lat,lon],...]}`: south-west corners of every 0.5 degree cell whose `/v1/cams`
answer holds a live catalog camera (minus ids the cron marked dead), `max-age=3600`. The app's location picker
lists only places in those cells (or near an FL511 stream). Same 405/401 rules as `/v1/cams`.

`GET /v1/ipcam/<alias>.m3u8` -> one-variant HLS master playlist pointing at the cam's current
ipcamlive stream (its stream id changes per session). Only aliases named in the catalog answer; 404 otherwise.
Catalog HLS urls may be relative (`/v1/ipcam/...`); `/v1/cams` makes them absolute.

## Behavior

1. Validate, snap `lat/lon` to the 0.5 degree cell (floor; `cell` is `"26.5,-80.0"`), KV key `cell:<cell>`.
2. Fresh (< 12 h) in KV: serve. Stale: serve now, refresh in `ctx.waitUntil`. Missing: build inline.
3. Build = catalog entries within 120 km of the cell center (minus ids the cron marked dead) + one `search.list` (`type=video eventType=live location=<center> locationRadius=50km maxResults=50 safeSearch=strict`) + one `videos.list` for the new ids (`part=snippet,liveStreamingDetails,status,contentDetails,recordingDetails`). Keep only `actualStartTime` set and no `actualEndTime`, `liveBroadcastContent=live`, `status.embeddable=true`, public, and a scenic-looking title. `recordingDetails.location` when present (it is deprecated but still returned), else the cell center.
4. Height proxy: YouTube does not expose live resolution. `contentDetails.definition` `sd` -> 480, `hd` -> 1080; when the thumbnail set carries the newer `fhd`/`qhd`/`uhd` keys we upgrade to 1080/1440/2160 ([video resource](https://developers.google.com/youtube/v3/docs/videos)). HLS entries use the catalog height.
5. Title filter (`src/filter.ts`): word-bounded list (news, breaking, gaming, music, lofi, radio, church, worship, podcast, asmr, crypto, trading, casino, just chatting, 24/7 music/hits/radio ...). Small on purpose; the ranking buries misses, a false positive would hide a good camera.
6. A quota or network failure during a refresh never replaces a merged list with a thinner catalog-only one; it keeps the old list and pushes the next retry out 15 minutes.

## Catalog rules

Both files live in `panes/` and are owned by other agents; this Worker only reads them. Accepted
shape: an array (or `{cams:[...]}` / `{entries:[...]}`) of
`{kind, title, lat, lon|lng, height?, still?, channel?, attribution?, clean?, stillIsFrame?, live?, embeddable?, videoId?, channelId?, url?, licensed?}`.

- `live:false` or `embeddable:false` entries are dropped.
- **HLS entries ship only with `licensed: true`.** Erdman (video-monitoring.com) terms: "You are strictly forbidden to 'hotlink' content from our site. A hotlink is placing an image or video or other content within a link for the purpose of display on a third party site." `catalog-erdman.json` stays on disk, but nothing in it reaches a TV until an entry is marked `licensed:true` after written consent. YouTube entries need no flag: they play through YouTube's official embed.
- YouTube entries without `still` get `https://i.ytimg.com/vi/<id>/maxresdefault_live.jpg`.
- Duplicate ids across the two files: first one wins (erdman, then youtube).

## Cache: KV, not the Cache API

KV ([docs](https://developers.cloudflare.com/kv/api/write-key-value-pairs/)) because the cache
must be global: the Cache API is per data center, so 100 search calls a day would be spent again in
every colo a TV hits. KV also holds the quota counter and the cron's dead-id list, and keeps a stale
copy for 7 days (`expirationTtl`) so an outage serves yesterday's list rather than nothing.
Caveats accepted: eventual consistency (~60 s) and 1 write/s per key; writes are wrapped in
try/catch and the next request repeats them. There is no per-cell lock, so a cold cell hit by
several TVs at once can spend more than one search call; with a 12 h TTL that is rare and bounded by the daily cap.

## Quota math

- `search.list`: own bucket, **100 calls/day/project**, 1 unit each ([search.list](https://developers.google.com/youtube/v3/docs/search/list)). We stop at **90** (`SEARCH_DAILY_CAP`) counted per UTC day in `quota:search:<YYYY-MM-DD>`. Google resets at **midnight Pacific Time**, our counter at midnight UTC (7-8 h earlier); the 10-call headroom plus the conservative earlier rollover cover the mismatch and KV's approximate counting. A `403 quotaExceeded` from Google pins the counter to the cap for the rest of the UTC day.
- `videos.list`: the 10,000 units/day bucket, 1 unit per call (up to 50 ids). One per cell build, plus `ceil(catalogYoutubeIds/50)` per cron run. Negligible.
- Capacity: one search per cell per 12 h means at most ~90 distinct cells refreshed per day. With more cells than that the extra cells are catalog-only (or stale) until the next day. If the product outgrows this, ask Google for a search quota raise or add a second project key; nothing else in the design changes.

## Cron

`0 9 * * *` UTC (after the PT reset): one `videos.list` per 50 catalog YouTube ids, records ids whose broadcast ended or lost embeddability in `catalog:dead` (3-day TTL); `buildCell` drops them. On API failure yesterday's list is kept.

TODO: re-resolving a channel's *new* live `videoId` (catalog entries with `channelId`) needs
`search.list?channelId=...&eventType=live`, which spends the scarce 100/day bucket, and the
`channels.list` / `playlistItems.list` buckets do not expose the current live broadcast for
third-party channels. Scraping `youtube.com/channel/<id>/live` is not allowed. Until there is a
cheap path, the catalog agents refresh `videoId`s and the cron only hides dead ones.

## Abuse protection

Only `GET /v1/cams` answers. Optional shared token: set `PANES_APP_KEY` and the app sends
`X-Panes-Key: <value>`; compared constant-time, 401 on mismatch, off when the secret is unset.
Public cache header `max-age=3600` keeps repeat calls from one TV cheap. No CORS (native client).
For real rate limiting add a Cloudflare WAF rate-limiting rule on the route; not configured here.

## Tests

`bun run test` runs 67 vitest cases: param validation and cell snapping, distance and quality
tiering, clean-tier ranking, `videos.list` filtering (ended, not started, non-embeddable, unlisted,
non-scenic), height proxy, catalog normalization (licensed gate, dedupe, 120 km), merge pipeline
with one search + one videos call, quotaExceeded fallback to catalog with counter pinning, stop at
90, no-key path, dead-id filtering, fresh / stale-while-revalidate / keep-merged-on-failure,
handler shape, headers and the `X-Panes-Key` check, and the cron.

Plain vitest on pure functions with an in-memory KV fake and `vi.stubGlobal("fetch")` rather than
the Workers pool: the current docs moved to `@cloudflare/vitest-plugin` (vitest 4.1+), and nothing
here needs workerd semantics to be verified. Pinned: wrangler 4.151.1, vitest 5.0.5,
typescript 5.9.3, @cloudflare/workers-types 5.20261018.1 (all checked with `npm view` on 2026-10-18).
