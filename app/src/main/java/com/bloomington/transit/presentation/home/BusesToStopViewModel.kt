package com.bloomington.transit.presentation.home

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class BusToStopOption(
    val routeId: String,
    val routeShortName: String,
    val routeColor: Int,
    val headsign: String,
    val boardingStopId: String,
    val boardingStopName: String,
    val boardingDistanceMeters: Float,
    val departureMinutes: Int,
    val arrivalAtDestMinutes: Int,
    val tripId: String
)

data class BusesToStopUiState(
    val options: List<BusToStopOption> = emptyList(),
    val boardingStopName: String = "",
    val isLoading: Boolean = true
)

class BusesToStopViewModel(
    private val context: Context,
    private val destStopId: String
) : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getTripUpdates = GetTripUpdatesUseCase(repository)

    private val _uiState = MutableStateFlow(BusesToStopUiState())
    val uiState: StateFlow<BusesToStopUiState> = _uiState

    fun load(userLat: Double, userLon: Double) {
        viewModelScope.launch {
            try {
                val options = withContext(Dispatchers.Default) {
                    computeOptions(userLat, userLon)
                }
                val boardingName = options.firstOrNull()?.boardingStopName ?: ""
                _uiState.value = BusesToStopUiState(
                    options = options,
                    boardingStopName = boardingName,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("BusesToStopVM", "load error: ${e.message}")
                _uiState.value = BusesToStopUiState(isLoading = false)
            }
        }
    }

    private suspend fun computeOptions(userLat: Double, userLon: Double): List<BusToStopOption> {
        val nowSec = System.currentTimeMillis() / 1000L
        val updates = try { getTripUpdates() } catch (_: Exception) { emptyList() }
        val activeServiceIds = activeTodayServiceIds()

        // Find nearby boarding stops (within 600m)
        val nearbyStops = GtfsStaticCache.stops.values
            .map { stop ->
                val dist = FloatArray(1)
                Location.distanceBetween(userLat, userLon, stop.lat, stop.lon, dist)
                stop to dist[0]
            }
            .filter { it.second <= 600f }
            .sortedBy { it.second }
            .take(15)

        val seenTrips = mutableSetOf<String>()
        val results = mutableListOf<BusToStopOption>()

        for ((boardingStop, distMeters) in nearbyStops) {
            val stopTimes = GtfsStaticCache.stopTimesByStop[boardingStop.stopId] ?: continue

            for (st in stopTimes) {
                if (st.tripId in seenTrips) continue
                val trip = GtfsStaticCache.trips[st.tripId] ?: continue
                if (!activeServiceIds.contains(trip.serviceId)) continue

                // Merge with realtime
                val stu = updates.find { it.tripId == st.tripId }
                    ?.stopTimeUpdates?.find { it.stopId == boardingStop.stopId }
                val departureSec = ArrivalTimeCalculator.resolvedArrivalSec(st, stu)
                if (departureSec <= nowSec) continue                       // already gone
                if ((departureSec - nowSec) > 90 * 60) continue           // more than 90 min away

                // Check if this trip also serves the destination AFTER the boarding stop
                val tripStops = GtfsStaticCache.stopTimesByTrip[st.tripId] ?: continue
                val destStopTime = tripStops.find {
                    it.stopId == destStopId && it.stopSequence > st.stopSequence
                } ?: continue

                seenTrips.add(st.tripId)

                val destStu = updates.find { it.tripId == st.tripId }
                    ?.stopTimeUpdates?.find { it.stopId == destStopId }
                val destArrSec = ArrivalTimeCalculator.resolvedArrivalSec(destStopTime, destStu)

                val route = GtfsStaticCache.routes[trip.routeId]
                val color = try { android.graphics.Color.parseColor("#${route?.color ?: "1565C0"}") }
                            catch (_: Exception) { android.graphics.Color.parseColor("#1565C0") }

                results.add(BusToStopOption(
                    routeId              = trip.routeId,
                    routeShortName       = route?.shortName ?: trip.routeId,
                    routeColor           = color,
                    headsign             = trip.headsign,
                    boardingStopId       = boardingStop.stopId,
                    boardingStopName     = boardingStop.name,
                    boardingDistanceMeters = distMeters,
                    departureMinutes     = ((departureSec - nowSec) / 60L).toInt(),
                    arrivalAtDestMinutes = ((destArrSec - nowSec) / 60L).toInt(),
                    tripId               = st.tripId
                ))
            }
        }

        return results.sortedBy { it.departureMinutes }.take(10)
    }

    private fun activeTodayServiceIds(): Set<String> {
        val cal = Calendar.getInstance()
        return GtfsStaticCache.calendars.values.filter { c ->
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY    -> c.monday
                Calendar.TUESDAY   -> c.tuesday
                Calendar.WEDNESDAY -> c.wednesday
                Calendar.THURSDAY  -> c.thursday
                Calendar.FRIDAY    -> c.friday
                Calendar.SATURDAY  -> c.saturday
                Calendar.SUNDAY    -> c.sunday
                else               -> false
            }
        }.map { it.serviceId }.toSet()
    }
}
