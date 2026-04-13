package com.kim.topoview

import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File

class MapViewerActivity : Activity(), LocationListener {

    private lateinit var mapView: TiledMapView
    private var locationManager: LocationManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mapView = TiledMapView(this)
        val layout = FrameLayout(this)
        layout.addView(mapView)
        setContentView(layout)

        val jsonPath = intent.getStringExtra("json_path") ?: run {
            Toast.makeText(this, "No map specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val jsonFile = File(jsonPath)
        val meta = MapMetadata.fromJsonFile(jsonFile)
        if (meta == null) {
            Toast.makeText(this, "Failed to load map metadata", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        mapView.setMap(meta.imageFile, meta)
        title = meta.name

        requestLocationPermission()
    }

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
        if (requestCode == 1 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
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
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    override fun onLocationChanged(location: Location) {
        mapView.setGpsPosition(location.latitude, location.longitude, location.accuracy)
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
        mapView.recycle()
    }
}
