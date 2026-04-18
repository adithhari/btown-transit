package com.bloomington.transit.domain.usecase

import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsStopTime
import com.bloomington.transit.data.model.StopTimeUpdate
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import java.util.Calendar

data class ScheduleEntry(
    val stopId: String,
    val stopName: String,
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val scheduledArrivalSec: Long,
    val liveArrivalSec: Long,
    val etaLabel: String,
    val delayMin: Int,
    val tripId: String,
    val isPast: Boolean = false
)

class GetScheduleForStopUseCase {

    /**
     * Returns the full-day schedule for [stopId].
     * If [filterRouteId] is non-empty, only that route's trips are included.
     * Past departures are included with [ScheduleEntry.isPast] = true.
     */
    operator fun invoke(
        stopId: String,
        tripUpdates: List<TripUpdate>,
        filterRouteId: String = ""
    ): List<ScheduleEntry> {
        val nowSec = System.currentTimeMillis() / 1000L

        val stopTimes: List<GtfsStopTime> =
            GtfsStaticCache.stopTimesByStop[stopId] ?: return emptyList()

        val todayServiced = activeTodayServiceIds()
        val activeServices = todayServiced.ifEmpty { GtfsStaticCache.calendars.keys.toSet() }

        val realtimeLookup = mutableMapOf<String, StopTimeUpdate>()
        for (tu in tripUpdates) {
            val stu = tu.stopTimeUpdates.find { it.stopId == stopId }
            if (stu != null) realtimeLookup[tu.tripId] = stu
        }

        return stopTimes.mapNotNull { st ->
            val trip = GtfsStaticCache.trips[st.tripId] ?: return@mapNotNull null
            if (!activeServices.contains(trip.serviceId)) return@mapNotNull null
            if (filterRouteId.isNotEmpty() && trip.routeId != filterRouteId) return@mapNotNull null

            val stu = realtimeLookup[st.tripId]
            val scheduledSec = ArrivalTimeCalculator.stopTimeToUnixSec(st.arrivalTime)
            val liveSec = ArrivalTimeCalculator.resolvedArrivalSec(st, stu)
            val isPast = liveSec < nowSec

            val route = GtfsStaticCache.routes[trip.routeId]
            val stop = GtfsStaticCache.stops[stopId]
            val delayMin = ((liveSec - scheduledSec) / 60L).toInt()

            ScheduleEntry(
                stopId = stopId,
                stopName = stop?.name ?: stopId,
                routeId = trip.routeId,
                routeShortName = route?.shortName ?: trip.routeId,
                headsign = trip.headsign,
                scheduledArrivalSec = scheduledSec,
                liveArrivalSec = liveSec,
                etaLabel = ArrivalTimeCalculator.formatTime(liveSec),
                delayMin = delayMin,
                tripId = st.tripId,
                isPast = isPast
            )
        }.sortedBy { it.liveArrivalSec }
    }

    private fun activeTodayServiceIds(): Set<String> {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return GtfsStaticCache.calendars.values.filter { c ->
            when (dow) {
                Calendar.MONDAY -> c.monday
                Calendar.TUESDAY -> c.tuesday
                Calendar.WEDNESDAY -> c.wednesday
                Calendar.THURSDAY -> c.thursday
                Calendar.FRIDAY -> c.friday
                Calendar.SATURDAY -> c.saturday
                Calendar.SUNDAY -> c.sunday
                else -> false
            }
        }.map { it.serviceId }.toSet()
    }
}
