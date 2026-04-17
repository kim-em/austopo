package com.kim.austopo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class PlaceResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val type: String
)

/**
 * Searches for Australian places via the OpenStreetMap Nominatim API.
 * Usage policy: max 1 request/second, custom User-Agent required.
 * https://operations.osmfoundation.org/policies/nominatim/
 */
object PlaceSearchClient {

    private val client = OkHttpClient()

    suspend fun search(query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&countrycodes=au" +
            "&format=json" +
            "&limit=10"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AusTopo/1.0 (Android; kim@lean-fro.org)")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val array = JSONArray(body)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            PlaceResult(
                displayName = obj.getString("display_name"),
                latitude = obj.getDouble("lat"),
                longitude = obj.getDouble("lon"),
                type = obj.optString("type", "")
            )
        }
    }
}
