package com.bearbeneman.soilsensor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bearbeneman.soilsensor.data.SoilRepository
import com.bearbeneman.soilsensor.network.model.HistoryPoint
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
                val result = repository.fetchLive()
                result.onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            status = ConnectionStatus.ONLINE,
                            moisture = response.moisture,
                            raw = response.raw,
                            lastUpdated = response.time,
                            ip = response.ip,
                            wet = response.wet,
                            dry = response.dry,
                            intervalMs = response.interval.toLong(),
                            cooldownMs = response.notifCooldown,
                            errorMessage = null
                        )
                    }
                }.onFailure { ex ->
                    _uiState.update {
                        it.copy(
                            status = ConnectionStatus.OFFLINE,
                            errorMessage = ex.message ?: "Failed to reach ESP32"
                        )
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
        val result = repository.fetchHistory()
        result.onSuccess { response ->
            _uiState.update {
                it.copy(
                    history = response.points,
                    isHistoryLoading = false,
                    errorMessage = null
                )
            }
        }.onFailure { ex ->
            _uiState.update {
                it.copy(
                    isHistoryLoading = false,
                    errorMessage = ex.message ?: "Failed to load history"
                )
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

    fun saveBaseUrl(url: String) {
        repository.updateBaseUrl(url)
        viewModelScope.launch {
            _events.emit(UiEvent.Message("Base URL saved"))
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 200L
        private const val HISTORY_INTERVAL_MS = 60_000L
    }
}

data class DashboardUiState(
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val baseUrl: String = "",
    val moisture: Int? = null,
    val raw: Int? = null,
    val lastUpdated: String? = null,
    val ip: String? = null,
    val wet: Int? = null,
    val dry: Int? = null,
    val intervalMs: Long? = null,
    val cooldownMs: Long? = null,
    val history: List<HistoryPoint> = emptyList(),
    val errorMessage: String? = null,
    val isHistoryLoading: Boolean = false,
    val isApplyingConfig: Boolean = false
)

enum class ConnectionStatus { CONNECTING, ONLINE, OFFLINE }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

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

