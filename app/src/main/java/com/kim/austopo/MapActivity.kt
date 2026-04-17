package com.kim.austopo

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.EditText
import com.kim.austopo.data.MapSheet
import com.kim.austopo.data.MapSheetRepository
import com.kim.austopo.data.PlaceSearchClient
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
    private lateinit var titleBar: LinearLayout
    private lateinit var hamburgerButton: Button
    private var toolbarVisible = true

    // Default center: roughly SE Australia
    private val defaultLat = -33.8
    private val defaultLon = 149.0

    companion object {
        const val DEFAULT_CACHE_MAX_MB = 500
        private const val REQUEST_BOOKMARK_PICK = 101

        // Hamburger menu item IDs
        private const val MENU_LOCATION = 1
        private const val MENU_BOOKMARKS = 2
        private const val MENU_SAVE_OFFLINE = 3
        private const val MENU_OFFLINE_REGIONS = 4
        private const val MENU_CACHE = 5
        private const val MENU_SYNC_NSW = 6
        private const val MENU_SHEET_GRID = 7
        private const val MENU_KM_GRID = 8
        private const val MENU_SEARCH = 9
        private const val MENU_MAP_DETAIL = 10
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

        // Set up tile servers with boundary-based tile ownership.
        val boundaryIndex = com.kim.austopo.geo.StateBoundaryIndex.get(this)
        for (fetcher in listOf(
            TileFetcher.nt(), TileFetcher.wa(),
            TileFetcher.sa(), TileFetcher.qld(),
            TileFetcher.vic(), TileFetcher.nsw(),
            TileFetcher.tas()
        )) {
            fetcher.onTileLoaded = { mapView.invalidate() }
            fetcher.storage = storage
            fetcher.pinnedStore = pinnedStore
            fetcher.transientStore = transientStore
            fetcher.onTransientWrite = { cacheCapEnforcer.onTileWritten() }
            val renderer = TileServerRenderer(fetcher)
            renderer.boundaryIndex = boundaryIndex
            mapView.tileServerRenderers.add(renderer)
        }

        // Persisted prefs
        mapView.detailFactor = prefs.getFloat("detail_factor", defaultDetailFactor()).toDouble()
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
        titleBar = buildTitleBar()
        layout.addView(titleBar, FrameLayout.LayoutParams(
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

        // Intent-driven camera: adb shell am start -n com.kim.austopo/.MapActivity
        //   --ef lat -42.88 --ef lon 147.33 --ef zoom 2.0
        applyIntentCamera()

        // GPS is opt-in: we don't request the permission on launch (which would
        // pop the OS dialog with no context, and breaches Play's prominent-
        // disclosure policy). Instead, the user invokes it via the Loc button,
        // which shows our own explanation first.
    }

    private fun hasIntentCamera(): Boolean =
        !intent.getDoubleExtra("lat", Double.NaN).isNaN()

    private fun applyIntentCamera() {
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
        val zoom = intent.getFloatExtra("zoom", 0.1f)
        mapView.camera.centerX = mx
        mapView.camera.centerY = my
        mapView.camera.zoom = zoom
        mapView.invalidate()
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

        // Set initial zoom if view is already laid out (skip if intent
        // provides an explicit camera position)
        if (!hasIntentCamera()) {
            if (mapView.width > 0) {
                setInitialZoom()
            } else {
                mapView.post { setInitialZoom() }
            }
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



    private fun actionMyLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            showLocationDisclosure()
            return
        }
        // Permission granted but we may not have started updates yet (e.g.
        // first tap after a fresh launch where the user previously granted).
        if (locationManager == null) startLocationUpdates()

        val mx = mapView.gpsMX
        val my = mapView.gpsMY
        if (mx != null && my != null) {
            mapView.camera.setPosition(mx, my, maxOf(mapView.camera.zoom, 0.1f))
            mapView.invalidate()
        } else {
            Toast.makeText(this, "Acquiring GPS fix\u2026", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionSearch() {
        val input = EditText(this).apply {
            hint = "e.g. Mount Kosciuszko, Uluru, Cradle Mountain"
            setSingleLine(true)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Search Place")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) performSearch(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSearch(query: String) {
        scope.launch {
            val results = try {
                PlaceSearchClient.search(query)
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "Search failed: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (results.isEmpty()) {
                Toast.makeText(this@MapActivity, "No results for \"$query\"", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Show short display names (strip trailing ", Australia" etc.)
            val names = results.map { r ->
                r.displayName
                    .replace(Regex(",\\s*Australia$"), "")
                    .let { if (it.length > 60) it.take(57) + "..." else it }
            }.toTypedArray()
            AlertDialog.Builder(this@MapActivity)
                .setTitle("Results for \"$query\"")
                .setItems(names) { _, which ->
                    val r = results[which]
                    val (mx, my) = CoordinateConverter.wgs84ToWebMercator(r.latitude, r.longitude)
                    mapView.camera.setPosition(mx, my, maxOf(mapView.camera.zoom, 0.1f))
                    mapView.invalidate()
                }
                .setNegativeButton("Cancel", null)
                .show()
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
    }

    private fun actionToggleGrid() {
        mapView.showKmGrid = !mapView.showKmGrid
        getSharedPreferences("austopo_sheets", MODE_PRIVATE)
            .edit()
            .putBoolean("show_km_grid", mapView.showKmGrid)
            .apply()
        mapView.invalidate()
    }

    private fun actionMapDetail() {
        val prefs = getSharedPreferences("austopo_sheets", MODE_PRIVATE)
        val currentFactor = prefs.getFloat("detail_factor", defaultDetailFactor())
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val label = TextView(this).apply {
            text = formatDetailFactor(currentFactor)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(label)
        val seekBar = SeekBar(this).apply {
            max = 100
            progress = detailFactorToProgress(currentFactor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = formatDetailFactor(progressToDetailFactor(progress))
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(seekBar)
        AlertDialog.Builder(this)
            .setTitle("Map Detail")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val factor = progressToDetailFactor(seekBar.progress)
                prefs.edit().putFloat("detail_factor", factor).apply()
                mapView.detailFactor = factor.toDouble()
                mapView.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun progressToDetailFactor(progress: Int): Float = 1.0f + progress / 100f * 3.0f
    private fun detailFactorToProgress(factor: Float): Int = ((factor - 1.0f) / 3.0f * 100f).toInt().coerceIn(0, 100)
    private fun formatDetailFactor(factor: Float): String = if (factor <= 1.05f) "Native" else "%.1fx".format(factor)
    private fun defaultDetailFactor(): Float = (resources.displayMetrics.densityDpi / 150f).coerceIn(1.0f, 4.0f)

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

        if (activeFetchers.isEmpty()) {
            Toast.makeText(this, "Region is outside any tile server extent.", Toast.LENGTH_LONG).show()
            return
        }

        // LOD range (current LOD ± 2)
        val baseLod = activeFetchers.first().bestLod(currentMpp)
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

    /**
     * Prominent in-app disclosure shown before the OS permission prompt, as
     * required by Play's Location policy. Must be the user's first sight of
     * any location-related dialog.
     */
    private fun showLocationDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Allow location access?")
            .setMessage(
                "AusTopo uses your device location to show where you are on the " +
                "map and to centre the view when you tap Loc.\n\n" +
                "Your location stays on this device. AusTopo does not share, " +
                "transmit, or store your location anywhere off the phone."
            )
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
            .setNegativeButton("Not now", null)
            .show()
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

    // --- Title bar + hamburger menu ---

    private fun buildTitleBar(): LinearLayout {
        // Push the bar below the Android status bar (battery/wifi/clock)
        val statusBarHeight = resources.getDimensionPixelSize(
            resources.getIdentifier("status_bar_height", "dimen", "android"))
                .coerceAtLeast(48)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xC0222222.toInt())
            setPadding(16, statusBarHeight + 8, 8, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AusTopo"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        }
        bar.addView(title, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        val searchButton = Button(this).apply {
            text = "\uD83D\uDD0D"   // 🔍 magnifying glass
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 4, 12, 4)
            setOnClickListener { actionSearch() }
        }
        bar.addView(searchButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        hamburgerButton = Button(this).apply {
            text = "\u2630"   // ☰ trigram for heaven (hamburger icon)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 4, 24, 4)
            setOnClickListener { showMainMenu() }
        }
        bar.addView(hamburgerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return bar
    }

    private fun showMainMenu() {
        val popup = PopupMenu(this, hamburgerButton)
        popup.menu.add(0, MENU_SEARCH, 0, "Search Place")
        popup.menu.add(0, MENU_LOCATION, 1, "My Location")
        popup.menu.add(0, MENU_BOOKMARKS, 2, "Bookmarks")
        popup.menu.add(0, MENU_SAVE_OFFLINE, 3, "Save Offline")
        popup.menu.add(0, MENU_OFFLINE_REGIONS, 4, "Offline Regions")
        popup.menu.add(0, MENU_CACHE, 5, "Cache Management")
        popup.menu.add(0, MENU_SYNC_NSW, 6, "Sync NSW Index")
        popup.menu.add(0, MENU_SHEET_GRID, 7,
            if (mapView.showSheetRectangles) "Hide Sheet Grid" else "Show Sheet Grid")
        popup.menu.add(0, MENU_KM_GRID, 8,
            if (mapView.showKmGrid) "Hide 1 km Grid" else "Show 1 km Grid")
        popup.menu.add(0, MENU_MAP_DETAIL, 9, "Map Detail")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SEARCH -> { actionSearch(); true }
                MENU_LOCATION -> { actionMyLocation(); true }
                MENU_BOOKMARKS -> { actionBookmarks(); true }
                MENU_SAVE_OFFLINE -> { actionSaveOffline(); true }
                MENU_OFFLINE_REGIONS -> { actionOfflineRegions(); true }
                MENU_CACHE -> { actionCacheManagement(); true }
                MENU_SYNC_NSW -> { actionSyncNsw(); true }
                MENU_SHEET_GRID -> { actionToggleOverlay(); true }
                MENU_KM_GRID -> { actionToggleGrid(); true }
                MENU_MAP_DETAIL -> { actionMapDetail(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun hideToolbar() {
        if (!toolbarVisible) return
        toolbarVisible = false
        titleBar.animate()
            .alpha(0f)
            .translationY(-titleBar.height.toFloat())
            .setDuration(150L)
            .start()
    }

    private fun showToolbar() {
        if (toolbarVisible) return
        toolbarVisible = true
        titleBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(150L)
            .start()
    }

}
