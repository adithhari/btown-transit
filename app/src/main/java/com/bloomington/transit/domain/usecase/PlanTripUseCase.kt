package com.bloomington.transit.domain.usecase

import android.util.Log
import com.bloomington.transit.data.local.GtfsStaticCache
import java.util.Calendar

data class RouteLeg(
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val boardStopId: String,
    val boardStopName: String,
    val alightStopId: String,
    val alightStopName: String,
    val departureTimeStr: String,
    val arrivalTimeStr: String,
    val departureSec: Long,
    val arrivalSec: Long,
    val color: String = ""
)

data class JourneyPlan(val legs: List<RouteLeg>) {
    val totalDurationMin: Int get() =
        if (legs.isEmpty()) 0
        else ((legs.last().arrivalSec - legs.first().departureSec) / 60).toInt()
    val transferCount: Int get() = legs.size - 1
    val departureStr: String get() = legs.firstOrNull()?.departureTimeStr ?: ""
    val arrivalStr: String get() = legs.lastOrNull()?.arrivalTimeStr ?: ""
}

class PlanTripUseCase {

    /**
     * @param departAtSec  Seconds since midnight to depart at (null = now). Used for "Leave at".
     * @param arriveByAtSec Seconds since midnight to arrive by (null = no constraint). Used for "Arrive by".
     */
    operator fun invoke(
        originStopId: String,
        destStopId: String,
        departAtSec: Long? = null,
        arriveByAtSec: Long? = null
    ): List<JourneyPlan> {
        val stopToRoutes = buildStopToRoutes()

        var results: List<JourneyPlan>
        if (arriveByAtSec != null) {
            // For "Arrive by": search the 2-hour window ending at the target so we find trips
            // that actually arrive close to the requested time, not early-morning outliers.
            val windowStart = maxOf(0L, arriveByAtSec - 7200L)
            results = runBfs(originStopId, destStopId, stopToRoutes, windowStart,
                maxResults = 20, pruningWindowSec = 3600L)
            // If nothing found in that window, widen to 4 hours before target
            if (results.isEmpty()) {
                val widerStart = maxOf(0L, arriveByAtSec - 14400L)
                results = runBfs(originStopId, destStopId, stopToRoutes, widerStart,
                    maxResults = 20, pruningWindowSec = 7200L)
            }
        } else {
            val fromSec = if (departAtSec != null) departAtSec else nowMidnightSec()
            results = runBfs(originStopId, destStopId, stopToRoutes, fromSec)
            if (results.isEmpty()) {
                // Fallback for "Leave at / now": retry from midnight
                results = runBfs(originStopId, destStopId, stopToRoutes, 0L)
            }
        }

        var filtered = results
            .distinctBy { plan -> plan.legs.map { "${it.routeId}|${it.boardStopId}|${it.alightStopId}" } }

        // For "Arrive by": sort by closest arrival to the target time.
        // Prefer journeys arriving before the target, then by proximity, then fewer transfers.
        if (arriveByAtSec != null) {
            filtered = filtered
                .sortedWith(compareBy(
                    { if (it.legs.last().arrivalSec <= arriveByAtSec) 0 else 1 },
                    { Math.abs(it.legs.last().arrivalSec - arriveByAtSec) },
                    { it.transferCount }
                ))
        } else {
            filtered = filtered.sortedWith(
                compareBy({ it.transferCount }, { it.legs.firstOrNull()?.departureSec ?: 0L })
            )
        }

        // Dominance pruning and wait filtering only apply to "Leave at / now" mode.
        // For "Arrive by", we want the closest arrival to the target — don't prune by duration.
        if (arriveByAtSec == null) {
            // Remove dominated journeys: drop any journey where another has
            // strictly fewer (or equal) transfers AND strictly shorter (or equal) total duration.
            filtered = filtered.filter { candidate ->
                filtered.none { other ->
                    other !== candidate &&
                    other.transferCount <= candidate.transferCount &&
                    other.totalDurationMin <= candidate.totalDurationMin &&
                    (other.transferCount < candidate.transferCount || other.totalDurationMin < candidate.totalDurationMin)
                }
            }

            // If a direct route exists, drop transfer routes whose worst transfer wait > 20 min —
            // the passenger can just wait for the next direct bus instead.
            val hasDirect = filtered.any { it.transferCount == 0 }
            if (hasDirect) {
                filtered = filtered.filter { journey ->
                    if (journey.transferCount == 0) return@filter true
                    val maxWaitMin = (0 until journey.legs.size - 1).maxOfOrNull { i ->
                        ((journey.legs[i + 1].departureSec - journey.legs[i].arrivalSec) / 60).toInt()
                    } ?: 0
                    maxWaitMin <= 20
                }
            }
        }

        // If the user is searching from "now" and the first departure is < 10 min away,
        // also fetch the next bus so they have a fallback if they miss it.
        if (departAtSec == null && arriveByAtSec == null && filtered.isNotEmpty()) {
            val nowSec = nowMidnightSec()
            val firstDep = filtered.first().legs.first().departureSec
            if (firstDep - nowSec in 0L until 600L) {   // within 10 minutes
                val nextResults = runBfs(originStopId, destStopId, stopToRoutes, firstDep + 60L)
                val nextJourneys = nextResults
                    .distinctBy { plan -> plan.legs.map { "${it.routeId}|${it.boardStopId}|${it.alightStopId}" } }
                    .filter { next -> filtered.none { it.legs.first().departureSec == next.legs.first().departureSec } }
                if (nextJourneys.isNotEmpty()) {
                    filtered = (filtered + nextJourneys.take(2))
                        .sortedWith(compareBy({ it.legs.firstOrNull()?.departureSec ?: 0L }))
                }
            }
        }

        return filtered.take(5)
    }

    private fun runBfs(
        originStopId: String,
        destStopId: String,
        stopToRoutes: Map<String, Set<String>>,
        fromSec: Long,
        maxResults: Int = 6,
        pruningWindowSec: Long = 900L
    ): List<JourneyPlan> {
        data class State(
            val stopId: String,
            val arrivalSec: Long,
            val legs: List<RouteLeg>,
            val usedRouteIds: Set<String>
        )

        val queue = ArrayDeque<State>()
        queue.add(State(originStopId, fromSec, emptyList(), emptySet()))

        val bestArrival = mutableMapOf(originStopId to fromSec)
        val results = mutableListOf<JourneyPlan>()

        while (queue.isNotEmpty() && results.size < maxResults) {
            val (currentStop, arrivalSec, legs, usedRoutes) = queue.removeFirst()
            if (legs.size >= 3) continue  // max 2 transfers

            val transferBuffer = if (legs.isEmpty()) 0L else 120L
            val earliestBoard = arrivalSec + transferBuffer

            val stopTimes = GtfsStaticCache.stopTimesByStop[currentStop] ?: continue

            // Pick the earliest upcoming trip per (route, boardingSequence) pair.
            // Keying by sequence handles loop routes where the same stop appears twice
            // (e.g. Route 12 passes stop 45399 at seq=1 then again at seq=13 — both must be tried).
            val boardingKey = mutableMapOf<String,
                    Pair<com.bloomington.transit.data.model.GtfsStopTime, Long>>()

            for (st in stopTimes) {
                val trip = GtfsStaticCache.trips[st.tripId] ?: continue
                if (trip.routeId in usedRoutes) continue
                val depSec = gtfsTimeToSec(st.departureTime)
                if (depSec < earliestBoard) continue

                val key = "${trip.routeId}|${st.stopSequence}"
                val prev = boardingKey[key]
                if (prev == null || depSec < prev.second) {
                    boardingKey[key] = Pair(st, depSec)
                }
            }

            for ((_, pair) in boardingKey) {
                val (boardSt, depSec) = pair
                val trip = GtfsStaticCache.trips[boardSt.tripId] ?: continue
                val routeId = trip.routeId
                val route = GtfsStaticCache.routes[routeId]
                val tripStops = GtfsStaticCache.stopTimesByTrip[boardSt.tripId]
                    ?.sortedBy { it.stopSequence } ?: continue
                val subseq = tripStops.filter { it.stopSequence > boardSt.stopSequence }

                // Direct reach: destination is on this trip
                val destSt = subseq.find { it.stopId == destStopId }
                if (destSt != null) {
                    val arrSec = gtfsTimeToSec(destSt.arrivalTime)
                    results.add(JourneyPlan(legs + makeLeg(
                        routeId, route?.shortName ?: routeId, trip.headsign, route?.color ?: "",
                        currentStop, destStopId,
                        boardSt.departureTime, destSt.arrivalTime, depSec, arrSec
                    )))
                    continue
                }

                // Transfer: look for stops where a new route is available
                for (nextSt in subseq) {
                    val nextStopId = nextSt.stopId
                    val routesHere = stopToRoutes[nextStopId] ?: continue
                    val newRoutes = routesHere - usedRoutes - routeId
                    if (newRoutes.isEmpty()) continue

                    val arrSec = gtfsTimeToSec(nextSt.arrivalTime)
                    val prevBest = bestArrival[nextStopId]
                    if (prevBest != null && arrSec > prevBest + pruningWindowSec) continue
                    if (prevBest == null || arrSec < prevBest) bestArrival[nextStopId] = arrSec

                    queue.add(State(
                        nextStopId, arrSec,
                        legs + makeLeg(
                            routeId, route?.shortName ?: routeId, trip.headsign, route?.color ?: "",
                            currentStop, nextStopId,
                            boardSt.departureTime, nextSt.arrivalTime, depSec, arrSec
                        ),
                        usedRoutes + routeId
                    ))
                }
            }
        }

        return results
    }

    private fun makeLeg(
        routeId: String, shortName: String, headsign: String, color: String,
        boardStopId: String, alightStopId: String,
        depTimeStr: String, arrTimeStr: String,
        depSec: Long, arrSec: Long
    ) = RouteLeg(
        routeId = routeId,
        routeShortName = shortName,
        headsign = headsign,
        boardStopId = boardStopId,
        boardStopName = GtfsStaticCache.stops[boardStopId]?.name ?: boardStopId,
        alightStopId = alightStopId,
        alightStopName = GtfsStaticCache.stops[alightStopId]?.name ?: alightStopId,
        departureTimeStr = formatTime(depTimeStr),
        arrivalTimeStr = formatTime(arrTimeStr),
        departureSec = depSec,
        arrivalSec = arrSec,
        color = color
    )

    private fun buildStopToRoutes(): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        for ((tripId, trip) in GtfsStaticCache.trips) {
            val stopTimes = GtfsStaticCache.stopTimesByTrip[tripId] ?: continue
            for (st in stopTimes) {
                map.getOrPut(st.stopId) { mutableSetOf() }.add(trip.routeId)
            }
        }
        return map
    }

    internal fun gtfsTimeToSec(timeStr: String): Long {
        val parts = timeStr.split(":").map { it.toLongOrNull() ?: 0L }
        return parts.getOrElse(0) { 0L } * 3600L +
                parts.getOrElse(1) { 0L } * 60L +
                parts.getOrElse(2) { 0L }
    }

    private fun nowMidnightSec(): Long {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 3600L +
                cal.get(Calendar.MINUTE) * 60L +
                cal.get(Calendar.SECOND)
    }

    private fun formatTime(timeStr: String): String {
        val parts = timeStr.split(":").map { it.toIntOrNull() ?: 0 }
        val h = parts.getOrElse(0) { 0 } % 24
        val m = parts.getOrElse(1) { 0 }
        val amPm = if (h < 12) "AM" else "PM"
        val h12 = if (h == 0) 12 else if (h > 12) h - 12 else h
        return "$h12:${m.toString().padStart(2, '0')} $amPm"
    }
}
