# AusTopo Privacy Policy

*Last updated: 17 April 2026*

AusTopo is a free, open-source Android app for viewing Australian topographic maps. It is developed by Kim Morrison and the source code is available at [github.com/kim-em/austopo](https://github.com/kim-em/austopo).

## What data does AusTopo access?

**Location (GPS).** When you tap the Loc button and grant permission, AusTopo reads your device's GPS position to centre the map on your location. Your location is used entirely on-device and is never transmitted, stored, or shared by the app.

**Tile requests.** When you pan or zoom the map, AusTopo fetches map tile images over HTTPS from government-operated tile servers (NSW Spatial Services, Vicmap, QSpatial, DEW SA, theLIST, Geoscience Australia). These requests contain the geographic coordinates of the tiles being viewed, your device's IP address, and a User-Agent string identifying the app. This is inherent to how web map tiles work. AusTopo does not add any tracking identifiers, cookies, or analytics parameters to these requests. The privacy policies of the respective state and Commonwealth agencies govern how they handle server-side logs.

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
