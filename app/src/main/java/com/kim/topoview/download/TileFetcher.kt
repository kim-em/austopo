package com.kim.topoview.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches 256x256 tiles from an ArcGIS tile server.
 * Uses OkHttp disk cache for offline use and performance.
 */
class TileFetcher(
    context: Context,
    private val baseUrl: String,
    val extentMinX: Double,
    val extentMaxX: Double,
    val extentMinY: Double,
    val extentMaxY: Double,
    val minLod: Int = 6,
    cacheName: String = "tiles"
) {

    companion object {
        private const val CACHE_SIZE = 500L * 1024 * 1024  // 500 MB
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

        fun nsw(context: Context) = TileFetcher(
            context,
            baseUrl = "https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Topo_Map/MapServer/tile",
            extentMinX = 15519000.0,   // ~139.5°E
            extentMaxX = 17200000.0,   // ~154.5°E
            extentMinY = -4400000.0,   // ~-37°S
            extentMaxY = -3100000.0,   // ~-27°S
            cacheName = "tiles_nsw"
        )

        fun vic(context: Context) = TileFetcher(
            context,
            baseUrl = "https://emap.ffm.vic.gov.au/arcgis/rest/services/mapscape_mercator/MapServer/tile",
            extentMinX = 15688000.0,   // ~141°E
            extentMaxX = 16693000.0,   // ~150°E
            extentMinY = -4750000.0,   // ~-39.2°S
            extentMaxY = -4020000.0,   // ~-34°S
            cacheName = "tiles_vic"
        )
    }

    private val client: OkHttpClient
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    val tileCacheName: String = cacheName

    // Offline tile store — checked before network fetches
    var offlineStore: OfflineTileStore? = null

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

    init {
        val cacheDir = File(context.cacheDir, cacheName)
        val cache = Cache(cacheDir, CACHE_SIZE)
        client = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Find the best LOD level for the current camera zoom (meters per pixel). */
    fun bestLod(metersPerPixel: Double): Int {
        for (i in LOD_RESOLUTIONS.indices) {
            if (LOD_RESOLUTIONS[i] <= metersPerPixel) {
                return i
            }
        }
        return LOD_RESOLUTIONS.size - 1
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

        // Check offline store
        offlineStore?.loadTile(tileCacheName, lod, col, row)?.let { bitmap ->
            synchronized(memCache) {
                memCache[key] = bitmap
            }
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
                val url = "$baseUrl/$lod/$row/$col"
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
