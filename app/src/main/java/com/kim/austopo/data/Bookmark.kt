package com.kim.austopo.data

import org.json.JSONObject

/** A named lat/lon the user has saved to jump back to. */
data class Bookmark(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("latitude", latitude)
        put("longitude", longitude)
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject) = Bookmark(
            id = obj.getString("id"),
            name = obj.getString("name"),
            latitude = obj.getDouble("latitude"),
            longitude = obj.getDouble("longitude"),
            createdAtMs = obj.optLong("createdAtMs", 0L)
        )
    }
}
