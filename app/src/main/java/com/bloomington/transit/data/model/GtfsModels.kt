package com.bloomington.transit.data.model

data class GtfsRoute(
    val routeId: String,
    val shortName: String,
    val longName: String,
    val color: String,      // hex without #, e.g. "FF0000"
    val textColor: String
)

data class GtfsStop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val code: String = ""
)

data class GtfsStopTime(
    val tripId: String,
    val arrivalTime: String,    // HH:MM:SS (may exceed 24h for overnight)
    val departureTime: String,
    val stopId: String,
    val stopSequence: Int
)

data class GtfsTrip(
    val routeId: String,
    val serviceId: String,
    val tripId: String,
    val headsign: String,
    val shapeId: String,
    val directionId: Int
)

data class GtfsShape(
    val shapeId: String,
    val lat: Double,
    val lon: Double,
    val sequence: Int
)

data class GtfsCalendar(
    val serviceId: String,
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
    val startDate: String,  // YYYYMMDD
    val endDate: String
)
