package com.kim.austopo.util

import android.content.Context
import java.io.File

/**
 * Location of user-supplied topo map sheets (JPEG + JSON pairs produced by
 * `convert_geotiff.py`).
 *
 * Historically this was `/sdcard/TopoMaps/`, which forced us to request
 * MANAGE_EXTERNAL_STORAGE — Android's scary "All files access" prompt. The
 * new location is the app's own external-files dir, e.g.
 *
 *     /sdcard/Android/data/com.kim.austopo/files/TopoMaps/
 *
 * Readable + writable by the app with no permission at all. Still visible
 * over USB / adb for the user to push pre-converted sheets into.
 *
 * If external storage isn't mounted (very rare), we fall back to the app's
 * internal files dir so the code path still works.
 */
object MapsDir {
    fun forContext(context: Context): File {
        val ext = context.getExternalFilesDir("TopoMaps")
        if (ext != null) return ext
        return File(context.filesDir, "TopoMaps").apply { mkdirs() }
    }
}
