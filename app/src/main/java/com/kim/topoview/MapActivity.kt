package com.kim.topoview

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.EditText
import com.kim.topoview.data.MapSheet
import com.kim.topoview.data.MapSheetRepository
import com.kim.topoview.data.SheetStatus
import com.kim.topoview.download.OfflineRegion
import com.kim.topoview.download.OfflineRegionStore
import com.kim.topoview.download.OfflineTileStore
import com.kim.topoview.download.SheetDownloadManager
import com.kim.topoview.download.TileFetcher
import com.kim.topoview.index.NswIndexSyncer
import com.kim.topoview.render.TileServerRenderer
import com.kim.topoview.ui.CacheManagementActivity
import com.kim.topoview.ui.DownloadDialog
import com.kim.topoview.ui.OfflineRegionsActivity
import kotlinx.coroutines.*
import java.io.File

class MapActivity : Activity(), LocationListener {

    private lateinit var mapView: TiledMapView
    private lateinit var repository: MapSheetRepository
    private var locationManager: LocationManager? = null
    private var hasNavigatedToGps = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var downloadManager: SheetDownloadManager
    private lateinit var offlineTileStore: OfflineTileStore
    private lateinit var offlineRegionStore: OfflineRegionStore

    // Default center: roughly SE Australia
    private val defaultLat = -33.8
    private val defaultLon = 149.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = MapSheetRepository(this)
        mapView = TiledMapView(this)
        mapView.repository = repository
        offlineTileStore = OfflineTileStore(this)
        offlineRegionStore = OfflineRegionStore(this)

        // Set up tile servers (NSW + Victoria)
        for (fetcher in listOf(TileFetcher.nsw(this), TileFetcher.vic(this))) {
            fetcher.onTileLoaded = { mapView.invalidate() }
            fetcher.offlineStore = offlineTileStore
            mapView.tileServerRenderers.add(TileServerRenderer(fetcher))
        }

        // Set up download manager
        downloadManager = SheetDownloadManager(this)
        downloadManager.onDownloadComplete = { sheet, success ->
            if (success) {
                Toast.makeText(this, "${sheet.name} downloaded", Toast.LENGTH_SHORT).show()
                repository.loadAll()
                mapView.invalidate()
            } else {
                Toast.makeText(this, "Download failed: ${sheet.name}", Toast.LENGTH_SHORT).show()
            }
        }

        val layout = FrameLayout(this)
        layout.addView(mapView)
        setContentView(layout)

        // Start at a wide view over SE Australia
        val (mx, my) = CoordinateConverter.wgs84ToWebMercator(defaultLat, defaultLon)
        mapView.camera.clampEnabled = false
        mapView.camera.centerX = mx
        mapView.camera.centerY = my

        // Handle tap on sheet rectangles
        mapView.onSheetTapped = { sheet -> onSheetTapped(sheet) }

        // Handle region selection for offline save
        mapView.onRegionSelected = { minMX, minMY, maxMX, maxMY ->
            mapView.selectionMode = false
            showSaveOfflineDialog(minMX, minMY, maxMX, maxMY)
        }

        checkStoragePermission()
        loadSheets()
        syncNswIndexIfNeeded()

        // Request GPS
        requestLocationPermission()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                try {
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 2)
            }
        }
    }

    private fun loadSheets() {
        repository.loadAll()
        mapView.invalidate()

        // If launched with a specific map, load it
        val jsonPath = intent.getStringExtra("json_path")
        if (jsonPath != null) {
            val jsonFile = File(jsonPath)
            val meta = MapMetadata.fromJsonFile(jsonFile)
            if (meta != null) {
                mapView.setMap(meta.imageFile, meta)
                title = meta.name
                return
            }
        }

        // Set initial zoom if view is already laid out
        if (mapView.width > 0) {
            setInitialZoom()
        } else {
            mapView.post { setInitialZoom() }
        }
    }

    private fun setInitialZoom() {
        val (mxL, _) = CoordinateConverter.wgs84ToWebMercator(defaultLat, defaultLon - 2.5)
        val (mxR, _) = CoordinateConverter.wgs84ToWebMercator(defaultLat, defaultLon + 2.5)
        val widthMeters = mxR - mxL
        if (mapView.width > 0) {
            mapView.camera.zoom = (mapView.width / widthMeters).toFloat()
        }
        mapView.invalidate()
    }

    private fun onSheetTapped(sheet: MapSheet) {
        when {
            sheet.isNsw -> {
                // Zoom into the NSW sheet area (tiles load automatically via tile server)
                zoomToSheet(sheet)
            }
            sheet.isLocal && sheet.localPath != null -> {
                // Load and render the local JPEG
                val jsonFile = File(sheet.localPath!!)
                val meta = MapMetadata.fromJsonFile(jsonFile)
                if (meta != null) {
                    mapView.setMap(meta.imageFile, meta)
                    title = sheet.name
                } else {
                    Toast.makeText(this, "Failed to load ${sheet.name}", Toast.LENGTH_SHORT).show()
                }
            }
            sheet.status == SheetStatus.AVAILABLE && sheet.downloadUrl != null -> {
                DownloadDialog.show(this, sheet) {
                    downloadManager.download(sheet)
                    Toast.makeText(this, "Downloading ${sheet.name}...", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                zoomToSheet(sheet)
            }
        }
    }

    private fun zoomToSheet(sheet: MapSheet) {
        val bbox = sheet.bboxMercator
        val cx = (bbox[0] + bbox[2]) / 2.0
        val cy = (bbox[1] + bbox[3]) / 2.0
        val sheetWidth = bbox[2] - bbox[0]
        if (mapView.width > 0 && sheetWidth > 0) {
            val newZoom = (mapView.width / sheetWidth * 0.9).toFloat()
            mapView.camera.setPosition(cx, cy, newZoom)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "My Location")
        menu.add(0, 5, 0, "Save Offline")
        menu.add(0, 6, 0, "Offline Regions")
        menu.add(0, 2, 0, "Cache Management")
        menu.add(0, 3, 0, "Sync NSW Index")
        menu.add(0, 4, 0, if (mapView.showSheetRectangles) "Hide Sheet Grid" else "Show Sheet Grid")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(4)?.title = if (mapView.showSheetRectangles) "Hide Sheet Grid" else "Show Sheet Grid"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                val mx = mapView.gpsMX
                val my = mapView.gpsMY
                if (mx != null && my != null) {
                    mapView.camera.setPosition(mx, my, maxOf(mapView.camera.zoom, 0.1f))
                    mapView.invalidate()
                } else {
                    Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
                }
                true
            }
            2 -> {
                startActivity(Intent(this, CacheManagementActivity::class.java))
                true
            }
            3 -> {
                Toast.makeText(this, "Syncing NSW index...", Toast.LENGTH_SHORT).show()
                val syncer = NswIndexSyncer(this)
                scope.launch {
                    val success = syncer.sync()
                    if (success) {
                        repository.loadAll()
                        mapView.invalidate()
                        Toast.makeText(this@MapActivity, "NSW index synced", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MapActivity, "Sync failed", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            4 -> {
                mapView.showSheetRectangles = !mapView.showSheetRectangles
                mapView.invalidate()
                invalidateOptionsMenu()
                true
            }
            5 -> {
                mapView.selectionMode = true
                mapView.invalidate()
                Toast.makeText(this, "Drag to select a region", Toast.LENGTH_LONG).show()
                true
            }
            6 -> {
                startActivity(Intent(this, OfflineRegionsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun syncNswIndexIfNeeded() {
        val syncer = NswIndexSyncer(this)
        if (syncer.hasFreshCache()) return

        scope.launch {
            val success = syncer.sync()
            if (success) {
                repository.loadAll()
                mapView.invalidate()
            }
        }
    }

    // --- Offline region saving ---

    private fun showSaveOfflineDialog(minMX: Double, minMY: Double, maxMX: Double, maxMY: Double) {
        val currentMpp = mapView.camera.metersPerPixel()

        // Get the actual fetcher references from the renderers
        val activeFetchers = mapView.tileServerRenderers.mapNotNull { renderer ->
            try {
                val field = renderer.javaClass.getDeclaredField("tileFetcher")
                field.isAccessible = true
                field.get(renderer) as? TileFetcher
            } catch (_: Exception) { null }
        }

        // Calculate tile counts for LOD range (current LOD ± 2)
        val baseLod = activeFetchers.firstOrNull()?.bestLod(currentMpp) ?: 12
        val lodMin = maxOf(6, baseLod - 2)
        val lodMax = minOf(17, baseLod + 2)

        var totalTiles = 0
        for (fetcher in activeFetchers) {
            if (maxMX > fetcher.extentMinX && minMX < fetcher.extentMaxX &&
                maxMY > fetcher.extentMinY && minMY < fetcher.extentMaxY) {
                val estimates = offlineTileStore.estimateTiles(fetcher, minMX, minMY, maxMX, maxMY, lodMin, lodMax)
                totalTiles += estimates.sumOf { it.second }
            }
        }

        val estSizeMB = totalTiles * 30 / 1024  // ~30KB per tile average

        // Show dialog with name field
        val nameInput = EditText(this).apply {
            hint = "Region name"
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Offline Region")
            .setView(nameInput)
            .setMessage("LOD $lodMin–$lodMax\n~$totalTiles tiles (~${estSizeMB} MB)")
            .setPositiveButton("Download") { _, _ ->
                val name = nameInput.text.toString().ifBlank { "Region ${System.currentTimeMillis() / 1000}" }
                startOfflineDownload(name, minMX, minMY, maxMX, maxMY, lodMin, lodMax, activeFetchers, totalTiles)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOfflineDownload(
        name: String,
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int,
        fetchers: List<TileFetcher>,
        totalTiles: Int
    ) {
        Toast.makeText(this, "Downloading \"$name\"...", Toast.LENGTH_SHORT).show()

        // Create notification channel
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("offline_dl", "Offline Downloads", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        for (fetcher in fetchers) {
            if (maxMX <= fetcher.extentMinX || minMX >= fetcher.extentMaxX ||
                maxMY <= fetcher.extentMinY || minMY >= fetcher.extentMaxY) continue

            // Get the base URL via reflection since it's private
            val baseUrl = try {
                val field = fetcher.javaClass.getDeclaredField("baseUrl")
                field.isAccessible = true
                field.get(fetcher) as String
            } catch (_: Exception) { continue }

            offlineTileStore.onProgress = { downloaded, total ->
                val notification = Notification.Builder(this, "offline_dl")
                    .setContentTitle("Saving \"$name\"")
                    .setContentText("$downloaded / $total tiles")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(total, downloaded, false)
                    .setOngoing(true)
                    .build()
                nm.notify(2001, notification)
            }

            offlineTileStore.onComplete = { success ->
                nm.cancel(2001)
                val msg = if (success) "\"$name\" saved for offline use" else "\"$name\" saved (some tiles failed)"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

                // Save region metadata
                offlineRegionStore.add(OfflineRegion(
                    name = name,
                    minMX = minMX, minMY = minMY, maxMX = maxMX, maxMY = maxMY,
                    lodMin = lodMin, lodMax = lodMax,
                    cacheName = fetcher.tileCacheName,
                    tileCount = totalTiles
                ))
            }

            offlineTileStore.downloadRegion(
                fetcher, baseUrl, fetcher.tileCacheName,
                minMX, minMY, maxMX, maxMY, lodMin, lodMax
            )
        }
    }

    // --- GPS ---

    private fun requestLocationPermission() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                1 -> startLocationUpdates()
                2 -> {
                    loadSheets()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 1f, this)
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 5f, this)
            } catch (_: Exception) {}

            val last = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocationChanged(last)
        } catch (_: SecurityException) {}
    }

    override fun onLocationChanged(location: Location) {
        mapView.setGpsPosition(location.latitude, location.longitude, location.accuracy)

        // On first GPS fix, navigate to position
        if (!hasNavigatedToGps && intent.getStringExtra("json_path") == null) {
            hasNavigatedToGps = true
            val (mx, my) = CoordinateConverter.wgs84ToWebMercator(
                location.latitude, location.longitude)

            // Check if there's a local sheet at this location
            val localSheets = repository.sheetsAtLocation(location.latitude, location.longitude)
                .filter { it.isLocal }
            if (localSheets.isNotEmpty()) {
                // Load the first local sheet
                val sheet = localSheets.first()
                if (sheet.localPath != null) {
                    val meta = MapMetadata.fromJsonFile(File(sheet.localPath!!))
                    if (meta != null) {
                        mapView.setMap(meta.imageFile, meta)
                        title = sheet.name
                        return
                    }
                }
            }

            // Otherwise zoom to GPS position at a reasonable zoom level
            // ~1 pixel per 10 meters
            mapView.camera.setPosition(mx, my, 0.1f)
        }
    }

    override fun onPause() {
        super.onPause()
        locationManager?.removeUpdates(this)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        downloadManager.cancel()
        mapView.recycle()
    }
}
