package com.bloomington.transit.data.repository

import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition

interface TransitRepository {
    suspend fun getVehiclePositions(): List<VehiclePosition>
    suspend fun getTripUpdates(): List<TripUpdate>
}
