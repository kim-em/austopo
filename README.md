AusTopo
---

AusTopo is a lightweight Android app for Australian topographic maps.

# Philosophy

Several of the Australian State governments provide high quality topographic map tile servers, but it's not trivial for a general user to make use of them. I suspect here that there's some hesitation to provide user level apps built on top of these free services, and instead to leave these for commercial offerings. This is a great pity, as the underlying data is free and paid for by taxpayers, and the commercials apps available aren't that great. The advent of excellent AI agents and their ready availability means we have an opportunity here: to provide easy to use apps, accessing free government data sources, and to provide these apps for free (because they are now so cheap to author). This app is an initial attempt to do so for topographic map data, but I hope others will be inspired to implement similar apps for other data sources!

# To install

Pre-built debug APKs are published as artifacts on every CI run. From a merged commit on `main`, grab the `app-debug.apk` artifact from the GitHub Actions run and sideload it:

```
adb install -r app-debug.apk
```

To build from source:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires a JDK (17+) and the Android SDK. No Android Studio needed.

The app requests no storage permissions and no "All files access" prompt. User-supplied map sheets (if any) live in the app's own external-files dir, visible over USB at `/sdcard/Android/data/com.kim.austopo/files/TopoMaps/`.

# Features

## Tile servers

Live tiles covering every Australian state and territory:

- **NSW, VIC, QLD, SA, TAS** — each state's own ArcGIS REST topographic service.
- **Northern Territory** — Geoscience Australia's [national Topographic Base Map](https://services.ga.gov.au/gis/rest/services/Topographic_Base_Map/MapServer), used for NT because no NT-specific topo tile server is published. © Commonwealth of Australia (Geoscience Australia), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Western Australia** — [OpenTopoMap](https://opentopomap.org/) (XYZ PNG, capped at zoom 17), used for WA because Landgate's basemap is paywalled through SLIP. Attribution: *Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap ([CC-BY-SA](https://creativecommons.org/licenses/by-sa/3.0/))*. In keeping with [OpenTopoMap's usage policy](https://opentopomap.org/about) and the underlying [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/), WA coverage is **live-view only**: the offline-region feature deliberately skips WA so that pre-emptive bulk fetching never hits OpenTopoMap. Requests identify themselves with a descriptive `User-Agent` (`AusTopo/<version> (+repo URL)`).

Each tile source's extent is clipped to its region so cross-boundary pans don't generate 404s. Web Mercator LOD selection uses hysteresis to avoid flicker at zoom thresholds, and the last visible LOD is pinned in the in-memory LRU so it stays as a fallback during transitions.

## Offline regions

Drag-select a bounding box on the map and pin every tile inside it (across all relevant LODs and all states that cover it) into a separate pinned store. Pinned tiles are never evicted. The Regions activity lists saved regions, shows download progress, and lets you delete them. Selections that include WA show a notice and WA tiles are excluded from the plan — see *Tile servers* above.

## Cache control

Transient (non-pinned) tiles are capped by a user-configurable size limit, set from Cache Management with a SeekBar + live readout. A debounced LRU eviction pass runs after writes once the cap is exceeded. Pinned tiles are never counted against the cap.

## Bookmarks

A RecyclerView of saved locations with swipe-to-delete and 5-second UNDO (survives rotation), overflow-menu rename, and paste-to-add. The paste parser accepts:

- Google Maps URLs (`@lat,lon` or `!3dLAT!4dLON` form, including `maps.app.goo.gl`)
- Decimal pairs (`-37.8, 144.9` or space-separated)
- DMS with hemisphere (`37°48'S 144°54'E`, with or without seconds)
- MGA grid refs (`55H 311000 5811000`)

## Overlays

- **Scale bar**, latitude-corrected.
- **Sheet rectangle overlay**, toggleable and persisted.
- **1 km MGA grid** (with 10 km major lines snapped to 10 km multiples) for Victoria, rendered per-zone across zones 54 and 55 and clipped to each 6° band. World-space cached and rebuilt only when the camera leaves the margined bbox.

## NSW sheet index

One-tap sync of the NSW getlost.com.au topo sheet index. Tapping a sheet rectangle zooms to it (for live-tile states) or loads the local GeoTIFF-derived JPEG if one is present.

## Map sheet import

For areas without a good tile server, or for offline-first use, the app can display pre-converted GeoTIFFs. Use `convert_geotiff.py` to produce a `.jpg` + `.json` pair, then push them to the TopoMaps directory.

## UI

Translucent title bar with "AusTopo" label and a hamburger menu. Tap the hamburger to access: My Location, Bookmarks, Save Offline, Offline Regions, Cache Management, Sync NSW Index, Show/Hide Sheet Grid, Show/Hide 1 km Grid. The title bar auto-hides on pan and restores on tap.

## Geodesy

Zone-explicit UTM/MGA conversions using the GRS80 ellipsoid via the Redfern series, sub-10 cm accurate within a zone — well under the 1 km grid granularity. Canonical `TileCoverage` type for describing tile sets across LODs.

## Permissions

- `INTERNET` — tile fetches.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — "Loc" button.

No external storage permissions. No legacy storage. No "All files access" prompt. No foreground-service permission. (Offline-region downloads currently run from a coroutine inside the map Activity, so they pause if you background the app — a future improvement is to move them to a dedicated foreground service of type `dataSync`, which would re-introduce that permission.)

## CI

GitHub Actions runs unit tests and builds a debug APK on every PR. Tests cover UTM conversions, tile coverage, storage migration, transient tile store, scale bar math, location parsers, storage manager singleton, offline region download planning, and the grid renderer.

A separate `Release AAB` workflow runs on tag pushes (`v*`) and on manual dispatch, producing a signed `app-release.aab` for Play uploads. Signing keys come from GitHub Actions secrets (`AUSTOPO_KEYSTORE_BASE64`, `AUSTOPO_KEYSTORE_PASSWORD`, `AUSTOPO_KEY_ALIAS`, `AUSTOPO_KEY_PASSWORD`); if no keystore secret is set, the workflow falls back to the debug keystore (still produces an AAB, just not Play-uploadable).

# Data sources & licences

This is the canonical record of what each upstream provider permits. It exists for two reasons: (1) we are legally obliged to credit and respect each licence; (2) Google Play policy refuses apps that breach third-party terms, so this needs to be auditable before any store submission.

Every entry is sourced from the provider's published terms — links given. Where a row says **email confirmation pending**, the published terms don't unambiguously cover third-party mobile apps and a direct enquiry is needed before promoting any AAB to Play production.

| State | Endpoint | Licence | Status |
| --- | --- | --- | --- |
| NSW | maps.six.nsw.gov.au · `NSW_Topo_Map/MapServer` | [CC BY 4.0](https://www.spatial.nsw.gov.au/copyright) | ✅ confirmed |
| VIC | emap.ffm.vic.gov.au · `mapscape_mercator/MapServer` | Vicmap data is generally [CC BY 4.0](https://www.land.vic.gov.au/) but the FFM-operated tile cache is not separately documented | ⚠ email confirmation pending |
| QLD | spatial-gis.information.qld.gov.au · `QldMap_Topo/MapServer` | [CC BY 4.0](https://www.qld.gov.au/legal/copyright) (Queensland Government default) | ✅ confirmed (per default policy) |
| SA | location.sa.gov.au · `Topographic_wmas/MapServer` | DataSA endorses CC BY 4.0 per dataset; this MapServer's specific licence statement is not separately published | ⚠ email confirmation pending |
| TAS | services.thelist.tas.gov.au · `Basemaps/Topographic/MapServer` | theLIST publishes a copyright/disclaimer notice but no per-service CC declaration | ⚠ email confirmation pending |
| NT, WA fallback | services.ga.gov.au · `Topographic_Base_Map/MapServer` | [CC BY 4.0](https://www.ga.gov.au/copyright) | ✅ confirmed |
| WA (live tiles only) | OpenTopoMap (a/b/c.tile.opentopomap.org) | OSM data [ODbL](https://www.openstreetmap.org/copyright); rendering [CC BY-SA 3.0](https://opentopomap.org/about) | ⚠ usage-policy email pending |

Required attribution strings (rendered in the bottom-right of the map view):

- **NSW** — *© State of New South Wales (Spatial Services, a business unit of the Department of Customer Service NSW). For current information go to spatial.nsw.gov.au.*
- **QLD** — *© State of Queensland*.
- **GA** — *© Commonwealth of Australia (Geoscience Australia)*.
- **OpenTopoMap (if WA stays on OTM)** — *Map data: © OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)*.

The current implementation renders a single shorter line listing every provider regardless of camera position; full per-provider attribution will need to expand into a tap-to-open dialog before Play submission.

### Outstanding ToS work before Play production

1. Email DEECA / Vicmap and Forest Fire Management Victoria asking whether the FFM-operated `emap.ffm.vic.gov.au` tile cache is covered by Vicmap's CC BY 4.0 stance, and confirm attribution wording.
2. Email DataSA / DEW SA confirming the licence on `location.sa.gov.au` Topographic_wmas, and confirm app use is permitted.
3. Email theLIST (DPIPWE) confirming licence and app use of `services.thelist.tas.gov.au` basemap tiles.
4. If WA stays on OpenTopoMap, email Stefan Erhardt / OpenTopoMap describing AusTopo's caching, no-bulk-fetch, identifying-`User-Agent` posture, and ask for explicit blessing. (If declined, the WA fetcher should switch to Geoscience Australia — same licence as the NT fallback.)

Replies should be filed under `docs/tos-correspondence/` (gitignored if they contain personal contact details).
