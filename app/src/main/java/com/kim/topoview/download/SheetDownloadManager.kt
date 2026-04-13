package com.kim.topoview.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import com.kim.topoview.data.MapSheet
import com.kim.topoview.data.SheetStatus
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads map sheets (Getlost GeoTIFFs from Google Drive, or NSW GeoTIFFs).
 * Uses OkHttp for downloads and shows a notification during download.
 */
class SheetDownloadManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "topoview_download"
        private const val NOTIFICATION_ID = 1001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mapsDir = File(Environment.getExternalStorageDirectory(), "TopoMaps")

    var onDownloadComplete: ((MapSheet, Boolean) -> Unit)? = null
    var onDownloadProgress: ((MapSheet, Int) -> Unit)? = null

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Map Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for map sheet downloads"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun download(sheet: MapSheet) {
        if (sheet.status == SheetStatus.DOWNLOADING) return
        sheet.status = SheetStatus.DOWNLOADING

        scope.launch {
            var success = false
            try {
                mapsDir.mkdirs()

                val url = resolveDownloadUrl(sheet) ?: run {
                    withContext(Dispatchers.Main) {
                        onDownloadComplete?.invoke(sheet, false)
                    }
                    return@launch
                }

                showNotification("Downloading ${sheet.name}...", 0)

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@launch
                }

                val body = response.body ?: run {
                    response.close()
                    return@launch
                }

                val contentLength = body.contentLength()
                val safeName = sheet.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val outFile = File(mapsDir, "${safeName}.tif")
                val stream = body.byteStream()
                val fos = FileOutputStream(outFile)

                var totalRead = 0L
                val buffer = ByteArray(8192)
                var lastProgress = 0

                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    totalRead += read

                    if (contentLength > 0) {
                        val progress = (totalRead * 100 / contentLength).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            showNotification("Downloading ${sheet.name}...", progress)
                            withContext(Dispatchers.Main) {
                                onDownloadProgress?.invoke(sheet, progress)
                            }
                        }
                    }
                }

                fos.close()
                stream.close()
                response.close()

                // TODO: Convert GeoTIFF to JPEG + JSON sidecar
                // For now, mark the raw file location
                sheet.localPath = outFile.absolutePath
                sheet.status = SheetStatus.CACHED
                success = true

            } catch (e: Exception) {
                sheet.status = SheetStatus.AVAILABLE
            } finally {
                cancelNotification()
                withContext(Dispatchers.Main) {
                    onDownloadComplete?.invoke(sheet, success)
                }
            }
        }
    }

    private fun resolveDownloadUrl(sheet: MapSheet): String? {
        val url = sheet.downloadUrl ?: return null

        if (url.startsWith("gdrive:")) {
            val fileId = url.removePrefix("gdrive:")
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        if (url.startsWith("http")) return url

        return null
    }

    private fun showNotification(text: String, progress: Int) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("TopoView")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    fun cancel() {
        scope.cancel()
        cancelNotification()
    }
}
