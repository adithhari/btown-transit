package com.bloomington.transit.presentation.map

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bloomington.transit.R
import com.bloomington.transit.data.local.GtfsStaticCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.databinding.FragmentRouteMapBinding
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private data class RouteItem(val routeId: String, val displayName: String, val colorInt: Int)

class RouteMapFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentRouteMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RouteMapViewModel by viewModels()
    private lateinit var prefs: PreferencesManager

    private var googleMap: GoogleMap? = null
    private val routePolylines = mutableMapOf<String, Polyline>()
    private val stopCircles = mutableListOf<Pair<Circle, GtfsStop>>()
    private val busMarkerMap = mutableMapOf<String, Marker>()
    private val favoriteMarkers = mutableMapOf<String, Marker>()

    private var routeItems: List<RouteItem> = emptyList()
    private var spinnerReady = false
    private val markerAnimators  = mutableMapOf<String, ValueAnimator>()
    private val lastEmittedPos   = mutableMapOf<String, LatLng>()   // detect real position changes


    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            googleMap?.isMyLocationEnabled = true
            fetchLocationAndAutoSelect()
        } else {
            Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRouteMapBinding.inflate(inflater, container, false)
        prefs = PreferencesManager(requireContext())
        // Map is full-screen — hide the action bar so we have more canvas
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.hide()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Restore the action bar for other screens
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.show()
        markerAnimators.values.forEach { it.cancel() }
        markerAnimators.clear()
        lastEmittedPos.clear()
        busMarkerMap.clear()
        routePolylines.clear()
        stopCircles.clear()
        favoriteMarkers.clear()
        googleMap = null
        binding.map.onDestroy()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.map.onCreate(savedInstanceState)
        binding.map.getMapAsync(this)
        binding.fabMyLocation.setOnClickListener { flyToMyLocation() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk || coarseOk) map.isMyLocationEnabled = true

        viewLifecycleOwner.lifecycleScope.launch {
            val state = prefs.getMapState()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(state.lat, state.lon), state.zoom.toFloat()))
        }

        map.setOnCircleClickListener { circle ->
            stopCircles.find { it.first == circle }?.let { (_, stop) -> showStopInfoDialog(stop) }
        }

        map.setOnMarkerClickListener { marker ->
            val vehicleId = marker.tag as? String ?: return@setOnMarkerClickListener false
            findNavController().navigate(
                R.id.action_routeMapFragment_to_busTrackerFragment,
                bundleOf("vehicleId" to vehicleId)
            )
            true
        }

        buildSpinner()
        observeState()

        if (fineOk || coarseOk) {
            fetchLocationAndAutoSelect()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // ---------------------------------------------------------------------------
    // Spinner
    // ---------------------------------------------------------------------------

    private fun buildSpinner() {
        if (!GtfsStaticCache.isLoaded) {
            viewLifecycleOwner.lifecycleScope.launch {
                GtfsStaticCache.loaded.filter { it }.collect { buildSpinner() }
            }
            return
        }

        val defaultColor = Color.parseColor("#1565C0")
        val allItem = RouteItem("", "All Routes", defaultColor)
        val routes = GtfsStaticCache.routes.values
            .sortedWith(compareBy { it.shortName.padStart(4, '0') })
            .map { route ->
                val color = routeColor(route.routeId, route.color)
                RouteItem(route.routeId, "Route ${route.shortName} — ${route.longName}", color)
            }
        routeItems = listOf(allItem) + routes

        spinnerReady = false
        binding.spinnerRoute.adapter = RouteSpinnerAdapter(requireContext(), routeItems)

        // Restore current ViewModel selection
        val currentId = viewModel.selectedRouteId.value
        if (currentId.isNotEmpty()) {
            val pos = routeItems.indexOfFirst { it.routeId == currentId }
            if (pos >= 0) binding.spinnerRoute.setSelection(pos, false)
        }

        binding.spinnerRoute.post { spinnerReady = true }

        binding.spinnerRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                val item = routeItems.getOrNull(pos) ?: return
                if (item.routeId != viewModel.selectedRouteId.value) {
                    viewModel.selectRoute(item.routeId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ---------------------------------------------------------------------------
    // Location auto-select
    // ---------------------------------------------------------------------------

    private fun fetchLocationAndAutoSelect() {
        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) return

        val client = LocationServices.getFusedLocationProviderClient(requireActivity())
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f))
            } else {
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                client.getCurrentLocation(req, null).addOnSuccessListener { fresh ->
                    if (fresh != null) {
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fresh.latitude, fresh.longitude), 15f))
                    }
                }
            }
        }
    }

    private fun applyRouteSelection(routeId: String) {
        viewModel.selectRoute(routeId)
        val pos = routeItems.indexOfFirst { it.routeId == routeId }
        if (pos >= 0) binding.spinnerRoute.setSelection(pos, false)
    }

    // ---------------------------------------------------------------------------
    // State observation
    // ---------------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val routeId = viewModel.selectedRouteId.value
                        updateBusMarkers(state.vehicles, routeId)
                        when {
                            state.isLoading -> {
                                binding.tvStatus.text = "Loading…"
                                binding.viewLiveDot.setBackgroundResource(R.drawable.dot_live)
                                binding.viewLiveDot.alpha = 0.4f
                            }
                            state.error != null -> {
                                binding.tvStatus.text = "Retrying…"
                                binding.viewLiveDot.setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
                                binding.viewLiveDot.alpha = 1f
                            }
                            else -> {
                                val totalVehicles = state.vehicles.size
                                val busCount = if (routeId.isEmpty()) totalVehicles
                                              else state.vehicles.count { it.routeId == routeId }
                                val time = if (state.lastUpdatedMs > 0)
                                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(state.lastUpdatedMs))
                                else "—"
                                binding.tvStatus.text = if (busCount == 0)
                                    "No buses active • $time"
                                else
                                    "$busCount bus${if (busCount != 1) "es" else ""} live • $time"
                                binding.viewLiveDot.setBackgroundResource(R.drawable.dot_live)
                                binding.viewLiveDot.alpha = 1f
                            }
                        }
                    }
                }
                launch {
                    viewModel.selectedRouteId.collect { routeId ->
                        drawRouteShapes(routeId)
                        drawStopMarkers(routeId)
                        updateBusMarkers(viewModel.uiState.value.vehicles, routeId)
                    }
                }
                launch {
                    GtfsStaticCache.loaded.filter { it }.collect {
                        if (routeItems.isEmpty()) buildSpinner()
                        val routeId = viewModel.selectedRouteId.value
                        drawRouteShapes(routeId)
                        drawStopMarkers(routeId)
                        drawFavoriteStars(viewModel.favoriteStopIds.value)
                    }
                }
                launch {
                    viewModel.favoriteStopIds.collect { favIds -> drawFavoriteStars(favIds) }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Map drawing
    // ---------------------------------------------------------------------------

    private fun drawRouteShapes(selectedRouteId: String) {
        val map = googleMap ?: return
        routePolylines.values.forEach { it.remove() }
        routePolylines.clear()

        for ((shapeId, shapes) in GtfsStaticCache.shapes) {
            val routeId = GtfsStaticCache.trips.values.firstOrNull { it.shapeId == shapeId }?.routeId ?: continue
            if (selectedRouteId.isNotEmpty() && routeId != selectedRouteId) continue

            val route = GtfsStaticCache.routes[routeId]
            val colorInt = routeColor(routeId, route?.color)

            val polyline = map.addPolyline(
                PolylineOptions()
                    .addAll(shapes.map { LatLng(it.lat, it.lon) })
                    .color(colorInt)
                    .width(12f)
                    .geodesic(false)
            )
            routePolylines[shapeId] = polyline
        }
    }

    private fun drawStopMarkers(selectedRouteId: String) {
        val map = googleMap ?: return
        stopCircles.forEach { (c, _) -> c.remove() }
        stopCircles.clear()

        val stopsToShow: Collection<GtfsStop> = if (selectedRouteId.isEmpty()) {
            GtfsStaticCache.stops.values
        } else {
            val tripIds = GtfsStaticCache.tripsByRoute[selectedRouteId] ?: emptyList()
            val stopIds = tripIds.flatMap { tid ->
                GtfsStaticCache.stopTimesByTrip[tid]?.map { it.stopId } ?: emptyList()
            }.toSet()
            stopIds.mapNotNull { GtfsStaticCache.stops[it] }
        }

        val routeColor = GtfsStaticCache.routes[selectedRouteId]
            ?.let { routeColor(selectedRouteId, it.color) }
            ?: Color.parseColor("#FF8F00")

        for (stop in stopsToShow) {
            val fillColor = if (selectedRouteId.isNotEmpty()) {
                routeColor or 0xFF000000.toInt()
            } else {
                val rid = GtfsStaticCache.stopTimesByStop[stop.stopId]
                    ?.firstOrNull()?.let { st -> GtfsStaticCache.trips[st.tripId]?.routeId }
                rid?.let { r -> routeColor(r, GtfsStaticCache.routes[r]?.color) }
                    ?: Color.parseColor("#FF8F00")
            }

            val circle = map.addCircle(
                CircleOptions()
                    .center(LatLng(stop.lat, stop.lon))
                    .radius(10.0)
                    .fillColor(fillColor)
                    .strokeColor(Color.WHITE)
                    .strokeWidth(2f)
                    .clickable(true)
            )
            stopCircles.add(Pair(circle, stop))
        }
    }

    private fun updateBusMarkers(vehicles: List<VehiclePosition>, selectedRouteId: String) {
        val map = googleMap ?: return
        val filtered = if (selectedRouteId.isEmpty()) vehicles
                       else vehicles.filter { it.routeId == selectedRouteId }

        val currentIds = filtered.map { it.vehicleId }.toSet()
        busMarkerMap.keys.filter { it !in currentIds }.forEach { id -> busMarkerMap.remove(id)?.remove() }

        for (vehicle in filtered) {
            val newPos = LatLng(vehicle.lat, vehicle.lon)
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val routeShortName = route?.shortName ?: vehicle.routeId.take(4)
            val colorInt = routeColor(vehicle.routeId, route?.color)

            val existing = busMarkerMap[vehicle.vehicleId]
            if (existing == null) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(newPos)
                        .title("Route $routeShortName")
                        .snippet("Bus ${vehicle.label.ifEmpty { vehicle.vehicleId }} — tap to track")
                        .icon(createBusIcon(routeShortName, colorInt))
                        .anchor(0.5f, 0.5f)
                        .rotation(vehicle.bearing)
                        .flat(true)
                        .zIndex(10f)
                )
                marker?.tag = vehicle.vehicleId
                if (marker != null) {
                    busMarkerMap[vehicle.vehicleId] = marker
                    lastEmittedPos[vehicle.vehicleId] = newPos
                }
            } else {
                val prev = lastEmittedPos[vehicle.vehicleId]
                val moved = prev == null
                    || Math.abs(prev.latitude  - newPos.latitude)  > 1e-6
                    || Math.abs(prev.longitude - newPos.longitude) > 1e-6
                if (moved) {
                    lastEmittedPos[vehicle.vehicleId] = newPos
                    animateMarker(vehicle.vehicleId, existing, newPos, vehicle.bearing)
                }
            }
        }
    }

    /** Animate marker to next interpolated position. 450 ms < 500 ms tick = seamless. */
    private fun animateMarker(id: String, marker: Marker, toPos: LatLng, toBearing: Float) {
        markerAnimators[id]?.cancel()
        val fromPos     = marker.position
        val fromBearing = marker.rotation
        val dBearing    = ((toBearing - fromBearing + 540f) % 360f) - 180f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedFraction
                marker.position = LatLng(
                    fromPos.latitude  + (toPos.latitude  - fromPos.latitude)  * t,
                    fromPos.longitude + (toPos.longitude - fromPos.longitude) * t
                )
                marker.rotation = fromBearing + dBearing * t
            }
        }
        markerAnimators[id] = anim
        anim.start()
    }

    private fun drawFavoriteStars(favStopIds: Set<String>) {
        val map = googleMap ?: return
        favoriteMarkers.keys.filter { it !in favStopIds }.forEach { id ->
            favoriteMarkers.remove(id)?.remove()
        }
        for (stopId in favStopIds) {
            if (favoriteMarkers.containsKey(stopId)) continue
            val stop = GtfsStaticCache.stops[stopId] ?: continue
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title("⭐ ${stop.name}")
                    .snippet("Favorite stop")
                    .icon(createStarIcon())
            )
            if (marker != null) favoriteMarkers[stopId] = marker
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun flyToMyLocation() {
        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        fetchLocationAndAutoSelect()
    }

    private fun showStopInfoDialog(stop: GtfsStop) {
        val sheet = StopInfoSheet.newInstance(stop.stopId)
        sheet.onViewSchedule = {
            StopScheduleSheet.newInstance(stop.stopId, viewModel.selectedRouteId.value)
                .show(parentFragmentManager, "schedule")
        }
        sheet.show(parentFragmentManager, "stop_info")
    }

    /**
     * Top-down 3D bus icon — portrait orientation, "front" faces up (north).
     * The marker uses .flat(true) + .rotation(bearing) so it rotates with road direction.
     */
    private fun createBusIcon(routeShortName: String, color: Int): BitmapDescriptor {
        val dp = resources.displayMetrics.density
        // Canvas size: narrow width, taller height (bus is longer than wide, viewed from above)
        val W = (26 * dp).toInt()
        val H = (44 * dp).toInt()

        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // --- Derive darker shade for 3-D side panels ---
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * 0.65f).coerceIn(0f, 1f)
        val darkColor = Color.HSVToColor(hsv)

        // --- 3-D shadow offset (bottom-right) ---
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(50, 0, 0, 0)
        }
        canvas.drawRoundRect(
            RectF(2*dp, 2*dp, W.toFloat(), H.toFloat()),
            5*dp, 5*dp, shadow
        )

        val body = RectF(0f, 0f, W - 2*dp, H - 2*dp)

        // --- Right side-panel (darker) — 3-D depth illusion ---
        val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = darkColor }
        canvas.drawRoundRect(
            RectF(W - 4*dp, 4*dp, W - 2*dp, H - 4*dp),
            2*dp, 2*dp, sidePaint
        )

        // --- Main top face (route color) ---
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(body, 5*dp, 5*dp, bodyPaint)

        // --- Windshield (front, top ~22 % of body) ---
        val windshieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(200, 180, 225, 255)   // light blue tint
        }
        canvas.drawRoundRect(
            RectF(3*dp, 3*dp, W - 5*dp, H * 0.22f),
            3*dp, 3*dp, windshieldPaint
        )

        // --- Rear window (bottom ~10 %) ---
        canvas.drawRoundRect(
            RectF(4*dp, H * 0.87f, W - 6*dp, H - 5*dp),
            2*dp, 2*dp, windshieldPaint
        )

        // --- Side windows (two small strips left & right) ---
        val sideWinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(150, 180, 225, 255)
        }
        // Left side windows
        canvas.drawRect(0f, H * 0.28f, 2*dp, H * 0.55f, sideWinPaint)
        canvas.drawRect(0f, H * 0.60f, 2*dp, H * 0.82f, sideWinPaint)
        // Right side windows
        canvas.drawRect(W - 4*dp, H * 0.28f, W - 2*dp, H * 0.55f, sideWinPaint)
        canvas.drawRect(W - 4*dp, H * 0.60f, W - 2*dp, H * 0.82f, sideWinPaint)

        // --- Route number badge in center ---
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(180, 0, 0, 0)
        }
        val badgeRect = RectF(3*dp, H * 0.33f, W - 5*dp, H * 0.72f)
        canvas.drawRoundRect(badgeRect, 3*dp, 3*dp, badgePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 9 * dp
        }
        val textY = badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(routeShortName, badgeRect.centerX(), textY, textPaint)

        // --- White outline ---
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * dp
        }
        canvas.drawRoundRect(body, 5*dp, 5*dp, outline)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createStarIcon(): BitmapDescriptor {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFA000"); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BF360C"); style = Paint.Style.STROKE; strokeWidth = 5f }
        val path = android.graphics.Path()
        val cx = size / 2f; val cy = size / 2f
        val outerR = size / 2f - 6; val innerR = outerR * 0.42f
        for (i in 0 until 10) {
            val angle = Math.PI / 5 * i - Math.PI / 2
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // ---------------------------------------------------------------------------
    // Lifecycle passthrough
    // ---------------------------------------------------------------------------

    /** Returns the route color, falling back to a distinct hue derived from routeId. */
    private fun routeColor(routeId: String, colorHex: String?): Int {
        if (!colorHex.isNullOrBlank()) {
            runCatching { return Color.parseColor("#$colorHex") }
        }
        val hue = ((routeId.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % 300).toFloat() + 30f
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.80f))
    }

    override fun onResume() { super.onResume(); binding.map.onResume() }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        googleMap?.cameraPosition?.let { cam ->
            viewLifecycleOwner.lifecycleScope.launch {
                prefs.saveMapState(cam.target.latitude, cam.target.longitude, cam.zoom.toDouble())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.map?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() { super.onLowMemory(); binding.map.onLowMemory() }
}

// ---------------------------------------------------------------------------
// Spinner adapter — each route item rendered in its own route color
// ---------------------------------------------------------------------------

private class RouteSpinnerAdapter(context: Context, private val items: List<RouteItem>) :
    ArrayAdapter<RouteItem>(context, android.R.layout.simple_spinner_item, items) {

    init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getView(pos, convertView, parent), items[pos], bold = true)

    override fun getDropDownView(pos: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getDropDownView(pos, convertView, parent), items[pos], bold = false)

    private fun style(view: View, item: RouteItem, bold: Boolean): View {
        view.findViewById<TextView>(android.R.id.text1)?.apply {
            text = item.displayName
            setTextColor(item.colorInt)
            setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        return view
    }
}
