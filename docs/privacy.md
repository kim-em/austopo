# AusTopo Privacy Policy

*Last updated: 18 April 2026*

AusTopo is a free, open-source Android app for viewing Australian topographic maps. It is developed by Kim Morrison and the source code is available at [github.com/kim-em/austopo](https://github.com/kim-em/austopo).

## What data does AusTopo access?

**Location (GPS).** When you choose "My Location" from the menu and grant permission, AusTopo reads your device's GPS position to centre the map on your location. Your location is processed entirely on-device and is never transmitted, stored, or shared by the app. However, **centering the map on your location causes AusTopo to fetch map tiles for that area from tile servers** (see below), which reveals that area's geographic coordinates to those servers.

**Tile requests.** Whenever you pan, zoom, or centre the map, AusTopo fetches map tile images over HTTPS from government-operated tile servers. Each tile request necessarily contains the geographic coordinates of the tile being fetched, your device's IP address, and a User-Agent string identifying the app (`AusTopo/<version>`). This is inherent to how web map tiles work — without sending coordinates, the server cannot return the correct tile. AusTopo does not add any tracking identifiers, cookies, or analytics parameters to these requests.

The tile servers AusTopo contacts and their operators' privacy policies:

- **NSW** — maps.six.nsw.gov.au, operated by NSW Spatial Services. [Privacy policy](https://www.spatial.nsw.gov.au/privacy).
- **VIC** — emap.ffm.vic.gov.au, operated by Forest Fire Management Victoria (DEECA). [Privacy policy](https://www.deeca.vic.gov.au/privacy).
- **QLD** — spatial-gis.information.qld.gov.au, operated by the Queensland Government. [Privacy statement](https://www.qld.gov.au/legal/privacy).
- **SA** — location.sa.gov.au, operated by the SA Department for Environment and Water. [Privacy statement](https://www.environment.sa.gov.au/privacy-statement).
- **TAS** — services.thelist.tas.gov.au, operated by the Tasmanian Government (theLIST). [Personal information protection](https://www.tas.gov.au/stds/pip.htm).
- **NT and WA** — services.ga.gov.au, operated by Geoscience Australia. [Privacy policy](https://www.ga.gov.au/privacy).

**Place search.** When you use "Search Place" from the menu, AusTopo sends your search query to the [OpenStreetMap Nominatim](https://nominatim.openstreetmap.org/) geocoding service over HTTPS. The request contains the search text you typed, your device's IP address, and a User-Agent string identifying the app. No location data, device identifiers, or other personal information is included. Nominatim is operated by the OpenStreetMap Foundation; their [privacy policy](https://osmfoundation.org/wiki/Privacy_Policy) and [usage policy](https://operations.osmfoundation.org/policies/nominatim/) apply to these requests.

**Bookmarks.** Saved bookmarks (place names and coordinates) are stored locally on your device in the app's private storage. They are not transmitted anywhere.

**Offline tiles.** Cached and pinned tiles are stored locally on your device. They are not transmitted anywhere.

## What data does AusTopo collect, share, or sell?

None. AusTopo has no analytics, no crash reporting, no advertising, no user accounts, and no server of its own. It does not collect, share, or sell any personal data.

## Third-party services

The only network requests AusTopo makes are HTTPS tile fetches to the government tile servers listed above and place search queries to OpenStreetMap Nominatim. AusTopo does not integrate any third-party SDKs, ad networks, or analytics services.

## Children's privacy

AusTopo does not knowingly collect any personal information from anyone, including children under 13.

## Changes to this policy

If this policy changes, the updated version will be published at this URL and the "Last updated" date will be revised.

## Contact

If you have questions about this policy, open an issue at [github.com/kim-em/austopo/issues](https://github.com/kim-em/austopo/issues) or email [kim@tqft.net](mailto:kim@tqft.net).
