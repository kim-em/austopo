package com.kim.austopo

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.EditText
import com.kim.austopo.data.MapSheet
import com.kim.austopo.data.MapSheetRepository
import com.kim.austopo.data.SheetStatus
import com.kim.austopo.download.CacheCapEnforcer
import com.kim.austopo.download.OfflineRegion
import com.kim.austopo.download.OfflineRegionDownloader
import com.kim.austopo.download.OfflineRegionStore
import com.kim.austopo.download.PinnedTileStore
import com.kim.austopo.download.SheetDownloadManager
import com.kim.austopo.download.StorageManager
import com.kim.austopo.download.StorageMigration
import com.kim.austopo.download.TileFetcher
import com.kim.austopo.download.TransientTileStore
import com.kim.austopo.geo.TileCoverage
import com.kim.austopo.index.NswIndexSyncer
import com.kim.austopo.render.TileServerRenderer
import com.kim.austopo.ui.BookmarksActivity
import com.kim.austopo.ui.CacheManagementActivity
import com.kim.austopo.ui.DownloadDialog
import com.kim.austopo.ui.OfflineRegionsActivity
import kotlinx.coroutines.*
import java.io.File

class MapActivity : Activity(), LocationListener {

    private lateinit var mapView: TiledMapView
    private lateinit var repository: MapSheetRepository
    private var locationManager: LocationManager? = null
    private var hasNavigatedToGps = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var downloadManager: SheetDownloadManager
    private lateinit var storage: StorageManager
    private lateinit var pinnedStore: PinnedTileStore
    private lateinit var transientStore: TransientTileStore
    private lateinit var offlineRegionStore: OfflineRegionStore
    private lateinit var offlineDownloader: OfflineRegionDownloader
    private lateinit var cacheCapEnforcer: CacheCapEnforcer
    private lateinit var overlayToolbar: LinearLayout
    private var toolbarVisible = true
    private var toggleOverlayButton: Button? = null
    private var toggleGridButton: Button? = null

    // Default center: roughly SE Australia
    private val defaultLat = -33.8
    private val defaultLon = 149.0

    companion object {
        const val DEFAULT_CACHE_MAX_MB = 500
        private const val REQUEST_BOOKMARK_PICK = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Migrate the legacy offline_tiles/ dir on first launch after upgrade.
        StorageMigration.migrateIfNeeded(this)

        storage = StorageManager.get(this)
        pinnedStore = PinnedTileStore(storage)
        transientStore = TransientTileStore(storage)
        offlineRegionStore = OfflineRegionStore(this, storage)
        offlineDownloader = OfflineRegionDownloader(storage, pinnedStore, offlineRegionStore)

        val prefs = getSharedPreferences("austopo_sheets", MODE_PRIVATE)
        cacheCapEnforcer = CacheCapEnforcer(storage, transientStore) {
            val mb = prefs.getInt("cache_max_mb", DEFAULT_CACHE_MAX_MB)
            mb.toLong() * 1024L * 1024L
        }

        repository = MapSheetRepository(this)
        mapView = TiledMapView(this)
        mapView.repository = repository

        // Set up tile servers for all supported states
        for (fetcher in listOf(
            TileFetcher.nsw(), TileFetcher.vic(),
            TileFetcher.qld(), TileFetcher.sa(), TileFetcher.tas(),
            TileFetcher.nt(), TileFetcher.wa()
        )) {
            fetcher.onTileLoaded = { mapView.invalidate() }
            fetcher.storage = storage
            fetcher.pinnedStore = pinnedStore
            fetcher.transientStore = transientStore
            fetcher.onTransientWrite = { cacheCapEnforcer.onTileWritten() }
            mapView.tileServerRenderers.add(TileServerRenderer(fetcher))
        }

        // Persisted overlay prefs
        mapView.showSheetRectangles = prefs.getBoolean("show_sheet_rectangles", false)
        mapView.showKmGrid = prefs.getBoolean("show_km_grid", false)

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
        overlayToolbar = buildOverlayToolbar()
        layout.addView(overlayToolbar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        setContentView(layout)

        mapView.camera.onInteractionStart = { hideToolbar() }
        mapView.onMapTap = { showToolbar() }

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

        loadSheets()
        syncNswIndexIfNeeded()

        // Request GPS
        requestLocationPermission()
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
        menu.add(0, 7, 0, "Bookmarks")
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { actionMyLocation(); true }
        2 -> { actionCacheManagement(); true }
        3 -> { actionSyncNsw(); true }
        4 -> { actionToggleOverlay(); true }
        5 -> { actionSaveOffline(); true }
        6 -> { actionOfflineRegions(); true }
        7 -> { actionBookmarks(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun actionMyLocation() {
        val mx = mapView.gpsMX
        val my = mapView.gpsMY
        if (mx != null && my != null) {
            mapView.camera.setPosition(mx, my, maxOf(mapView.camera.zoom, 0.1f))
            mapView.invalidate()
        } else {
            Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionCacheManagement() {
        startActivity(Intent(this, CacheManagementActivity::class.java))
    }

    private fun actionSyncNsw() {
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
    }

    private fun actionToggleOverlay() {
        mapView.showSheetRectangles = !mapView.showSheetRectangles
        getSharedPreferences("austopo_sheets", MODE_PRIVATE)
            .edit()
            .putBoolean("show_sheet_rectangles", mapView.showSheetRectangles)
            .apply()
        mapView.invalidate()
        toggleOverlayButton?.text = overlayToggleLabel()
    }

    private fun actionToggleGrid() {
        mapView.showKmGrid = !mapView.showKmGrid
        getSharedPreferences("austopo_sheets", MODE_PRIVATE)
            .edit()
            .putBoolean("show_km_grid", mapView.showKmGrid)
            .apply()
        mapView.invalidate()
        toggleGridButton?.text = gridToggleLabel()
    }

    private fun actionSaveOffline() {
        mapView.selectionMode = true
        mapView.invalidate()
        Toast.makeText(this, "Drag to select a region", Toast.LENGTH_LONG).show()
    }

    private fun actionOfflineRegions() {
        startActivity(Intent(this, OfflineRegionsActivity::class.java))
    }

    private fun actionBookmarks() {
        val intent = Intent(this, BookmarksActivity::class.java)
        val gpsLat = mapView.gpsMX?.let { mx ->
            mapView.gpsMY?.let { my -> CoordinateConverter.webMercatorToWgs84(mx, my) }
        }
        if (gpsLat != null) {
            intent.putExtra(BookmarksActivity.EXTRA_CURRENT_LAT, gpsLat.first)
            intent.putExtra(BookmarksActivity.EXTRA_CURRENT_LON, gpsLat.second)
        } else {
            val (lat, lon) = CoordinateConverter.webMercatorToWgs84(
                mapView.camera.centerX, mapView.camera.centerY
            )
            intent.putExtra(BookmarksActivity.EXTRA_CURRENT_LAT, lat)
            intent.putExtra(BookmarksActivity.EXTRA_CURRENT_LON, lon)
        }
        startActivityForResult(intent, REQUEST_BOOKMARK_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BOOKMARK_PICK && resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra(BookmarksActivity.EXTRA_RESULT_LAT, Double.NaN)
            val lon = data.getDoubleExtra(BookmarksActivity.EXTRA_RESULT_LON, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
                mapView.camera.setPosition(mx, my, maxOf(mapView.camera.zoom, 0.1f))
            }
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

        // Fetchers whose extent intersects the selection.
        val activeFetchers = mapView.tileServerRenderers
            .map { it.tileFetcher }
            .filter {
                maxMX > it.extentMinX && minMX < it.extentMaxX &&
                    maxMY > it.extentMinY && minMY < it.extentMaxY
            }

        // LOD range (current LOD ± 2)
        val baseLod = activeFetchers.firstOrNull()?.bestLod(currentMpp) ?: 12
        val lodMin = maxOf(6, baseLod - 2)
        val lodMax = minOf(17, baseLod + 2)

        val perFetcher = TileCoverage.count(minMX, minMY, maxMX, maxMY, lodMin, lodMax)
        val totalTiles = perFetcher * activeFetchers.size
        val estSizeMB = totalTiles * 30 / 1024  // ~30 KB/tile average

        val nameInput = EditText(this).apply {
            hint = "Region name"
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Offline Region")
            .setView(nameInput)
            .setMessage("LOD $lodMin–$lodMax\n~$totalTiles tiles (~${estSizeMB} MB)")
            .setPositiveButton("Download") { _, _ ->
                val name = nameInput.text.toString()
                    .ifBlank { "Region ${System.currentTimeMillis() / 1000}" }
                startOfflineDownload(name, minMX, minMY, maxMX, maxMY, lodMin, lodMax, activeFetchers)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOfflineDownload(
        name: String,
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int,
        fetchers: List<TileFetcher>
    ) {
        if (fetchers.isEmpty()) {
            Toast.makeText(this, "Region is outside any tile server extent", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Downloading \"$name\"...", Toast.LENGTH_SHORT).show()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("offline_dl", "Offline Downloads", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val entries = fetchers.map { OfflineRegionDownloader.Entry(it) }

        offlineDownloader.download(
            name, minMX, minMY, maxMX, maxMY, lodMin, lodMax, entries,
            object : OfflineRegionDownloader.Listener {
                override fun onProgress(done: Int, total: Int) {
                    val notification = Notification.Builder(this@MapActivity, "offline_dl")
                        .setContentTitle("Saving \"$name\"")
                        .setContentText("$done / $total tiles")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setProgress(total, done, total == 0)
                        .setOngoing(true)
                        .build()
                    nm.notify(2001, notification)
                }

                override fun onComplete(success: Boolean, regions: List<OfflineRegion>) {
                    nm.cancel(2001)
                    val msg = if (success) "\"$name\" saved for offline use"
                    else "\"$name\" saved (some tiles failed)"
                    Toast.makeText(this@MapActivity, msg, Toast.LENGTH_SHORT).show()
                    mapView.invalidate()
                }
            }
        )
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
            if (requestCode == 1) startLocationUpdates()
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
        offlineDownloader.cancel()
        cacheCapEnforcer.cancel()
        mapView.recycle()
    }

    // --- Overlay toolbar ---

    private fun buildOverlayToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xC0222222.toInt())  // translucent dark grey
            setPadding(8, 8, 8, 8)
        }
        fun add(label: String, onTap: () -> Unit): Button {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(12, 8, 12, 8)
                setOnClickListener { onTap() }
            }
            bar.addView(b, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            return b
        }
        add("Loc") { actionMyLocation() }
        add("Bkm") { actionBookmarks() }
        add("Save") { actionSaveOffline() }
        add("Rgns") { actionOfflineRegions() }
        add("Cache") { actionCacheManagement() }
        add("Sync") { actionSyncNsw() }
        toggleOverlayButton = add(overlayToggleLabel()) { actionToggleOverlay() }
        toggleGridButton = add(gridToggleLabel()) { actionToggleGrid() }
        return bar
    }

    private fun overlayToggleLabel(): String =
        if (::mapView.isInitialized && mapView.showSheetRectangles) "Hide" else "Show"

    private fun gridToggleLabel(): String =
        if (::mapView.isInitialized && mapView.showKmGrid) "Grid\u2713" else "Grid"

    private fun hideToolbar() {
        if (!toolbarVisible) return
        toolbarVisible = false
        overlayToolbar.animate()
            .alpha(0f)
            .translationY(-overlayToolbar.height.toFloat())
            .setDuration(150L)
            .start()
    }

    private fun showToolbar() {
        if (toolbarVisible) return
        toolbarVisible = true
        overlayToolbar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(150L)
            .start()
    }

}
