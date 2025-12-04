package com.bearbeneman.soilsensor.network

import com.bearbeneman.soilsensor.network.model.ConfigResponse
import com.bearbeneman.soilsensor.network.model.HistoryResponse
import com.bearbeneman.soilsensor.network.model.LiveDataResponse
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

interface SoilService {
    @GET
    suspend fun fetchLive(@Url url: String): LiveDataResponse

    @GET
    suspend fun fetchHistory(@Url url: String): HistoryResponse

    @GET
    suspend fun updateConfig(
        @Url url: String,
        @Query("wet") wet: Int? = null,
        @Query("dry") dry: Int? = null,
        @Query("cooldown") cooldown: Int? = null,
        @Query("alertLow") alertLow: Int? = null,
        @Query("alertHigh") alertHigh: Int? = null,
        @Query("alerts") alerts: Int? = null,
        @Query("name") name: String? = null,
        @Query("clearHistory") clearHistory: Int? = null
    ): ConfigResponse
}

