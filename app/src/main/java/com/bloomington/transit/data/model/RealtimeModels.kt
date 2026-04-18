package com.bloomington.transit.data.model

data class VehiclePosition(
    val vehicleId: String,
    val tripId: String,
    val routeId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speed: Float,
    val timestamp: Long,
    val currentStopSequence: Int = 0,
    val label: String = ""
)

data class StopTimeUpdate(
    val stopId: String,
    val stopSequence: Int,
    val arrivalDelaySec: Int,
    val arrivalTime: Long,      // absolute Unix timestamp; 0 if not provided
    val departureDelaySec: Int,
    val departureTime: Long
)

data class TripUpdate(
    val tripId: String,
    val routeId: String,
    val vehicleId: String,
    val timestamp: Long,
    val stopTimeUpdates: List<StopTimeUpdate>
)
