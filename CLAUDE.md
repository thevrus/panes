# Panes

Android TV app: live public cameras as ambient TV, plus a screensaver (`Dream`). FL511 is the current data source, not the product's identity. Keep copy, design and code location-neutral so other regions and sources can plug in.

## Rule: great cameras first, lightweight second

The product is beautiful live views. A tiny app showing 360p traffic cams misses the point. Camera quality and coverage (sharp 1080p-4K, scenic and worth leaving on) beat app size. A dependency, a bigger APK or more memory is fine when it buys real capability: a better source, a better stream format, smoother playback.

Keep the balance: spend weight only on what viewers can see or feel, and stay lean everywhere else. It must still run smoothly on modest TV hardware (SHIELD on API 30 is the reference).
- Before adding a dependency, ask what it gives the viewer. Media and playback libraries (media3 modules, a source SDK) earn their place. Convenience libraries mostly don't. Prefer the Android framework and Kotlin stdlib for UI, networking, JSON and threading.
- Build views in code or plain XML. No fragments or navigation frameworks. Keep it to one Activity.
- Images: decode with `inSampleSize` to the size they are shown at, keep few bitmaps alive, and drop them when a view goes away. Effects like blur run on a thumbnail, never on a full frame.
- Network: use the shared `Net.pool`, cache what's stable (the index, names, details), and don't poll faster than the content changes.
- Before adding code, check whether existing code already does the job (`card()`, `lift()`, `glint()`, `fade()`, `dp()`, `Pane.fill`). Delete code that is no longer used.
- Release builds stay minified and shrunk.

## Design rule: it should look like Apple made it for Apple TV

Every screen, control and motion should feel like a first-party tvOS app (TV app, Photos, Aerial screensaver). When unsure, ask "what would tvOS do?" and do that. This rule overrides generic design defaults.

**UI**
- Content is the interface. Camera imagery fills the screen; chrome is minimal, translucent or dimmed, and gets out of the way.
- Dark, quiet surfaces (`@color/ink`, `veil`, `tile`). No saturated accents, gradients for decoration, borders, or badges. Text is white at the `text` / `text_dim` / `text_faint` steps.
- One type family (Geist), with hierarchy from size and weight only: large titles around 34sp semibold, body 15-17sp, captions 13sp. No all-caps labels or letterspaced kickers.
- Text over live video must stay readable on any picture: bright sky, snow, water. Darken with the eased `scrim` gradient only where text sits and keep the middle clear. Add the `OnPicture` soft shadow to that text, and use `on_picture_dim` (not `text_dim`) for secondary lines.
- Generous safe margins: about 48-64dp on the sides and at least 40dp top and bottom. Nothing touches the edge.
- Posters are 16:9 with rounded corners (12dp). Titles sit under the poster, never on top of it.

**Focus is the cursor**
- The focused item scales up (1.06-1.1x), lifts with one soft shadow below it, and gets a specular glint (`glint()`). Unfocused items stay flat and dimmer.
- Whatever grows on focus has room to grow: no clipping. Neighbours (like a caption) move out of its way.
- Focus moves predictably: D-pad direction means that direction, focus never jumps to hidden views, and it lands on the obvious item when a screen opens (the current camera).
- Long titles marquee only while focused.

**Motion**
- Short and eased: 160-380ms with `DecelerateInterpolator`. Nothing bounces or loops for decoration. Two exceptions are slow on purpose: the focus glint sweep (700ms) and the still-frame drift.
- Stills drift slowly (Ken Burns) and crossfade. Overlays fade or rise in. Content never depends on an animation finishing in order to be visible.

**UX**
- Select acts, Back always goes up one level, and Back from the top returns to full-screen ambient.
- Few words. Use sentence case and plain labels ("All cameras", "Add to favorites"), with no exclamation marks or jargon.
- Don't show dead states. Broken feeds leave the UI, loading uses the small spinner, and errors say what happens next.
- Remote first. Every feature works with the D-pad, Select, Back and long-press. Nothing needs a pointer or typing.

## Sources

- **Scenic** (first): from the Panes server (`Repo.SERVER`, a Cloudflare Worker in `panes/server`) via `GET /v1/cams?lat&lon`; its URL is `panes.server` in `local.properties`, empty by default. Kinds are `youtube` (played with the official IFrame player in a WebView, `Tube`) and licensed `hls` (played with ExoPlayer). Cached for 6 h per half-degree cell, falling back to the last good answer. While the URL is empty the app shows road cams only.
- **Road** (fallback): FL511 stills and HLS.
- YouTube player (`Tube`): YouTube Developer Policies III.I.6 (no blocking any portion of a player) and III.I.9 (no background players), plus Required Minimum Functionality: no overlays, frames or other elements in front of any part of the player, viewport at least 200x200, no autoplay until more than half the player is visible. Neither page mentions muted playback, so don't assume muting makes hidden playback OK. The default build complies: the player is revealed the moment it plays (`Tube.Bridge.state`) and the clock steps aside while it plays (`chrome()`). The owner's build (`panes.personal=true` in `local.properties`) adds a settings row, "YouTube elements" (Hidden by default), that keeps the poster over the player for 5 s and the clock up, like Aerial; Shown behaves like the default build. The row doesn't exist in default builds. Never change the default to match the personal build.
- YouTube rules: no drawing over the player in ambient mode (`chrome()` hides the scrim and title), no stream extraction, and no caching of video.
- Never hotlink an unlicensed stream (for example the Erdman/video-monitoring.com feeds), even if it plays.
- **Personal-use streams** (WPTV ipcamlive, Erdman) live in `server/catalog-local.json`, which is gitignored and never committed; their owners allow personal viewing, not redistribution. The Worker resolves ipcamlive's per-session stream id at `/v1/ipcam/<alias>.m3u8`. Don't add `licensed:true` entries to a committed catalog without written consent.

## Build

`./gradlew :app:compileReleaseKotlin` to type-check; `./gradlew assembleRelease` for the APK.
