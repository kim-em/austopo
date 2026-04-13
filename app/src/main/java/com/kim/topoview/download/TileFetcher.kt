package com.kim.topoview.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches 256x256 tiles from the NSW ArcGIS tile server.
 * URL pattern: https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Topo_Map/MapServer/tile/{z}/{y}/{x}
 *
 * Uses OkHttp disk cache for offline use and performance.
 */
class TileFetcher(context: Context) {

    companion object {
        private const val BASE_URL =
            "https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Topo_Map/MapServer/tile"
        private const val CACHE_SIZE = 500L * 1024 * 1024  // 500 MB

        // ArcGIS LOD table for Web Mercator (levels 0-23)
        // Each level's resolution in meters/pixel
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

        // NSW service extent in Web Mercator
        const val NSW_MIN_X = 15519000.0   // ~139.5°E
        const val NSW_MAX_X = 17200000.0   // ~154.5°E
        const val NSW_MIN_Y = -4400000.0   // ~-37°S
        const val NSW_MAX_Y = -3100000.0   // ~-27°S

        // Web Mercator origin (top-left of the world)
        const val ORIGIN_X = -20037508.342789244
        const val ORIGIN_Y = 20037508.342789244
        const val TILE_SIZE = 256
    }

    private val client: OkHttpClient
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory tile cache (LRU-ish, limited size)
    private val memCache = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size > 100) {
                eldest?.value?.recycle()
                return true
            }
            return false
        }
    }

    // Tiles currently being fetched
    private val pendingFetches = mutableSetOf<String>()

    var onTileLoaded: (() -> Unit)? = null

    init {
        val cacheDir = File(context.cacheDir, "tiles")
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

    /** Get resolution (meters/pixel) for a given LOD level. */
    fun lodResolution(lod: Int): Double = LOD_RESOLUTIONS[lod.coerceIn(0, LOD_RESOLUTIONS.size - 1)]

    /** Check if a Mercator point is within the NSW service extent. */
    fun isInNswExtent(mx: Double, my: Double): Boolean {
        return mx in NSW_MIN_X..NSW_MAX_X && my in NSW_MIN_Y..NSW_MAX_Y
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

    /** Get a cached tile bitmap, or null if not cached. Starts async fetch if not available. */
    fun getTile(lod: Int, col: Int, row: Int): Bitmap? {
        val key = "$lod/$row/$col"
        synchronized(memCache) {
            memCache[key]?.let { return it }
        }

        // Start async fetch if not already pending
        synchronized(pendingFetches) {
            if (key in pendingFetches) return null
            pendingFetches.add(key)
        }

        scope.launch {
            try {
                val url = "$BASE_URL/$lod/$row/$col"
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
                }
                response.close()
            } catch (_: Exception) {
                // Network error — will retry on next draw
            } finally {
                synchronized(pendingFetches) {
                    pendingFetches.remove(key)
                }
            }
        }

        return null
    }

    fun recycle() {
        scope.cancel()
        synchronized(memCache) {
            memCache.values.forEach { it.recycle() }
            memCache.clear()
        }
    }
}
