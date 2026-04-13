package com.kim.topoview

import org.json.JSONObject
import java.io.File

data class MapMetadata(
    val name: String,
    val imageFile: File,
    val crs: String,
    val originX: Double,
    val originY: Double,
    val pixelSizeX: Double,
    val pixelSizeY: Double,
    val width: Int,
    val height: Int
) {
    companion object {
        fun fromJsonFile(file: File): MapMetadata? {
            return try {
                val json = JSONObject(file.readText())
                val imageFile = File(file.parentFile, json.getString("image"))
                if (!imageFile.exists()) return null
                MapMetadata(
                    name = json.getString("name"),
                    imageFile = imageFile,
                    crs = json.optString("crs", "EPSG:3857"),
                    originX = json.getDouble("origin_x"),
                    originY = json.getDouble("origin_y"),
                    pixelSizeX = json.getDouble("pixel_size_x"),
                    pixelSizeY = json.getDouble("pixel_size_y"),
                    width = json.getInt("width"),
                    height = json.getInt("height")
                )
            } catch (e: Exception) {
                null
            }
        }

        fun scanDirectory(dir: File): List<MapMetadata> {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { fromJsonFile(it) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }
}
