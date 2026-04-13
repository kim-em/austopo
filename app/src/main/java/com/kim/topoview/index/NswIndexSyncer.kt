package com.kim.topoview.index

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches the NSW topo map sheet index from the FeatureServer and caches as JSON.
 * https://portal.spatial.nsw.gov.au/server/rest/services/Hosted/TopoMapIndex/FeatureServer/0/query
 *
 * ~765 sheets at 1:25k scale. Paginated in batches of 1000.
 */
class NswIndexSyncer(private val context: Context) {

    companion object {
        private const val FEATURE_SERVER =
            "https://portal.spatial.nsw.gov.au/server/rest/services/Hosted/TopoMapIndex/FeatureServer/0/query"
        private const val CACHE_FILENAME = "nsw_sheets.json"
        // Re-sync if cache is older than 30 days
        private const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILENAME)

    /** True if we have a cached index that's not too old. */
    fun hasFreshCache(): Boolean {
        val f = cacheFile
        if (!f.exists()) return false
        return System.currentTimeMillis() - f.lastModified() < CACHE_MAX_AGE_MS
    }

    /** Sync the index from the FeatureServer. Returns true on success. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        try {
            val allFeatures = JSONArray()
            var offset = 0
            val batchSize = 1000

            while (true) {
                val url = "$FEATURE_SERVER" +
                    "?where=scale%3D25000" +
                    "&outFields=tileid,tilename,collarof_51" +
                    "&returnGeometry=true&outSR=4326" +
                    "&resultRecordCount=$batchSize&resultOffset=$offset" +
                    "&f=json"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext false
                }

                val body = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(body)
                val features = json.optJSONArray("features") ?: break

                if (features.length() == 0) break

                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val attrs = feature.getJSONObject("attributes")
                    val geom = feature.optJSONObject("geometry")

                    val entry = JSONObject()
                    entry.put("tileid", attrs.getString("tileid"))
                    entry.put("tilename", attrs.optString("tilename", ""))
                    entry.put("collarof_51", attrs.optString("collarof_51", ""))
                    if (geom != null) {
                        entry.put("geometry", geom)
                    }

                    allFeatures.put(entry)
                }

                offset += features.length()

                // If we got fewer than the batch size, we're done
                if (features.length() < batchSize) break
            }

            // Write to cache
            cacheFile.writeText(allFeatures.toString())
            true
        } catch (e: Exception) {
            false
        }
    }
}
