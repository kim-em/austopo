package com.kim.austopo.data

import android.content.Context
import android.content.SharedPreferences
import com.kim.austopo.MapMetadata
import com.kim.austopo.util.MapsDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MapSheetRepository(private val context: Context) {

    private val sheets = mutableListOf<MapSheet>()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("austopo_sheets", Context.MODE_PRIVATE)

    private val mapsDir: File
        get() = MapsDir.forContext(context)

    fun getSheets(): List<MapSheet> = sheets

    /** Load sheets from all available sources. */
    fun loadAll() {
        sheets.clear()
        loadLocalSheets()
        loadNswIndex()
        loadBundledIndices()
    }

    /** Scan /sdcard/TopoMaps/ for locally available sheets. */
    private fun loadLocalSheets() {
        val metas = MapMetadata.scanDirectory(mapsDir)
        for (meta in metas) {
            val jsonFile = mapsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.firstOrNull { MapMetadata.fromJsonFile(it)?.name == meta.name }
                ?: continue

            val id = "local:${meta.name.lowercase().replace(" ", "-")}"
            val bbox = com.kim.austopo.CoordinateConverter.metadataBboxMercator(meta)
            val (minLat, minLon) = com.kim.austopo.CoordinateConverter.webMercatorToWgs84(bbox[0], bbox[1])
            val (maxLat, maxLon) = com.kim.austopo.CoordinateConverter.webMercatorToWgs84(bbox[2], bbox[3])

            val kept = prefs.getBoolean("kept:$id", false)
            sheets.add(MapSheet(
                id = id,
                name = meta.name,
                source = "local",
                scale = 25000,
                minLon = minLon,
                minLat = minLat,
                maxLon = maxLon,
                maxLat = maxLat,
                status = if (kept) SheetStatus.KEPT else SheetStatus.CACHED,
                localPath = jsonFile.absolutePath
            ))
        }
    }

    /** Load NSW sheet index from cached JSON (populated by NswIndexSyncer). */
    private fun loadNswIndex() {
        val cacheFile = File(context.filesDir, "nsw_sheets.json")
        if (!cacheFile.exists()) return

        try {
            val jsonArray = JSONArray(cacheFile.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = "nsw:${obj.getString("tileid")}"
                // Skip if we already have this sheet from local
                if (sheets.any { it.id == id }) continue

                val sheet = parseNswSheet(obj)
                if (sheet != null) sheets.add(sheet)
            }
        } catch (_: Exception) {}
    }

    private fun parseNswSheet(obj: JSONObject): MapSheet? {
        return try {
            val tileId = obj.getString("tileid")
            val name = obj.optString("tilename", tileId)
            val downloadUrl = obj.optString("collarof_51", null)

            // Parse geometry rings
            val geom = obj.optJSONObject("geometry")
            var polygon: List<Pair<Double, Double>>? = null
            var minLon = Double.MAX_VALUE
            var minLat = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE

            if (geom != null) {
                val rings = geom.getJSONArray("rings")
                if (rings.length() > 0) {
                    val ring = rings.getJSONArray(0)
                    val pts = mutableListOf<Pair<Double, Double>>()
                    for (j in 0 until ring.length()) {
                        val pt = ring.getJSONArray(j)
                        val lon = pt.getDouble(0)
                        val lat = pt.getDouble(1)
                        pts.add(Pair(lon, lat))
                        minLon = minOf(minLon, lon)
                        minLat = minOf(minLat, lat)
                        maxLon = maxOf(maxLon, lon)
                        maxLat = maxOf(maxLat, lat)
                    }
                    polygon = pts
                }
            } else {
                minLon = obj.getDouble("minLon")
                minLat = obj.getDouble("minLat")
                maxLon = obj.getDouble("maxLon")
                maxLat = obj.getDouble("maxLat")
            }

            MapSheet(
                id = "nsw:$tileId",
                name = name,
                source = "nsw",
                scale = 25000,
                minLon = minLon,
                minLat = minLat,
                maxLon = maxLon,
                maxLat = maxLat,
                downloadUrl = downloadUrl,
                polygon = polygon
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Load bundled Getlost index JSONs from assets. */
    private fun loadBundledIndices() {
        val assetFiles = try {
            context.assets.list("")?.filter { it.startsWith("getlost_") && it.endsWith(".json") }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        for (filename in assetFiles) {
            try {
                val json = context.assets.open(filename).bufferedReader().readText()
                val arr = JSONArray(json)
                val source = filename.removeSuffix(".json")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = "${source}:${obj.getString("id")}"
                    if (sheets.any { it.id == id }) continue

                    sheets.add(MapSheet(
                        id = id,
                        name = obj.getString("name"),
                        source = source,
                        scale = obj.optInt("scale", 25000),
                        minLon = obj.getDouble("minLon"),
                        minLat = obj.getDouble("minLat"),
                        maxLon = obj.getDouble("maxLon"),
                        maxLat = obj.getDouble("maxLat"),
                        downloadUrl = obj.optString("downloadUrl", null)
                    ))
                }
            } catch (_: Exception) {}
        }
    }

    /** Find sheets whose bbox intersects a Mercator viewport. */
    fun sheetsInView(minMX: Double, minMY: Double, maxMX: Double, maxMY: Double): List<MapSheet> {
        return sheets.filter { sheet ->
            val b = sheet.bboxMercator
            b[0] < maxMX && b[2] > minMX && b[1] < maxMY && b[3] > minMY
        }
    }

    /** Find sheets containing a GPS position. */
    fun sheetsAtLocation(lat: Double, lon: Double): List<MapSheet> {
        return sheets.filter { it.containsWgs84(lat, lon) }
    }

    fun setKept(sheet: MapSheet, kept: Boolean) {
        sheet.status = if (kept) SheetStatus.KEPT else SheetStatus.CACHED
        prefs.edit().putBoolean("kept:${sheet.id}", kept).apply()
    }

    fun findById(id: String): MapSheet? = sheets.firstOrNull { it.id == id }
}
