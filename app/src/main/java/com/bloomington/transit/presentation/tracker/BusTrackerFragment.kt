package com.bloomington.transit.presentation.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.databinding.FragmentBusTrackerBinding
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.launch

class BusTrackerFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentBusTrackerBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: BusTrackerViewModel

    private var googleMap: GoogleMap? = null
    private var busMarker: Marker? = null
    private val stopMarkers = mutableListOf<Marker>()
    private var routeShapeDrawn = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBusTrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vehicleId = arguments?.getString("vehicleId") ?: ""
        viewModel = ViewModelProvider(this, BusTrackerViewModelFactory(vehicleId, requireContext()))
            .get(BusTrackerViewModel::class.java)

        binding.trackerMap.onCreate(savedInstanceState)
        binding.trackerMap.getMapAsync(this)

        binding.rvNextStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNextStops.adapter = NextStopsAdapter()

        binding.btnClearAlert.setOnClickListener { viewModel.clearAlert() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        map.setOnMarkerClickListener { marker ->
            val stopId = marker.tag as? String
            if (stopId != null) {
                viewModel.setAlert(stopId)
                true
            } else false
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val map = googleMap ?: return@collect
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        val pos = LatLng(vehicle.lat, vehicle.lon)
                        val route = GtfsStaticCache.routes[vehicle.routeId]
                        val colorInt = try { Color.parseColor("#${route?.color ?: "1565C0"}") }
                        catch (_: Exception) { Color.parseColor("#1565C0") }

                        if (busMarker == null) {
                            val marker = map.addMarker(
                                MarkerOptions()
                                    .position(pos)
                                    .title("Bus ${vehicle.label.ifEmpty { vehicle.vehicleId }}")
                                    .icon(createBusDot(colorInt))
                            )
                            busMarker = marker
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
                        } else {
                            busMarker!!.position = pos
                            map.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        }

                        if (!routeShapeDrawn) {
                            drawRouteShape(vehicle.tripId, colorInt)
                            routeShapeDrawn = true
                        }
                    }

                    (binding.rvNextStops.adapter as NextStopsAdapter).submitList(state.nextStops)

                    updateArrivalProgress(state)
                }
            }
        }
    }

    /**
     * Shows/updates the arrival progress card when a stop is being tracked.
     * Progress: 0 % at 15+ min away → 100 % at arrival.
     * Bar colour: green → amber → red as bus gets close.
     */
    private fun updateArrivalProgress(state: com.bloomington.transit.presentation.tracker.TrackerUiState) {
        val stopId = state.alertStopId
        if (stopId.isEmpty()) {
            binding.cardArrivalProgress.visibility = android.view.View.GONE
            binding.tvAlertStatus.visibility = android.view.View.VISIBLE
            binding.tvAlertStatus.text = "Tap a stop marker to set an arrival alert"
            return
        }

        binding.cardArrivalProgress.visibility = android.view.View.VISIBLE
        binding.tvAlertStatus.visibility = android.view.View.GONE

        // Stop name
        val stop = com.bloomington.transit.data.local.GtfsStaticCache.stops[stopId]
        binding.tvTrackedStop.text = stop?.name ?: stopId

        // ETA entry for the tracked stop
        val eta = state.nextStops.find { it.stopId == stopId }
        val nowSec = System.currentTimeMillis() / 1000L
        val arrSec = eta?.let {
            if (it.liveArrivalSec > 0) it.liveArrivalSec else it.scheduledArrivalSec
        } ?: 0L

        val minutesAway = if (arrSec > nowSec) ((arrSec - nowSec) / 60L).toInt() else 0

        // ETA badge text + colour
        val (badgeText, badgeColor) = when {
            arrSec <= 0      -> Pair("-- min", "#1565C0")
            minutesAway <= 0 -> Pair("Arriving", "#4CAF50")
            minutesAway <= 5 -> Pair("$minutesAway min", "#F44336")
            minutesAway <= 10 -> Pair("$minutesAway min", "#FF9800")
            else              -> Pair("$minutesAway min", "#1565C0")
        }
        binding.tvEtaBadge.text = badgeText
        binding.tvEtaBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(badgeColor))

        // Progress: 0 % at ≥15 min, 100 % at arrival
        val WINDOW_MIN = 15
        val progress = if (arrSec <= 0) 0
        else ((WINDOW_MIN - minutesAway.coerceAtMost(WINDOW_MIN)).toFloat() / WINDOW_MIN * 100).toInt()
            .coerceIn(0, 100)

        // Animate progress bar
        val animator = android.animation.ObjectAnimator.ofInt(
            binding.progressArrival, "progress",
            binding.progressArrival.progress, progress
        ).apply { duration = 600L }
        animator.start()

        // Bar colour reflects urgency
        val barColor = when {
            minutesAway <= 5  -> "#F44336"
            minutesAway <= 10 -> "#FF9800"
            else               -> "#1565C0"
        }
        (binding.progressArrival.progressDrawable as? android.graphics.drawable.LayerDrawable)
            ?.findDrawableByLayerId(android.R.id.progress)
            ?.setTint(android.graphics.Color.parseColor(barColor))
    }

    private fun drawRouteShape(tripId: String, colorInt: Int) {
        val map = googleMap ?: return
        val trip = GtfsStaticCache.trips[tripId] ?: return
        val shapes = GtfsStaticCache.shapes[trip.shapeId]
        if (shapes != null) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(shapes.map { LatLng(it.lat, it.lon) })
                    .color(colorInt)
                    .width(10f)
                    .geodesic(false)
            )
        }

        val stopTimes = GtfsStaticCache.stopTimesByTrip[tripId] ?: return
        stopTimes.take(8).forEach { st ->
            val stop = GtfsStaticCache.stops[st.stopId] ?: return@forEach
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title(stop.name)
                    .snippet("Tap to set arrival alert")
                    .icon(createStopDot())
            )
            marker?.tag = stop.stopId
            if (marker != null) stopMarkers.add(marker)
        }
    }

    private fun createBusDot(color: Int): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val w = (72 * density).toInt()
        val h = (44 * density).toInt()
        val r = 6 * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bodyRect = RectF(0f, 0f, w - 2*density, h - 2*density)
        canvas.drawRoundRect(bodyRect, r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        canvas.drawRect(6*density, 5*density, w - 8*density, h * 0.52f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(220,255,255,255); style = Paint.Style.FILL })
        val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#212121"); style = Paint.Style.FILL }
        canvas.drawCircle(14*density, h - 2*density - 5*density, 5*density, wheelPaint)
        canvas.drawCircle(w - 16*density, h - 2*density - 5*density, 5*density, wheelPaint)
        canvas.drawRoundRect(bodyRect, r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f*density })
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createStopDot(): BitmapDescriptor {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8F00"); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, fill)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    override fun onResume() {
        super.onResume()
        binding.trackerMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.trackerMap.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.trackerMap.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.trackerMap.onDestroy()
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.trackerMap.onLowMemory()
    }
}

class NextStopsAdapter : RecyclerView.Adapter<NextStopsAdapter.VH>() {
    private var items: List<ScheduleEntry> = emptyList()
    fun submitList(list: List<ScheduleEntry>) { items = list; notifyDataSetChanged() }

    inner class VH(val tv: android.widget.TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = android.widget.TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(32, 16, 32, 16)
            textSize = 13f
        }
        return VH(tv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.tv.text = "${e.etaLabel}  •  Route ${e.routeShortName}  ${e.headsign}"
    }
}

class BusTrackerViewModelFactory(
    private val vehicleId: String,
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BusTrackerViewModel(vehicleId, context) as T
    }
}
