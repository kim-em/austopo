package com.kim.austopo.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches 256x256 tiles from an ArcGIS tile server.
 *
 * Reads: check in-memory LRU, then [pinnedStore], then [transientStore].
 * If all miss, kick off an async network fetch that writes the raw response
 * bytes to [transientStore] (under [storage]'s lock) and decodes to a Bitmap
 * for the in-memory cache. The caller gets null on that pass and will
 * re-check on the next draw after [onTileLoaded] fires.
 */
class TileFetcher(
    val baseUrl: String,
    val extentMinX: Double,
    val extentMaxX: Double,
    val extentMinY: Double,
    val extentMaxY: Double,
    val minLod: Int = 6,
    val maxLod: Int = 23,
    cacheName: String = "tiles",
    /** Contrast multiplier for tile rendering (1.0 = no change). */
    val contrast: Float = 1.0f
) {

    /** Full URL for a single tile (ArcGIS REST cached tile service). */
    fun tileUrl(lod: Int, col: Int, row: Int): String = "$baseUrl/$lod/$row/$col"

    companion object {
        private const val RETRY_DELAY_MS = 2000L

        // ArcGIS LOD table for Web Mercator (levels 0-23)
        val LOD_RESOLUTIONS = doubleArrayOf(
            156543.03392800014,  // 0
            78271.51696399994,   // 1
            39135.75848200009,   // 2
            19567.87924099992,   // 3
            9783.93962049996,    // 4
            4891.96981024998,    // 5
            2445.98490512499,    // 6
            1222.992452562495,   // 7
            611.4962262813508,   // 8
            305.74811314055756,  // 9
            152.87405657041106,  // 10
            76.43702828507324,   // 11
            38.21851414253662,   // 12
            19.10925707126831,   // 13
            9.554628535634155,   // 14
            4.77731426794937,    // 15
            2.388657133974685,   // 16
            1.1943285668550503,  // 17
            0.5971642835598172,  // 18
            0.29858214164761665, // 19
            0.14929107082380833, // 20
            0.07464553541190416, // 21
            0.03732276770595208, // 22
            0.01866138385297604  // 23
        )

        // Web Mercator origin (top-left of the world)
        const val ORIGIN_X = -20037508.342789244
        const val ORIGIN_Y = 20037508.342789244
        const val TILE_SIZE = 256

        // maxLod values below come from each server's published tileInfo.lods
        // array (MapServer?f=json). Setting them stops bestLod from requesting
        // tiles the server doesn't cache — at higher camera zoom the view just
        // upsamples the maxLod tile instead of showing grey.

        fun nsw() = TileFetcher(
            baseUrl = "https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Topo_Map/MapServer/tile",
            extentMinX = 15519000.0,   // ~139.5°E
            extentMaxX = 17200000.0,   // ~154.5°E
            extentMinY = -4400000.0,   // ~-37°S
            extentMaxY = -3100000.0,   // ~-27°S
            maxLod = 21,
            cacheName = "tiles_nsw"
        )

        /**
         * VIC — FFM Mapscape topographic basemap.
         *
         * Mapscape is a rendered basemap product by Spatial Vision / Veris,
         * licensed to Victorian emergency services. No public open licence
         * has been found; the ArcGIS metadata has empty copyrightText.
         * Email vicmap@transport.vic.gov.au to confirm terms before any
         * Play Store publication.
         */
        fun vic() = TileFetcher(
            baseUrl = "https://emap.ffm.vic.gov.au/arcgis/rest/services/mapscape_mercator/MapServer/tile",
            extentMinX = 15688000.0,   // ~141°E
            extentMaxX = 16693000.0,   // ~150°E
            extentMinY = -4750000.0,   // ~-39.2°S
            extentMaxY = -4020000.0,   // ~-34°S
            maxLod = 23,
            cacheName = "tiles_vic",
            contrast = 1.3f   // faint at highest zooms
        )

        fun qld() = TileFetcher(
            baseUrl = "https://spatial-gis.information.qld.gov.au/arcgis/rest/services/Basemaps/QldMap_Topo/MapServer/tile",
            extentMinX = 15360000.0,   // ~138°E
            extentMaxX = 17150000.0,   // ~154°E
            extentMinY = -3380000.0,   // ~-29°S
            extentMaxY = -1120000.0,   // ~-10°S
            maxLod = 23,
            cacheName = "tiles_qld"
        )

        fun sa() = TileFetcher(
            baseUrl = "https://location.sa.gov.au/arcgis/rest/services/BaseMaps/Topographic_wmas/MapServer/tile",
            extentMinX = 14360000.0,   // ~129°E
            extentMaxX = 15700000.0,   // ~141°E
            extentMinY = -4585000.0,   // ~-38°S
            extentMaxY = -2990000.0,   // ~-26°S
            maxLod = 20,
            cacheName = "tiles_sa",
            contrast = 1.4f   // thin lines, low contrast
        )

        fun tas() = TileFetcher(
            baseUrl = "https://services.thelist.tas.gov.au/arcgis/rest/services/Basemaps/Topographic/MapServer/tile",
            extentMinX = 16030000.0,   // ~144°E
            extentMaxX = 16585000.0,   // ~149°E
            extentMinY = -5432000.0,   // ~-43.7°S
            extentMaxY = -4800000.0,   // ~-39.6°S
            maxLod = 18,
            cacheName = "tiles_tas",
            contrast = 1.4f   // thin lines, low contrast
        )

        /**
         * NT and WA: neither state publishes a free public topo tile server
         * (Landgate's WA basemap is paywalled behind SLIP), so both fall back
         * to Geoscience Australia's national topographic base map (CC BY 4.0,
         * © Commonwealth of Australia / Geoscience Australia). Same ArcGIS
         * REST tile scheme as the state services; full LOD range cached.
         */
        fun nt() = TileFetcher(
            baseUrl = "https://services.ga.gov.au/gis/rest/services/Topographic_Base_Map/MapServer/tile",
            extentMinX = 14360000.0,   // ~129°E (NT/WA border)
            extentMaxX = 15365000.0,   // ~138°E (NT/QLD border)
            extentMinY = -2990000.0,   // ~-26°S (NT/SA border)
            extentMaxY = -1170000.0,   // ~-10.5°S (Tiwi Islands)
            maxLod = 23,
            cacheName = "tiles_nt"
        )

        fun wa() = TileFetcher(
            baseUrl = "https://services.ga.gov.au/gis/rest/services/Topographic_Base_Map/MapServer/tile",
            extentMinX = 12575000.0,   // ~113°E (west coast)
            extentMaxX = 14360000.0,   // ~129°E (WA/NT/SA border)
            extentMinY = -4165000.0,   // ~-35°S (south coast)
            extentMaxY = -1460000.0,   // ~-13°S (Kimberley)
            maxLod = 23,
            cacheName = "tiles_wa"
        )
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    val tileCacheName: String = cacheName

    // Storage — checked before network fetches; network successes write to transient.
    var storage: StorageManager? = null
    var pinnedStore: PinnedTileStore? = null
    var transientStore: TransientTileStore? = null
    /** Called after a transient write if the cap has been configured. */
    var onTransientWrite: (() -> Unit)? = null

    // Main LRU cache
    private val memCache = object : LinkedHashMap<String, Bitmap>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size > 400) {
                val key = eldest?.key ?: return false
                // Don't evict pinned tiles
                if (key in pinnedKeys) return false
                eldest.value?.recycle()
                return true
            }
            return false
        }
    }

    // Pinned tile keys — tiles from the last visible LOD that must survive eviction
    private val pinnedKeys = mutableSetOf<String>()

    // Tiles currently being fetched
    private val pendingFetches = mutableSetOf<String>()
    // Failed tiles — don't retry immediately
    private val failedFetches = mutableSetOf<String>()

    var onTileLoaded: (() -> Unit)? = null

    /** Find the best LOD level for the current camera zoom (meters per pixel). */
    fun bestLod(metersPerPixel: Double): Int {
        // Pick the finest LOD whose resolution is at most 75% of the camera's
        // meters-per-pixel. Without the 0.75 factor we'd switch to the next
        // LOD as soon as the camera barely crosses the threshold, loading 4x
        // more tiles before the extra detail is visually useful.
        val threshold = metersPerPixel * 0.75
        for (i in LOD_RESOLUTIONS.indices) {
            if (LOD_RESOLUTIONS[i] <= threshold) {
                return i.coerceAtMost(maxLod)
            }
        }
        return (LOD_RESOLUTIONS.size - 1).coerceAtMost(maxLod)
    }

    /**
     * Pick LOD with hysteresis: prefer staying on currentLod unless the ideal LOD
     * is more than ~40% away from the current resolution.
     */
    fun bestLodWithHysteresis(metersPerPixel: Double, currentLod: Int): Int {
        val ideal = bestLod(metersPerPixel)
        if (currentLod < 0) return ideal
        if (ideal == currentLod) return ideal
        // Only switch if the camera resolution is clearly past the midpoint
        // between current LOD and the next one
        val currentRes = LOD_RESOLUTIONS.getOrElse(currentLod) { return ideal }
        val threshold = if (ideal > currentLod) {
            // Zooming in: switch when past 60% of the way to the next level
            currentRes * 0.4
        } else {
            // Zooming out: switch when past 60% of the way back
            currentRes * 2.5
        }
        return if (ideal > currentLod && metersPerPixel < threshold) ideal
        else if (ideal < currentLod && metersPerPixel > threshold) ideal
        else currentLod
    }

    /** Get resolution (meters/pixel) for a given LOD level. */
    fun lodResolution(lod: Int): Double = LOD_RESOLUTIONS[lod.coerceIn(0, LOD_RESOLUTIONS.size - 1)]

    /** Check if a Mercator point is within the service extent. */
    fun isInExtent(mx: Double, my: Double): Boolean {
        return mx in extentMinX..extentMaxX && my in extentMinY..extentMaxY
    }

    /**
     * Get tile column and row for a Web Mercator position at a given LOD.
     * Returns (col, row).
     */
    fun tileForMercator(mx: Double, my: Double, lod: Int): Pair<Int, Int> {
        val res = lodResolution(lod)
        val col = ((mx - ORIGIN_X) / (res * TILE_SIZE)).toInt()
        val row = ((ORIGIN_Y - my) / (res * TILE_SIZE)).toInt()
        return Pair(col, row)
    }

    /** Get the Web Mercator bounds of a tile: (minX, minY, maxX, maxY). */
    fun tileBounds(col: Int, row: Int, lod: Int): DoubleArray {
        val res = lodResolution(lod)
        val tileSize = res * TILE_SIZE
        val minX = ORIGIN_X + col * tileSize
        val maxX = minX + tileSize
        val maxY = ORIGIN_Y - row * tileSize
        val minY = maxY - tileSize
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    /** Check cache only — no fetch. Returns bitmap if in memory cache, null otherwise. */
    fun peekTile(lod: Int, col: Int, row: Int): Bitmap? {
        val key = "$lod/$row/$col"
        synchronized(memCache) {
            return memCache[key]
        }
    }

    /** Number of tiles currently being fetched. */
    fun pendingCount(): Int = synchronized(pendingFetches) { pendingFetches.size }

    /**
     * Pin a set of tile keys so they won't be evicted from the LRU cache.
     * Call this with the tile keys from the last fully-rendered LOD.
     */
    fun pinTiles(keys: Set<String>) {
        synchronized(memCache) {
            pinnedKeys.clear()
            pinnedKeys.addAll(keys)
        }
    }

    /** Get a cached tile bitmap, or null if not cached. Starts async fetch if not available. */
    fun getTile(lod: Int, col: Int, row: Int): Bitmap? {
        val key = "$lod/$row/$col"
        synchronized(memCache) {
            memCache[key]?.let { return it }
        }

        // Check pinned store first (saved offline regions) then transient (recent fetches).
        pinnedStore?.loadTile(tileCacheName, lod, col, row)?.let { bitmap ->
            synchronized(memCache) { memCache[key] = bitmap }
            return bitmap
        }
        transientStore?.loadTile(tileCacheName, lod, col, row)?.let { bitmap ->
            synchronized(memCache) { memCache[key] = bitmap }
            return bitmap
        }

        // Don't retry recently failed tiles (they'll be cleared after RETRY_DELAY_MS)
        synchronized(failedFetches) {
            if (key in failedFetches) return null
        }

        // Start async fetch if not already pending
        synchronized(pendingFetches) {
            if (key in pendingFetches) return null
            pendingFetches.add(key)
        }

        scope.launch {
            try {
                val url = tileUrl(lod, col, row)
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            synchronized(memCache) {
                                memCache[key] = bitmap
                            }
                            // Persist to transient store so the tile survives app restart
                            // and future panning over the same area is instant.
                            val s = storage
                            val t = transientStore
                            if (s != null && t != null) {
                                s.withLock {
                                    t.write(tileCacheName, lod, col, row, bytes)
                                }
                                onTransientWrite?.invoke()
                            }
                            withContext(Dispatchers.Main) {
                                onTileLoaded?.invoke()
                            }
                        }
                    }
                } else {
                    // HTTP error — mark as failed, schedule retry
                    markFailedAndRetry(key)
                }
                response.close()
            } catch (_: Exception) {
                // Network error — mark as failed, schedule retry
                markFailedAndRetry(key)
            } finally {
                synchronized(pendingFetches) {
                    pendingFetches.remove(key)
                }
            }
        }

        return null
    }

    private fun markFailedAndRetry(key: String) {
        synchronized(failedFetches) {
            failedFetches.add(key)
        }
        // Clear failure flag after delay so it can be retried, and trigger redraw
        mainHandler.postDelayed({
            synchronized(failedFetches) {
                failedFetches.remove(key)
            }
            onTileLoaded?.invoke()
        }, RETRY_DELAY_MS)
    }

    fun recycle() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(memCache) {
            pinnedKeys.clear()
            memCache.values.forEach { it.recycle() }
            memCache.clear()
        }
    }
}
