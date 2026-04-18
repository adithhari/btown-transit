package com.bloomington.transit.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET

interface GtfsRealtimeService {

    @GET("position_updates.pb")
    suspend fun getVehiclePositions(): ResponseBody

    @GET("trip_updates.pb")
    suspend fun getTripUpdates(): ResponseBody
}
