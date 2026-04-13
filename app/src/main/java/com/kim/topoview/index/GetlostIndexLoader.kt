package com.kim.topoview.index

import android.content.Context
import com.kim.topoview.data.SheetGridCalculator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads Getlost Maps sheet indices from:
 * 1. Bundled asset JSON files (getlost_vic_25k.json, etc.)
 * 2. Catalogue cache from download_sheet.py (~/.cache/getlost-maps/)
 *
 * If no bundled asset exists, generates a basic index from the catalogue cache
 * using the sheet grid calculator.
 */
class GetlostIndexLoader(private val context: Context) {

    /**
     * Generate an index JSON from the getlost-maps catalogue cache.
     * Parses filenames like "8224-3_SomeName_GetlostMap_V16b.tif" to extract
     * sheet numbers and names, then calculates bounding boxes.
     */
    fun generateIndexFromCache(catalogueId: String): JSONArray {
        val cacheFile = File(
            System.getenv("HOME") ?: "/sdcard",
            ".cache/getlost-maps/$catalogueId.json"
        )
        if (!cacheFile.exists()) return JSONArray()

        val entries = JSONArray()
        try {
            val catalogue = JSONArray(cacheFile.readText())
            for (i in 0 until catalogue.length()) {
                val item = catalogue.getJSONObject(i)
                val filename = item.getString("name")
                val fileId = item.getString("id")

                val parsed = parseGetlostFilename(filename) ?: continue
                val bbox = SheetGridCalculator.sheetBbox(parsed.sheetNumber) ?: continue

                val entry = JSONObject()
                entry.put("id", parsed.sheetNumber)
                entry.put("name", parsed.name)
                entry.put("scale", 25000)
                entry.put("minLon", bbox[0])
                entry.put("minLat", bbox[1])
                entry.put("maxLon", bbox[2])
                entry.put("maxLat", bbox[3])
                entry.put("downloadUrl", "gdrive:$fileId")
                entries.put(entry)
            }
        } catch (_: Exception) {}

        return entries
    }

    private data class ParsedFilename(val sheetNumber: String, val name: String)

    private fun parseGetlostFilename(filename: String): ParsedFilename? {
        // Expected: "8224-3_SomeName_GetlostMap_V16b.tif"
        // or: "8224-3_Some Name Here_GetlostMap_V15.tif"
        val base = filename.removeSuffix(".tif").removeSuffix(".TIF")
        val parts = base.split("_GetlostMap_", "_Getlost_", limit = 2)
        if (parts.isEmpty()) return null

        val prefix = parts[0]
        // Split into sheet number and name
        val dashIdx = prefix.indexOf('-')
        if (dashIdx == -1) return null

        // Find where the sheet number ends (digits and dash, possibly N/S suffix)
        val sheetRegex = Regex("""^(\d{4}-\d[NS]?)[\s_](.+)$""")
        val match = sheetRegex.find(prefix)
        if (match != null) {
            return ParsedFilename(
                sheetNumber = match.groupValues[1],
                name = match.groupValues[2].replace("_", " ").trim()
            )
        }

        // Fallback: try splitting on first underscore after the dash
        val underscoreIdx = prefix.indexOf('_', dashIdx)
        if (underscoreIdx != -1) {
            return ParsedFilename(
                sheetNumber = prefix.substring(0, underscoreIdx),
                name = prefix.substring(underscoreIdx + 1).replace("_", " ").trim()
            )
        }

        return null
    }

    /** Write a generated index to internal storage for later loading by MapSheetRepository. */
    fun saveIndex(source: String, index: JSONArray) {
        val file = File(context.filesDir, "${source}_index.json")
        file.writeText(index.toString())
    }
}
