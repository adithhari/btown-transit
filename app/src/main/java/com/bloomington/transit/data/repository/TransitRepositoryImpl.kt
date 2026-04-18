package com.bloomington.transit.data.repository

import android.util.Log
import com.bloomington.transit.data.api.GtfsRtParser
import com.bloomington.transit.data.api.NetworkModule
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransitRepositoryImpl : TransitRepository {

    private val service = NetworkModule.realtimeService

    override suspend fun getVehiclePositions(): List<VehiclePosition> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = service.getVehiclePositions().bytes()
                Log.d("TransitRepo", "Vehicle positions response: ${bytes.size} bytes")
                GtfsRtParser.parseVehiclePositions(bytes)
            } catch (e: Exception) {
                Log.e("TransitRepo", "Vehicle positions fetch failed: ${e.message}", e)
                emptyList()
            }
        }

    override suspend fun getTripUpdates(): List<TripUpdate> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = service.getTripUpdates().bytes()
                GtfsRtParser.parseTripUpdates(bytes)
            } catch (e: Exception) {
                Log.e("TransitRepo", "Trip updates fetch failed", e)
                emptyList()
            }
        }
}
