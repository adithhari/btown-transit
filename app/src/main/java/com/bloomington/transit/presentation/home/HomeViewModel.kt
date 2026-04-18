package com.bloomington.transit.presentation.home

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Stores raw arrival timestamps so the Fragment can recompute ETAs without a network call
data class NearbyBusItem(
    val routeId: String,
    val routeShortName: String,
    val routeColor: Int,
    val headsign: String,
    val boardingStopId: String,
    val boardingStopName: String,
    val distanceMeters: Float,
    val liveArrivalSec: Long,   // absolute Unix seconds — Fragment computes minutes from this
    val tripId: String,
    val entry: ScheduleEntry
)

data class HomeUiState(
    val nearbyBuses: List<NearbyBusItem> = emptyList(),
    val isLoading: Boolean = true,
    val lastFetchMs: Long = 0L   // changes every network fetch → forces StateFlow emission
)

class HomeViewModel(private val context: Context) : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    private var userLat = 0.0
    private var userLon = 0.0

    init {
        // Network refresh every 15 seconds
        viewModelScope.launch {
            while (true) {
                if (userLat != 0.0 || userLon != 0.0) fetchNetworkData()
                delay(3_000)
            }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        viewModelScope.launch { fetchNetworkData() }
    }

    private suspend fun fetchNetworkData() {
        try {
            val buses = withContext(Dispatchers.Default) { computeNearbyBuses() }
            _uiState.value = HomeUiState(
                nearbyBuses  = buses,
                isLoading    = false,
                lastFetchMs  = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("HomeViewModel", "fetch error: ${e.message}")
            _uiState.value = _uiState.value.copy(isLoading = false, lastFetchMs = System.currentTimeMillis())
        }
    }

    private suspend fun computeNearbyBuses(): List<NearbyBusItem> {
        val nowSec = System.currentTimeMillis() / 1000L
        val updates = try { getTripUpdates() } catch (_: Exception) { emptyList() }

        val nearbyStops = GtfsStaticCache.stops.values
            .map { stop ->
                val dist = FloatArray(1)
                Location.distanceBetween(userLat, userLon, stop.lat, stop.lon, dist)
                stop to dist[0]
            }
            .filter { it.second <= 600f }
            .sortedBy { it.second }
            .take(20)

        val seenTrips = mutableSetOf<String>()
        val items = mutableListOf<NearbyBusItem>()

        for ((stop, distMeters) in nearbyStops) {
            val entries = getSchedule(stop.stopId, updates)
                .filter { !it.isPast && (it.liveArrivalSec - nowSec) < 90 * 60 }
                .take(3)

            for (entry in entries) {
                if (entry.tripId in seenTrips) continue
                seenTrips.add(entry.tripId)

                val route = GtfsStaticCache.routes[entry.routeId]
                val color = routeColor(entry.routeId, route?.color)

                items.add(NearbyBusItem(
                    routeId          = entry.routeId,
                    routeShortName   = entry.routeShortName,
                    routeColor       = color,
                    headsign         = entry.headsign,
                    boardingStopId   = stop.stopId,
                    boardingStopName = stop.name,
                    distanceMeters   = distMeters,
                    liveArrivalSec   = entry.liveArrivalSec,
                    tripId           = entry.tripId,
                    entry            = entry
                ))
            }
        }

        return items.sortedBy { it.liveArrivalSec }.take(25)
    }

    companion object {
        /** Returns route color, falling back to a pleasant hue derived from the routeId. */
        fun routeColor(routeId: String, colorHex: String?): Int {
            if (!colorHex.isNullOrBlank()) {
                runCatching { return android.graphics.Color.parseColor("#$colorHex") }
            }
            val hue = ((routeId.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % 300).toFloat() + 30f
            return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.80f))
        }
    }
}
