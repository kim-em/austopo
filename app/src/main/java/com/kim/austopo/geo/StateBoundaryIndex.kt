package com.kim.austopo.geo

import android.content.Context
import com.kim.austopo.download.TileFetcher
import com.kim.austopo.render.TileServerRenderer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Determines which Australian state/territory a map tile belongs to, using
 * actual state boundary polygons (not just rectangular extents).
 *
 * Loaded once from `assets/state_boundaries_wm.json` (Web Mercator coords).
 * Tile ownership is cached by `lod/col/row` key so point-in-polygon only
 * runs once per tile across all renderers and frames.
 */
class StateBoundaryIndex private constructor(
    private val states: Map<String, List<DoubleArray>>  // stateId -> list of polygon rings (flat x,y,x,y,...)
) {

    companion object {
        @Volatile private var instance: StateBoundaryIndex? = null

        fun get(context: Context): StateBoundaryIndex {
            return instance ?: synchronized(this) {
                instance ?: load(context).also { instance = it }
            }
        }

        private fun load(context: Context): StateBoundaryIndex {
            val json = context.assets.open("state_boundaries_wm.json").use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
            val root = JSONObject(json)
            val states = mutableMapOf<String, List<DoubleArray>>()
            for (stateId in root.keys()) {
                val polygonsJson = root.getJSONArray(stateId)
                val polygons = mutableListOf<DoubleArray>()
                for (i in 0 until polygonsJson.length()) {
                    val ring = polygonsJson.getJSONArray(i)
                    val coords = DoubleArray(ring.length() * 2)
                    for (j in 0 until ring.length()) {
                        val pt = ring.getJSONArray(j)
                        coords[j * 2] = pt.getDouble(0)      // x (Web Mercator)
                        coords[j * 2 + 1] = pt.getDouble(1)   // y (Web Mercator)
                    }
                    polygons.add(coords)
                }
                states[stateId] = polygons
            }
            return StateBoundaryIndex(states)
        }
    }

    // Cache: "lod/col/row" -> stateId (or "" for no match)
    private val cache = HashMap<Long, String>(2048)

    private fun cacheKey(lod: Int, col: Int, row: Int): Long =
        (lod.toLong() shl 40) or (col.toLong() and 0xFFFFF shl 20) or (row.toLong() and 0xFFFFF)

    /**
     * Priority list for border tiles (tiles whose corners fall in different
     * states). The first state in this list that appears in the candidate set
     * wins. States not listed here fall back to the first candidate.
     */
    private val borderPriority = listOf("VIC")
    // VIC > NSW: VIC Mapscape has full basemap coverage at the border,
    // while NSW shows VIC territory as blank white.

    /**
     * The renderers, set once at startup. Used to check which states can
     * actually render a tile (their rectangular extent covers it).
     */
    var renderers: List<TileServerRenderer> = emptyList()

    /**
     * Which state owns this tile? Returns a state ID like "NSW", "VIC", etc.
     * Returns null if no corner falls within any state polygon (ocean tiles).
     *
     * - If all 4 corners agree: that state owns the tile.
     * - If corners disagree (border tile): the highest-priority state from
     *   [borderPriority] that can actually render the tile wins.
     * - A state can only own a tile if its renderer's rectangular extent
     *   covers the tile (otherwise the renderer would never enumerate it,
     *   producing grey blocks).
     * - ACT is mapped to "NSW" since NSW's tile server covers it.
     */
    fun ownerForTile(lod: Int, col: Int, row: Int): String? {
        val key = cacheKey(lod, col, row)
        cache[key]?.let { return it.ifEmpty { null } }

        val bounds = TileFetcher.tileBoundsStatic(col, row, lod)
        val corners = arrayOf(
            findState(bounds[0], bounds[1]),  // SW
            findState(bounds[2], bounds[1]),  // SE
            findState(bounds[0], bounds[3]),  // NW
            findState(bounds[2], bounds[3])   // NE
        )
        val present = corners.filterNotNull().toSet()

        // Filter to only states that have a renderer whose extent covers
        // this tile. A state that "owns" a tile but can't render it is
        // useless — it would just produce a grey block.
        val canRender = if (renderers.isNotEmpty()) {
            present.filter { state ->
                renderers.any { r ->
                    r.tileFetcher.stateId == state &&
                    bounds[2] > r.tileFetcher.extentMinX &&
                    bounds[0] < r.tileFetcher.extentMaxX &&
                    bounds[3] > r.tileFetcher.extentMinY &&
                    bounds[1] < r.tileFetcher.extentMaxY
                }
            }.toSet()
        } else present

        val owner = when {
            canRender.isEmpty() -> null
            canRender.size == 1 -> canRender.first()
            else -> {
                borderPriority.firstOrNull { it in canRender } ?: canRender.first()
            }
        }
        cache[key] = owner ?: ""
        return owner
    }

    /** Find which state contains the given Web Mercator point. */
    fun findState(mx: Double, my: Double): String? {
        for ((stateId, polygons) in states) {
            for (ring in polygons) {
                if (pointInRing(mx, my, ring)) {
                    return if (stateId == "ACT") "NSW" else stateId
                }
            }
        }
        return null
    }

    /**
     * Ray-casting point-in-polygon test on a flat coordinate ring.
     * Ring is stored as [x0,y0, x1,y1, ...].
     */
    private fun pointInRing(px: Double, py: Double, ring: DoubleArray): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i * 2]; val yi = ring[i * 2 + 1]
            val xj = ring[j * 2]; val yj = ring[j * 2 + 1]
            if (((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
