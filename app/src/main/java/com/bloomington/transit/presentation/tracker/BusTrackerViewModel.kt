package com.bloomington.transit.presentation.tracker

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.GetVehiclePositionsUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.bloomington.transit.notification.ArrivalNotificationManager
import com.bloomington.transit.tracking.BusTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TrackerUiState(
    val vehicle: VehiclePosition? = null,
    val nextStops: List<ScheduleEntry> = emptyList(),
    val isLoading: Boolean = true,
    val alertEnabled: Boolean = false,
    val alertStopId: String = ""
)

class BusTrackerViewModel(
    private val vehicleId: String,
    private val context: Context
) : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getVehicles = GetVehiclePositionsUseCase(repository)
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()
    private val notifManager = ArrivalNotificationManager(context)
    private val prefs = PreferencesManager(context)

    private val _uiState = MutableStateFlow(TrackerUiState())
    val uiState: StateFlow<TrackerUiState> = _uiState

    // Tracks which minute milestones (15, 10, 5) have already fired this session
    private val alertedMilestones = mutableSetOf<Int>()
    private var initialStopsAway: Int? = null

    companion object {
        private val MILESTONES = listOf(15, 10, 5)
    }

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val vehicles = getVehicles()
                    val updates = getTripUpdates()
                    val vehicle = vehicles.find { it.vehicleId == vehicleId }

                    val nextStops = if (vehicle != null) {
                        computeNextStops(vehicle, updates)
                    } else emptyList()

                    val alertStopId = prefs.trackedStopId.first()
                    if (alertStopId.isNotEmpty() && vehicle != null) {
                        val etaEntry = nextStops.find { it.stopId == alertStopId }

                        // Update the live tracking notification every cycle
                        val routeShortName = GtfsStaticCache.routes[vehicle.routeId]?.shortName ?: ""
                        val stopName = GtfsStaticCache.stops[alertStopId]?.name ?: alertStopId
                        val targetStopSequence = GtfsStaticCache.stopTimesByTrip[vehicle.tripId]
                            ?.find { it.stopId == alertStopId }
                            ?.stopSequence
                        val stopsAway = targetStopSequence?.let {
                            (it - vehicle.currentStopSequence).coerceAtLeast(0)
                        }
                        if (stopsAway != null) {
                            initialStopsAway = maxOf(initialStopsAway ?: stopsAway, stopsAway, 1)
                        }
                        notifManager.updateLiveTracking(
                            routeShortName = routeShortName,
                            stopName = stopName,
                            distanceMeters = null,
                            etaLabel = etaEntry?.etaLabel ?: "",
                            progressPercent = if (initialStopsAway != null && stopsAway != null) {
                                val totalStops = initialStopsAway ?: 1
                                val completedStops = (totalStops - stopsAway).coerceIn(0, totalStops)
                                ((completedStops * 100f) / totalStops).toInt()
                            } else null
                        )

                        // Fire milestone buzz notifications at 15, 10, and 5 minutes out
                        if (etaEntry != null) {
                            val arrSec = if (etaEntry.liveArrivalSec > 0)
                                etaEntry.liveArrivalSec else etaEntry.scheduledArrivalSec
                            val minutesAway = ((arrSec - System.currentTimeMillis() / 1000L) / 60).toInt()
                            Log.d("BusTracker", "Alert stop ETA: ${minutesAway}min, alerted=$alertedMilestones")

                            for (milestone in MILESTONES) {
                                if (minutesAway in 0..milestone && milestone !in alertedMilestones) {
                                    alertedMilestones.add(milestone)
                                    Log.d("BusTracker", "Firing milestone notification: ${minutesAway}min")
                                    notifManager.notifyMilestone(routeShortName, stopName, minutesAway)
                                }
                            }
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        vehicle = vehicle,
                        nextStops = nextStops,
                        isLoading = false,
                        alertStopId = alertStopId
                    )
                } catch (e: Exception) {
                    Log.e("BusTracker", "Poll error: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    private fun computeNextStops(vehicle: VehiclePosition, updates: List<TripUpdate>): List<ScheduleEntry> {
        val tripStops = GtfsStaticCache.stopTimesByTrip[vehicle.tripId] ?: return emptyList()
        val afterSeq = vehicle.currentStopSequence
        val upcoming = tripStops.filter { it.stopSequence >= afterSeq }.take(10)
        return upcoming.mapNotNull { st ->
            val tripUpdate = updates.find { it.tripId == vehicle.tripId }
            val stu = tripUpdate?.stopTimeUpdates?.find { it.stopId == st.stopId }
            val arrSec = com.bloomington.transit.domain.util.ArrivalTimeCalculator.resolvedArrivalSec(st, stu)
            val stop = GtfsStaticCache.stops[st.stopId] ?: return@mapNotNull null
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val trip = GtfsStaticCache.trips[vehicle.tripId]
            ScheduleEntry(
                stopId = st.stopId,
                stopName = stop.name,
                routeId = vehicle.routeId,
                routeShortName = route?.shortName ?: "",
                headsign = trip?.headsign ?: "",
                scheduledArrivalSec = com.bloomington.transit.domain.util.ArrivalTimeCalculator.stopTimeToUnixSec(st.arrivalTime),
                liveArrivalSec = arrSec,
                etaLabel = com.bloomington.transit.domain.util.ArrivalTimeCalculator.formatEta(arrSec),
                delayMin = 0,
                tripId = vehicle.tripId
            )
        }
    }

    fun setAlert(stopId: String) {
        viewModelScope.launch {
            prefs.setTrackedVehicle(vehicleId, stopId)
            alertedMilestones.clear()
            initialStopsAway = null
            _uiState.value = _uiState.value.copy(alertEnabled = true, alertStopId = stopId)

            val vehicle = _uiState.value.vehicle
            val routeShortName = vehicle?.routeId
                ?.let { GtfsStaticCache.routes[it]?.shortName } ?: vehicle?.routeId ?: ""
            val stopName = GtfsStaticCache.stops[stopId]?.name ?: stopId
            Log.d("BusTracker", "Tracking started: route=$routeShortName stop=$stopName")
            notifManager.notifyTrackingStarted(routeShortName, stopName)
            BusTrackingService.start(context)
        }
    }

    fun clearAlert() {
        viewModelScope.launch {
            prefs.clearTracking()
            alertedMilestones.clear()
            initialStopsAway = null
            notifManager.cancelLiveTracking()
            BusTrackingService.stop(context)
            _uiState.value = _uiState.value.copy(alertEnabled = false, alertStopId = "")
        }
    }
}
