package com.bloomington.transit.data.local

import com.bloomington.transit.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object GtfsStaticCache {

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    var routes: Map<String, GtfsRoute> = emptyMap()
    var stops: Map<String, GtfsStop> = emptyMap()
    var trips: Map<String, GtfsTrip> = emptyMap()
    var shapes: Map<String, List<GtfsShape>> = emptyMap()

    // stopTimesByTrip[tripId] = sorted list of StopTimes
    var stopTimesByTrip: Map<String, List<GtfsStopTime>> = emptyMap()

    // stopTimesByStop[stopId] = list of StopTimes across all trips
    var stopTimesByStop: Map<String, List<GtfsStopTime>> = emptyMap()

    // tripsByRoute[routeId] = list of TripIds
    var tripsByRoute: Map<String, List<String>> = emptyMap()

    var calendars: Map<String, GtfsCalendar> = emptyMap()

    val isLoaded: Boolean get() = routes.isNotEmpty()

    fun markLoaded() { _loaded.value = true }

    fun clear() {
        routes = emptyMap()
        stops = emptyMap()
        trips = emptyMap()
        shapes = emptyMap()
        stopTimesByTrip = emptyMap()
        stopTimesByStop = emptyMap()
        tripsByRoute = emptyMap()
        calendars = emptyMap()
    }
}
