<h1 align="center">Panes</h1>

<p align="center">
  Live cameras as ambient TV for Android TV.<br>
  Beaches, skylines, harbours and piers, full screen, with a screensaver to match.
</p>

<p align="center">
  <img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-API%2028%2B-3ddc84?style=flat-square">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-app-7f52ff?style=flat-square">
  <img alt="Cloudflare Workers" src="https://img.shields.io/badge/Cloudflare-Worker-f38020?style=flat-square">
  <img alt="Private" src="https://img.shields.io/badge/repo-private-555?style=flat-square">
</p>

## What it does

- **Scenic cameras** from a small Cloudflare Worker: a hand-curated catalog plus YouTube live search, ranked by quality and distance from the city you pick.
- **Road cameras** from FL511 as a fallback.
- **Remote first.** One Activity, D-pad only. Media3 plays HLS, the YouTube IFrame player plays YouTube.
- Built for the NVIDIA Shield (Android 11). Personal use.

## Layout

| Path | What |
|---|---|
| `app/` | Android TV app, Kotlin, package `dev.vr.panes` |
| `server/` | `panes-cams` Worker serving `GET /v1/cams?lat&lon`. See [server/README.md](server/README.md) |
| `server/catalog-*.json` | Curated cameras, grouped by region |
| `CLAUDE.md` | Product, design and licensing rules |

## Build and install

Copy `local.properties` settings (it is gitignored):

```properties
sdk.dir=/path/to/Android/sdk
panes.server=https://<your-worker>.workers.dev   # empty: road cams only
panes.personal=false                              # true: owner's build, see CLAUDE.md
```


```sh
./gradlew :app:compileReleaseKotlin   # type-check
./gradlew assembleRelease             # build the APK
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Worker

```sh
cd server
bun install
bun run test
bun run deploy
```

Secrets (`YOUTUBE_API_KEY`, optional `PANES_APP_KEY`) go in with `bunx wrangler secret put`. Never commit them.

## Adding a camera

1. Add an entry to the right `server/catalog-<region>.json`: `lat`, `lon`, `title`, plus `videoId` for YouTube or `url` for HLS.
2. `bun run catalog`, then deploy.

HLS entries are served only with `licensed: true`, and this repo ships none. Streams whose owners allow personal viewing but not redistribution (local TV station cams, beach-cam networks) go in `server/catalog-local.json`, which is gitignored; copy [server/examples/catalog-local.example.json](server/examples/catalog-local.example.json). Check each owner's terms yourself: you are responsible for what you add.

## License

[PolyForm Noncommercial 1.0.0](LICENSE). Personal and other noncommercial use is fine; commercial use is not. Source-available, not OSI open source.

## Notes

- FL511 video needs a scoped Sectigo R46 trust anchor on Android 11. Don't trust all certs.
- Behind a VPN the TV's IP geolocates elsewhere, so pick the city manually.
