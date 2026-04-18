package com.bloomington.transit.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

interface GtfsStaticService {

    @GET
    suspend fun downloadGtfsZip(@Url url: String): ResponseBody
}
