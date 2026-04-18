package com.bloomington.transit.domain.usecase

import android.location.Location
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.VehiclePosition

class CheckArrivalProximityUseCase {

    data class ProximityResult(
        val isWithinThreshold: Boolean,
        val distanceMeters: Float,
        val vehicleId: String,
        val stopId: String,
        val stopName: String,
        val routeShortName: String
    )

    operator fun invoke(
        vehicleId: String,
        stopId: String,
        thresholdMeters: Int,
        vehicles: List<VehiclePosition>
    ): ProximityResult? {
        val vehicle = vehicles.find { it.vehicleId == vehicleId } ?: return null
        val stop = GtfsStaticCache.stops[stopId] ?: return null
        val route = GtfsStaticCache.routes[vehicle.routeId]

        val results = FloatArray(1)
        Location.distanceBetween(
            vehicle.lat, vehicle.lon,
            stop.lat, stop.lon,
            results
        )
        val distance = results[0]

        return ProximityResult(
            isWithinThreshold = distance <= thresholdMeters,
            distanceMeters = distance,
            vehicleId = vehicleId,
            stopId = stopId,
            stopName = stop.name,
            routeShortName = route?.shortName ?: vehicle.routeId
        )
    }
}
