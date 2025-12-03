package com.bearbeneman.soilsensor.network.model

import com.squareup.moshi.Json

data class LiveDataResponse(
    val raw: Int,
    val moisture: Int,
    val time: String,
    val ip: String,
    val wet: Int,
    val dry: Int,
    val interval: Int,
    val maxPoints: Int,
    @Json(name = "notifCooldown") val notifCooldown: Long,
    @Json(name = "alertLow") val alertLow: Int,
    @Json(name = "alertHigh") val alertHigh: Int,
    @Json(name = "alertsEnabled") val alertsEnabled: Boolean
)

data class HistoryPoint(
    @Json(name = "t") val timestamp: Long,
    @Json(name = "m") val moisture: Int
)

data class HistoryResponse(
    val maxPoints: Int,
    val points: List<HistoryPoint>
)

data class ConfigResponse(
    val ok: Boolean,
    val wet: Int,
    val dry: Int,
    val cooldown: Long,
    @Json(name = "alertLow") val alertLow: Int,
    @Json(name = "alertHigh") val alertHigh: Int,
    @Json(name = "alertsEnabled") val alertsEnabled: Boolean
)

