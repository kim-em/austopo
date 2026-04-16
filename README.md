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

In-layout translucent overlay toolbar (no ActionBar): Loc, Bkm, Save, Rgns, Cache, Sync, Show/Hide sheets, Grid. Auto-hides on pan past the touch slop and restores on tap.

## Geodesy

Zone-explicit UTM/MGA conversions using the GRS80 ellipsoid via the Redfern series, sub-10 cm accurate within a zone — well under the 1 km grid granularity. Canonical `TileCoverage` type for describing tile sets across LODs.

## Permissions

- `INTERNET` — tile fetches.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — "Loc" button.
- `FOREGROUND_SERVICE` — long-running offline-region downloads.

No external storage permissions. No legacy storage. No "All files access" prompt.

## CI

GitHub Actions runs unit tests and builds a debug APK on every PR. Tests cover UTM conversions, tile coverage, storage migration, transient tile store, scale bar math, location parsers, storage manager singleton, offline region download planning, and the grid renderer.
