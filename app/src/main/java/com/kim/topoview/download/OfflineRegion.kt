package com.kim.topoview.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A named offline region with its bounds and LOD range.
 */
data class OfflineRegion(
    val name: String,
    val minMX: Double,
    val minMY: Double,
    val maxMX: Double,
    val maxMY: Double,
    val lodMin: Int,
    val lodMax: Int,
    val cacheName: String,  // which tile source (tiles_nsw, tiles_vic)
    val tileCount: Int = 0,
    val sizeBytes: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("minMX", minMX)
        put("minMY", minMY)
        put("maxMX", maxMX)
        put("maxMY", maxMY)
        put("lodMin", lodMin)
        put("lodMax", lodMax)
        put("cacheName", cacheName)
        put("tileCount", tileCount)
        put("sizeBytes", sizeBytes)
    }

    companion object {
        fun fromJson(obj: JSONObject) = OfflineRegion(
            name = obj.getString("name"),
            minMX = obj.getDouble("minMX"),
            minMY = obj.getDouble("minMY"),
            maxMX = obj.getDouble("maxMX"),
            maxMY = obj.getDouble("maxMY"),
            lodMin = obj.getInt("lodMin"),
            lodMax = obj.getInt("lodMax"),
            cacheName = obj.getString("cacheName"),
            tileCount = obj.optInt("tileCount", 0),
            sizeBytes = obj.optLong("sizeBytes", 0)
        )
    }
}

/**
 * Persists the list of saved offline regions.
 */
class OfflineRegionStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "offline_regions.json")

    fun load(): List<OfflineRegion> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { OfflineRegion.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(regions: List<OfflineRegion>) {
        val arr = JSONArray()
        regions.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun add(region: OfflineRegion) {
        val regions = load().toMutableList()
        regions.add(region)
        save(regions)
    }

    fun remove(name: String) {
        val regions = load().filter { it.name != name }
        save(regions)
    }
}
