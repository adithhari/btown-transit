package com.bloomington.transit.presentation.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.GtfsShape
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.GetVehiclePositionsUseCase
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import com.bloomington.transit.notification.ArrivalNotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MapUiState(
    val vehicles: List<VehiclePosition> = emptyList(),
    val tripUpdates: List<TripUpdate> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdatedMs: Long = 0L
)

class RouteMapViewModel(app: Application) : AndroidViewModel(app) {

    private val repository     = TransitRepositoryImpl()
    private val getVehicles    = GetVehiclePositionsUseCase(repository)
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val prefs          = PreferencesManager(app)
    private val notifManager   = ArrivalNotificationManager(app)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    private val _selectedRouteId = MutableStateFlow("")
    val selectedRouteId: StateFlow<String> = _selectedRouteId

    val favoriteStopIds: StateFlow<Set<String>> = prefs.favoriteStopIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val allRouteIds: List<String>
        get() = GtfsStaticCache.routes.keys.sorted()

    fun selectRoute(routeId: String) { _selectedRouteId.value = routeId }

    /** Last GPS lat/lon seen per vehicle — detect real position changes. */
    private data class LatLon(val lat: Double, val lon: Double)
    private val lastGps = mutableMapOf<String, LatLon>()

    init {
        startPolling()
    }

    // -------------------------------------------------------------------------
    // Poll every 2 s. Snap vehicle to route shape on real GPS changes.
    // The Fragment animates the jump; we just emit accurate positions.
    // -------------------------------------------------------------------------

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val rawVehicles = getVehicles()
                    val updates     = getTripUpdates()
                    val now         = System.currentTimeMillis()

                    // Snap each vehicle to its route shape.
                    // Only recompute when lat/lon actually changes — holds last snapped
                    // position between identical GPS reads.
                    val snapped = rawVehicles.map { v ->
                        val prev = lastGps[v.vehicleId]
                        val moved = prev == null
                            || kotlin.math.abs(prev.lat - v.lat) > 1e-6
                            || kotlin.math.abs(prev.lon - v.lon) > 1e-6
                        if (moved) {
                            lastGps[v.vehicleId] = LatLon(v.lat, v.lon)
                            snapToShape(v)
                        } else {
                            // Position unchanged — return existing snapped marker position
                            _uiState.value.vehicles.find { it.vehicleId == v.vehicleId }
                                ?: snapToShape(v)
                        }
                    }

                    lastGps.keys.retainAll { id -> rawVehicles.any { it.vehicleId == id } }

                    _uiState.value = _uiState.value.copy(
                        vehicles      = snapped,
                        tripUpdates   = updates,
                        isLoading     = false,
                        error         = null,
                        lastUpdatedMs = now
                    )
                    checkTripTracking(rawVehicles)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error     = "Network error. Retrying…"
                    )
                }
                delay(2_000)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snap a vehicle's GPS coordinate to the nearest point on its route shape.
    // This makes the icon sit on the road instead of floating slightly off it.
    // -------------------------------------------------------------------------

    private fun snapToShape(vehicle: VehiclePosition): VehiclePosition {
        val trip  = GtfsStaticCache.trips[vehicle.tripId] ?: return vehicle
        val shape = GtfsStaticCache.shapes[trip.shapeId]
            ?.sortedBy { it.sequence } ?: return vehicle

        // Find the nearest shape point — simple O(n) scan, safe against duplicate points
        var minD2 = Double.MAX_VALUE
        var minIdx = 0
        shape.forEachIndexed { i, pt ->
            val d2 = sq(pt.lat - vehicle.lat) + sq(pt.lon - vehicle.lon)
            if (d2 < minD2) { minD2 = d2; minIdx = i }
        }

        val snapped = shape[minIdx]
        // Guard: if snapped coordinates are somehow invalid, return raw vehicle
        if (snapped.lat.isNaN() || snapped.lon.isNaN()) return vehicle

        return vehicle.copy(lat = snapped.lat, lon = snapped.lon)
    }

    private fun sq(x: Double) = x * x

    // -------------------------------------------------------------------------
    // Trip tracking alert
    // -------------------------------------------------------------------------

    private fun gtfsTimeToSec(timeStr: String): Long {
        val parts = timeStr.split(":").map { it.toLongOrNull() ?: 0L }
        return parts.getOrElse(0){0L}*3600L + parts.getOrElse(1){0L}*60L + parts.getOrElse(2){0L}
    }

    private suspend fun checkTripTracking(vehicles: List<VehiclePosition>) {
        val trackedTripId = prefs.trackedTripId.first()
        val trackedStopId = prefs.trackedStopId.first()
        if (trackedTripId.isEmpty() || trackedStopId.isEmpty()) return

        val vehicle   = vehicles.find { it.tripId == trackedTripId } ?: return
        val tripStops = GtfsStaticCache.stopTimesByTrip[trackedTripId]
            ?.sortedBy { it.stopSequence } ?: return

        val targetIdx  = tripStops.indexOfFirst { it.stopId == trackedStopId }
        if (targetIdx < 0) return
        val currentIdx = tripStops.indexOfLast { it.stopSequence <= vehicle.currentStopSequence }
            .coerceAtLeast(0)

        val stopsAway = targetIdx - currentIdx
        if (stopsAway in 1..4) {
            val arrivalSec  = ArrivalTimeCalculator.stopTimeToUnixSec(tripStops[targetIdx].arrivalTime)
            val minutesAway = ((arrivalSec - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val stop  = GtfsStaticCache.stops[trackedStopId]
            notifManager.notifyTripApproaching(
                tripId         = trackedTripId,
                stopName       = stop?.name ?: trackedStopId,
                routeShortName = route?.shortName ?: vehicle.routeId,
                stopsAway      = stopsAway,
                minutesAway    = minutesAway.toInt()
            )
        } else if (stopsAway <= 0) {
            prefs.clearTracking()
        }
    }
}
