# AusTopo Privacy Policy

*Last updated: 17 April 2026*

AusTopo is a free, open-source Android app for viewing Australian topographic maps. It is developed by Kim Morrison and the source code is available at [github.com/kim-em/austopo](https://github.com/kim-em/austopo).

## What data does AusTopo access?

**Location (GPS).** When you choose "My Location" from the menu and grant permission, AusTopo reads your device's GPS position to centre the map on your location. Your location is processed entirely on-device and is never transmitted, stored, or shared by the app. However, **centering the map on your location causes AusTopo to fetch map tiles for that area from tile servers** (see below), which reveals that area's geographic coordinates to those servers.

**Tile requests.** Whenever you pan, zoom, or centre the map, AusTopo fetches map tile images over HTTPS from government-operated tile servers. Each tile request necessarily contains the geographic coordinates of the tile being fetched, your device's IP address, and a User-Agent string identifying the app (`AusTopo/<version>`). This is inherent to how web map tiles work — without sending coordinates, the server cannot return the correct tile. AusTopo does not add any tracking identifiers, cookies, or analytics parameters to these requests.

The tile servers AusTopo contacts and their operators' privacy policies:

- **NSW** — maps.six.nsw.gov.au, operated by NSW Spatial Services. [Privacy policy](https://www.spatial.nsw.gov.au/privacy).
- **VIC** — emap.ffm.vic.gov.au, operated by Forest Fire Management Victoria (DEECA). Privacy governed by the Victorian Government's [Privacy and Data Protection Act 2014](https://www.legislation.vic.gov.au/in-force/acts/privacy-and-data-protection-act-2014).
- **QLD** — spatial-gis.information.qld.gov.au, operated by the Queensland Government. [Privacy statement](https://www.qld.gov.au/legal/privacy).
- **SA** — location.sa.gov.au, operated by the SA Department for Environment and Water. Privacy governed by the SA Government's [Information Privacy Principles](https://archives.sa.gov.au/managing-information/privacy-personal-information/information-privacy-principles-ipps-instruction).
- **TAS** — services.thelist.tas.gov.au, operated by the Tasmanian Government (theLIST). Privacy governed by the Tasmanian Government's [Personal Information Protection Act 2004](https://www.legislation.tas.gov.au/view/html/inforce/current/act-2004-046).
- **NT and WA** — services.ga.gov.au, operated by Geoscience Australia. [Privacy policy](https://www.ga.gov.au/privacy).

**Bookmarks.** Saved bookmarks (place names and coordinates) are stored locally on your device in the app's private storage. They are not transmitted anywhere.

**Offline tiles.** Cached and pinned tiles are stored locally on your device. They are not transmitted anywhere.

## What data does AusTopo collect, share, or sell?

None. AusTopo has no analytics, no crash reporting, no advertising, no user accounts, and no server of its own. It does not collect, share, or sell any personal data.

## Third-party services

The only network requests AusTopo makes are HTTPS tile fetches to the government tile servers listed above. AusTopo does not integrate any third-party SDKs, ad networks, or analytics services.

## Children's privacy

AusTopo does not knowingly collect any personal information from anyone, including children under 13.

## Changes to this policy

If this policy changes, the updated version will be published at this URL and the "Last updated" date will be revised.

## Contact

If you have questions about this policy, open an issue at [github.com/kim-em/austopo/issues](https://github.com/kim-em/austopo/issues) or email [kim@tqft.net](mailto:kim@tqft.net).
