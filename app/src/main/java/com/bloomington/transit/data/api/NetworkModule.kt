package com.bloomington.transit.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    private const val S3_BASE = "https://s3.amazonaws.com/etatransit.gtfs/bloomingtontransit.etaspot.net/"

    const val GTFS_STATIC_URL = "${S3_BASE}gtfs.zip"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    val realtimeService: GtfsRealtimeService by lazy {
        Retrofit.Builder()
            .baseUrl(S3_BASE)
            .client(okHttpClient)
            .build()
            .create(GtfsRealtimeService::class.java)
    }

    val staticService: GtfsStaticService by lazy {
        Retrofit.Builder()
            .baseUrl(S3_BASE)
            .client(okHttpClient)
            .build()
            .create(GtfsStaticService::class.java)
    }
}
