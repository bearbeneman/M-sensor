package com.bearbeneman.soilsensor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bearbeneman.soilsensor.data.SoilRepository
import com.bearbeneman.soilsensor.network.model.HistoryPoint
import java.util.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(private val repository: SoilRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    private var liveJob: Job? = null
    private var historyJob: Job? = null
    private val liveBuffer = ArrayDeque<LiveSample>()

    init {
        observeBaseUrl()
        startLivePolling()
        startHistoryPolling()
    }

    private fun observeBaseUrl() {
        viewModelScope.launch {
            repository.baseUrl.collectLatest { base ->
                _uiState.update { it.copy(baseUrl = base) }
            }
        }
    }

    private fun startLivePolling() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (true) {
                // First try local ESP32 on the LAN.
                val localResult = repository.fetchLiveLocal()
                var usedRemote = false

                localResult
                    .onSuccess { response ->
                        appendLiveSample(response.moisture)
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.LOCAL,
                                sensorName = response.name ?: it.sensorName,
                                moisture = response.moisture,
                                raw = response.raw,
                                lastUpdated = response.time,
                                ip = response.ip,
                                wet = response.wet,
                                dry = response.dry,
                                intervalMs = response.interval.toLong(),
                                cooldownMs = response.notifCooldown,
                                alertLow = response.alertLow,
                                alertHigh = response.alertHigh,
                                alertsEnabled = response.alertsEnabled,
                                liveSamples = liveBuffer.toList(),
                                errorMessage = null
                            )
                        }
                    }
                    .onFailure {
                        usedRemote = true
                    }

                if (usedRemote) {
                    val remoteResult = repository.fetchLiveRemote()
                    remoteResult.onSuccess { response ->
                        appendLiveSample(response.moisture)
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.REMOTE,
                                sensorName = response.name ?: it.sensorName,
                                moisture = response.moisture,
                                raw = response.raw,
                                lastUpdated = response.time,
                                ip = response.ip,
                                wet = response.wet,
                                dry = response.dry,
                                intervalMs = response.interval.toLong(),
                                cooldownMs = response.notifCooldown,
                                alertLow = response.alertLow,
                                alertHigh = response.alertHigh,
                                alertsEnabled = response.alertsEnabled,
                                liveSamples = liveBuffer.toList(),
                                errorMessage = null
                            )
                        }
                    }.onFailure { ex ->
                        _uiState.update {
                            it.copy(
                                status = ConnectionStatus.OFFLINE,
                                errorMessage = ex.message ?: "Failed to reach ESP32 or cloud backend"
                            )
                        }
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startHistoryPolling() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            while (true) {
                fetchHistory()
                delay(HISTORY_INTERVAL_MS)
            }
        }
    }

    fun refreshHistoryOnce() {
        viewModelScope.launch { fetchHistory() }
    }

    private suspend fun fetchHistory() {
        _uiState.update { it.copy(isHistoryLoading = true) }
        val localResult = repository.fetchHistoryLocal()
        var usedRemote = false

        localResult.onSuccess { response ->
            val sorted = response.points
                .filter { it.timestamp > 0 }
                .sortedBy { it.timestamp }
            val trimmed = if (sorted.isNotEmpty()) {
                val newest = sorted.last().timestamp
                val cutoff = newest - HISTORY_WINDOW_SECONDS
                sorted.filter { it.timestamp >= cutoff }
            } else emptyList()
            _uiState.update {
                it.copy(
                    history = trimmed,
                    isHistoryLoading = false,
                    errorMessage = null
                )
            }
        }.onFailure {
            usedRemote = true
        }

        if (usedRemote) {
            val remoteResult = repository.fetchHistoryRemote()
            remoteResult.onSuccess { response ->
                val sorted = response.points
                    .filter { it.timestamp > 0 }
                    .sortedBy { it.timestamp }
                val trimmed = if (sorted.isNotEmpty()) {
                    val newest = sorted.last().timestamp
                    val cutoff = newest - HISTORY_WINDOW_SECONDS
                    sorted.filter { it.timestamp >= cutoff }
                } else emptyList()
                _uiState.update {
                    it.copy(
                        history = trimmed,
                        isHistoryLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isHistoryLoading = false,
                        errorMessage = ex.message
                            ?: "Failed to load history from device or cloud"
                    )
                }
            }
        }
    }

    fun applyCooldown(ms: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingConfig = true) }
            val result = repository.updateConfig(cooldownMs = ms)
            result.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        cooldownMs = response.cooldown,
                        wet = response.wet,
                        dry = response.dry,
                        sensorName = response.name ?: it.sensorName,
                        alertLow = response.alertLow,
                        alertHigh = response.alertHigh,
                        alertsEnabled = response.alertsEnabled,
                        isApplyingConfig = false
                    )
                }
                _events.emit(UiEvent.Message("Cooldown updated"))
            }.onFailure { ex ->
                _uiState.update { it.copy(isApplyingConfig = false) }
                _events.emit(UiEvent.Message(ex.message ?: "Failed to update cooldown"))
            }
        }
    }

    fun applyCalibration(wet: Int?, dry: Int?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingConfig = true) }
            val result = repository.updateConfig(wet = wet, dry = dry)
            result.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        wet = response.wet,
                        dry = response.dry,
                        sensorName = response.name ?: it.sensorName,
                        alertLow = response.alertLow,
                        alertHigh = response.alertHigh,
                        alertsEnabled = response.alertsEnabled,
                        isApplyingConfig = false
                    )
                }
                _events.emit(UiEvent.Message("Calibration saved"))
            }.onFailure { ex ->
                _uiState.update { it.copy(isApplyingConfig = false) }
                _events.emit(UiEvent.Message(ex.message ?: "Calibration failed"))
            }
        }
    }

    fun applyAlertSettings(enabled: Boolean, low: Int, high: Int) {
        if (low < 0 || high > 100 || low >= high) {
            viewModelScope.launch {
                _events.emit(UiEvent.Message("Thresholds must be between 0-100 and low < high"))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingConfig = true) }
            val result = repository.updateConfig(
                alertLow = low,
                alertHigh = high,
                alertsEnabled = enabled
            )
            result.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        alertLow = response.alertLow,
                        alertHigh = response.alertHigh,
                        alertsEnabled = response.alertsEnabled,
                        wet = response.wet,
                        dry = response.dry,
                        cooldownMs = response.cooldown,
                        sensorName = response.name ?: it.sensorName,
                        isApplyingConfig = false
                    )
                }
                _events.emit(UiEvent.Message("Alert settings saved"))
            }.onFailure { ex ->
                _uiState.update { it.copy(isApplyingConfig = false) }
                _events.emit(UiEvent.Message(ex.message ?: "Failed to save alert settings"))
            }
        }
    }

    fun updateSensorName(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingConfig = true) }
            val result = repository.updateConfig(sensorName = name)
            result.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        sensorName = response.name ?: name,
                        isApplyingConfig = false
                    )
                }
                _events.emit(UiEvent.Message("Sensor name updated"))
            }.onFailure { ex ->
                _uiState.update { it.copy(isApplyingConfig = false) }
                _events.emit(UiEvent.Message(ex.message ?: "Failed to update sensor name"))
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingConfig = true) }
            val result = repository.updateConfig(clearHistory = true)
            result.onSuccess {
                liveBuffer.clear()
                _uiState.update {
                    it.copy(
                        history = emptyList(),
                        liveSamples = emptyList(),
                        isHistoryLoading = false,
                        isApplyingConfig = false
                    )
                }
                _events.emit(UiEvent.Message("History cleared"))
            }.onFailure { ex ->
                _uiState.update { it.copy(isApplyingConfig = false) }
                _events.emit(UiEvent.Message(ex.message ?: "Failed to clear history"))
            }
        }
    }

    fun saveBaseUrl(url: String) {
        repository.updateBaseUrl(url)
        viewModelScope.launch {
            _events.emit(UiEvent.Message("Base URL saved"))
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val HISTORY_INTERVAL_MS = 60_000L
        private const val HISTORY_WINDOW_SECONDS = 10 * 24 * 60 * 60L
        private const val LIVE_WINDOW_MS = 5 * 60 * 1000L
    }

    private fun appendLiveSample(moisture: Int) {
        val now = System.currentTimeMillis()
        liveBuffer.addLast(LiveSample(now, moisture))
        while (liveBuffer.isNotEmpty() && now - liveBuffer.first().timestamp > LIVE_WINDOW_MS) {
            liveBuffer.removeFirst()
        }
    }
}

data class DashboardUiState(
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val baseUrl: String = "",
    val sensorName: String? = null,
    val moisture: Int? = null,
    val raw: Int? = null,
    val lastUpdated: String? = null,
    val ip: String? = null,
    val wet: Int? = null,
    val dry: Int? = null,
    val intervalMs: Long? = null,
    val cooldownMs: Long? = null,
    val alertLow: Int? = null,
    val alertHigh: Int? = null,
    val alertsEnabled: Boolean = true,
    val liveSamples: List<LiveSample> = emptyList(),
    val history: List<HistoryPoint> = emptyList(),
    val errorMessage: String? = null,
    val isHistoryLoading: Boolean = false,
    val isApplyingConfig: Boolean = false
)

enum class ConnectionStatus { CONNECTING, LOCAL, REMOTE, OFFLINE }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

data class LiveSample(
    val timestamp: Long,
    val moisture: Int
)

class DashboardViewModelFactory(
    private val repository: SoilRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            return DashboardViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

