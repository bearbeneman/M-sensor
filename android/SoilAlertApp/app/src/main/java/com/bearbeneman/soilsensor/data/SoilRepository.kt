package com.bearbeneman.soilsensor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.bearbeneman.soilsensor.network.SoilService
import com.bearbeneman.soilsensor.network.model.ConfigResponse
import com.bearbeneman.soilsensor.network.model.HistoryResponse
import com.bearbeneman.soilsensor.network.model.LiveDataResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class SoilRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(loadBaseUrl())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val service: SoilService

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        service = Retrofit.Builder()
            .baseUrl("http://localhost/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()
            .create(SoilService::class.java)
    }

    private fun loadBaseUrl(): String {
        val saved = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        return normalizeBaseUrl(saved ?: DEFAULT_BASE_URL)
    }

    fun updateBaseUrl(raw: String) {
        val normalized = normalizeBaseUrl(raw)
        prefs.edit { putString(KEY_BASE_URL, normalized) }
        _baseUrl.value = normalized
    }

    suspend fun fetchLive(): Result<LiveDataResponse> = runCatching {
        service.fetchLive(resolveUrl(PATH_LIVE))
    }

    suspend fun fetchHistory(): Result<HistoryResponse> = runCatching {
        service.fetchHistory(resolveUrl(PATH_HISTORY))
    }

    suspend fun updateConfig(
        wet: Int? = null,
        dry: Int? = null,
        cooldownMs: Long? = null,
        alertLow: Int? = null,
        alertHigh: Int? = null,
        alertsEnabled: Boolean? = null
    ): Result<ConfigResponse> = runCatching {
        service.updateConfig(
            resolveUrl(PATH_CONFIG),
            wet = wet,
            dry = dry,
            cooldown = cooldownMs?.toInt(),
            alertLow = alertLow,
            alertHigh = alertHigh,
            alerts = alertsEnabled?.let { if (it) 1 else 0 }
        )
    }

    private fun resolveUrl(path: String): String {
        val base = normalizeBaseUrl(baseUrl.value)
        return base + path
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_BASE_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    companion object {
        private const val PREFS_NAME = "soil_repo"
        private const val KEY_BASE_URL = "base_url"
        private const val DEFAULT_BASE_URL = "http://soilmonitor.local/"
        private const val PATH_LIVE = "data"
        private const val PATH_HISTORY = "history"
        private const val PATH_CONFIG = "config"

        @Volatile
        private var INSTANCE: SoilRepository? = null

        fun getInstance(context: Context): SoilRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SoilRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}

