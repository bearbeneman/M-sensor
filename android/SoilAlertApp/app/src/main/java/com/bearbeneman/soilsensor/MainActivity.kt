package com.bearbeneman.soilsensor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearbeneman.soilsensor.data.SoilRepository
import com.bearbeneman.soilsensor.databinding.ActivityMainBinding
import com.bearbeneman.soilsensor.network.model.HistoryPoint
import com.bearbeneman.soilsensor.ui.ConnectionStatus
import com.bearbeneman.soilsensor.ui.DashboardUiState
import com.bearbeneman.soilsensor.ui.DashboardViewModel
import com.bearbeneman.soilsensor.ui.DashboardViewModelFactory
import com.bearbeneman.soilsensor.ui.LiveSample
import com.bearbeneman.soilsensor.ui.UiEvent
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(SoilRepository.getInstance(applicationContext))
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.notification_permission_granted
            } else {
                R.string.notification_permission_denied
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

    private val cooldownOptions = listOf(
        60_000L to "1 min",
        5 * 60_000L to "5 min",
        10 * 60_000L to "10 min",
        30 * 60_000L to "30 min",
        60 * 60_000L to "1 hr",
        2 * 60 * 60_000L to "2 hr",
        6 * 60 * 60_000L to "6 hr",
        12 * 60 * 60_000L to "12 hr",
        24 * 60 * 60_000L to "24 hr"
    )
    private var selectedCooldownMs = cooldownOptions.first().first
    private var suppressCooldownListener = false
    private var latestState: DashboardUiState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChart(binding.historyChart)
        setupLiveChart(binding.liveChart)
        setupCooldownSpinner()
        setupListeners()
        ensureNotificationPermission()
        autoSubscribeToTopic()
        collectState()
        collectEvents()
    }

    private fun setupChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.axisRight.isEnabled = false
        chart.setNoDataText(getString(R.string.history_title))
        chart.setNoDataTextColor(Color.GRAY)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = Color.GRAY
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                        return if (value >= 24f) {
                            String.format("%.0fd", value / 24f)
                        } else {
                            String.format("%.0fh", value)
                        }
                }
            }
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            axisMaximum = 100f
            textColor = Color.GRAY
            setDrawGridLines(true)
            gridColor = Color.argb(80, 148, 163, 184)
            setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
        }
    }

    private fun setupCooldownSpinner() {
        val labels = cooldownOptions.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cooldownSpinner.adapter = adapter
        binding.cooldownSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (suppressCooldownListener) return
                selectedCooldownMs = cooldownOptions[position].first
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupListeners() = with(binding) {
        cooldownApplyButton.setOnClickListener {
            viewModel.applyCooldown(selectedCooldownMs)
        }
        historyRefreshButton.setOnClickListener {
            viewModel.refreshHistoryOnce()
        }
        clearHistoryButton.setOnClickListener {
            viewModel.clearHistory()
        }
        saveCalibrationButton.setOnClickListener {
            val wetValue = wetInput.text?.toString()?.toIntOrNull()
            val dryValue = dryInput.text?.toString()?.toIntOrNull()
            viewModel.applyCalibration(wetValue, dryValue)
        }
        saveAlertSettingsButton.setOnClickListener {
            val low = alertLowInput.text?.toString()?.toIntOrNull()
            val high = alertHighInput.text?.toString()?.toIntOrNull()
            if (low == null || high == null) {
                Snackbar.make(root, R.string.alert_threshold_error, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.applyAlertSettings(alertsToggle.isChecked, low, high)
        }
        wetFromCurrentButton.setOnClickListener {
            latestState?.raw?.let { wetInput.setText(it.toString()) }
        }
        dryFromCurrentButton.setOnClickListener {
            latestState?.raw?.let { dryInput.setText(it.toString()) }
        }
        saveBaseUrlButton.setOnClickListener {
            val url = baseUrlInput.text?.toString().orEmpty()
            if (url.isNotBlank()) {
                viewModel.saveBaseUrl(url)
            }
        }
        openNotificationSettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestState = state
                    renderState(state)
                }
            }
        }
    }

    private fun collectEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UiEvent.Message ->
                            Snackbar.make(binding.root, event.text, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderState(state: DashboardUiState) = with(binding) {
        moistureValue.text = state.moisture?.let { "$it%" } ?: "--"
        moistureBar.progress = state.moisture ?: 0
        rawValue.text = getString(R.string.raw_default, state.raw?.toString() ?: "--")
        timeValue.text = getString(R.string.time_default, state.lastUpdated ?: "--")
        ipValue.text = getString(R.string.ip_default, state.ip ?: "--")
        calValue.text = if (state.wet != null && state.dry != null) {
            "Calibration: wet ${state.wet} / dry ${state.dry}"
        } else {
            getString(R.string.cal_default)
        }
        val intervalText = state.intervalMs?.let { "${it} ms" } ?: "--"
        intervalValue.text = getString(R.string.interval_default, intervalText)

        updateStatus(state.status)

        updateChart(state.history)
        updateLiveChart(state.liveSamples)
        historyLoading.visibility = if (state.isHistoryLoading) View.VISIBLE else View.GONE

        errorBanner.visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
        errorBanner.text = state.errorMessage ?: ""

        suppressCooldownListener = true
        state.cooldownMs?.let { ms ->
            val index = cooldownOptions.indexOfFirst { it.first == ms }
            if (index >= 0) {
                cooldownSpinner.setSelection(index)
                selectedCooldownMs = ms
            }
        }
        suppressCooldownListener = false

        if (alertsToggle.isChecked != state.alertsEnabled) {
            alertsToggle.isChecked = state.alertsEnabled
        }

        state.alertLow?.let { value ->
            if (!alertLowInput.isFocused) {
                val text = alertLowInput.text?.toString()
                if (text.isNullOrBlank() || text != value.toString()) {
                    alertLowInput.setText(value.toString())
                }
            }
        }

        state.alertHigh?.let { value ->
            if (!alertHighInput.isFocused) {
                val text = alertHighInput.text?.toString()
                if (text.isNullOrBlank() || text != value.toString()) {
                    alertHighInput.setText(value.toString())
                }
            }
        }

        val currentBase = baseUrlInput.text?.toString()
        if (state.baseUrl.isNotBlank() && state.baseUrl != currentBase) {
            baseUrlInput.setText(state.baseUrl)
        }

        if (state.wet != null && wetInput.text.isNullOrBlank()) {
            wetInput.setText(state.wet.toString())
        }
        if (state.dry != null && dryInput.text.isNullOrBlank()) {
            dryInput.setText(state.dry.toString())
        }

        cooldownApplyButton.isEnabled = !state.isApplyingConfig
        saveCalibrationButton.isEnabled = !state.isApplyingConfig
        saveAlertSettingsButton.isEnabled = !state.isApplyingConfig
    }

    private fun updateChart(points: List<HistoryPoint>) {
        if (points.isEmpty()) {
            binding.historyChart.clear()
            binding.historyChart.setNoDataText(getString(R.string.history_empty))
            return
        }
        val firstTimestamp = points.first().timestamp
        val entries = points.map { point ->
            val hours = (point.timestamp - firstTimestamp) / 3600f
            Entry(hours, point.moisture.toFloat())
        }

        val dataSet = LineDataSet(entries, "Moisture").apply {
            color = Color.parseColor("#3b82f6")
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#1d4ed8")
            fillAlpha = 90
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.historyChart.data = LineData(dataSet)
        binding.historyChart.invalidate()
    }

    private fun setupLiveChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.axisRight.isEnabled = false
        chart.setNoDataText(getString(R.string.live_empty))
        chart.setNoDataTextColor(Color.GRAY)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = Color.GRAY
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                    return String.format("%.0fs", value)
                }
            }
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            axisMaximum = 100f
            textColor = Color.GRAY
            setDrawGridLines(true)
            gridColor = Color.argb(80, 148, 163, 184)
            setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
        }
    }

    private fun updateLiveChart(samples: List<LiveSample>) {
        if (samples.isEmpty()) {
            binding.liveChart.clear()
            binding.liveChart.setNoDataText(getString(R.string.live_empty))
            return
        }
        val first = samples.first().timestamp
        val entries = samples.map {
            val seconds = (it.timestamp - first) / 1000f
            Entry(seconds, it.moisture.toFloat())
        }
        val dataSet = LineDataSet(entries, "Live").apply {
            color = Color.parseColor("#0ea5e9")
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(false)
            mode = LineDataSet.Mode.LINEAR
        }
        binding.liveChart.data = LineData(dataSet)
        binding.liveChart.invalidate()
    }

    private fun updateStatus(status: ConnectionStatus) {
        val (textRes, color) = when (status) {
            ConnectionStatus.CONNECTING -> R.string.status_connecting to Color.YELLOW
            ConnectionStatus.ONLINE -> R.string.status_online to Color.parseColor("#22c55e")
            ConnectionStatus.OFFLINE -> R.string.status_offline to Color.parseColor("#ef4444")
        }
        binding.statusText.setText(textRes)
        val drawable = DrawableCompat.wrap(binding.statusDot.background)
        DrawableCompat.setTint(drawable, color)
        binding.statusDot.background = drawable
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(permission)
    }

    private fun autoSubscribeToTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    companion object {
        const val TOPIC = "soil-alerts"
    }
}

