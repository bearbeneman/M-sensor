package com.bearbeneman.soilsensor.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearbeneman.soilsensor.databinding.FragmentDashboardBinding
import com.bearbeneman.soilsensor.data.SoilRepositoryProvider
import com.bearbeneman.soilsensor.network.model.HistoryPoint
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels {
        DashboardViewModelFactory(SoilRepositoryProvider.provide(requireContext()))
    }

    private var moistureAnimator: ValueAnimator? = null
    private var lastMoistureValue: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart(binding.historyChart)
        setupLiveChart(binding.liveChart)
        setupListeners()
        collectState()
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { renderState(it) }
            }
        }
    }

    private fun renderState(state: DashboardUiState) = with(binding) {
        val targetMoisture = state.moisture
        if (targetMoisture != null) {
            animateMoistureChange(targetMoisture)
        } else {
            moistureAnimator?.cancel()
            lastMoistureValue = 0
            moistureValue.text = "--"
            moistureBar.progress = 0
        }
        rawValue.text = getString(com.bearbeneman.soilsensor.R.string.raw_default, state.raw?.toString() ?: "--")
        timeValue.text = getString(com.bearbeneman.soilsensor.R.string.time_default, state.lastUpdated ?: "--")
        ipValue.text = getString(com.bearbeneman.soilsensor.R.string.ip_default, state.ip ?: "--")
        calValue.text = if (state.wet != null && state.dry != null) {
            "Calibration: wet ${state.wet} / dry ${state.dry}"
        } else {
            getString(com.bearbeneman.soilsensor.R.string.cal_default)
        }
        val intervalText = state.intervalMs?.let { "${it} ms" } ?: "--"
        intervalValue.text = getString(com.bearbeneman.soilsensor.R.string.interval_default, intervalText)

        updateStatus(state.status)
        updateChart(state.history)
        updateLiveChart(state.liveSamples)
        historyLoading.visibility = if (state.isHistoryLoading) View.VISIBLE else View.GONE

        errorBanner.visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
        errorBanner.text = state.errorMessage ?: ""
    }

    private fun setupListeners() = with(binding) {
        historyRefreshButton.setOnClickListener { viewModel.refreshHistoryOnce() }
        clearHistoryButton.setOnClickListener { viewModel.clearHistory() }
    }

    private fun setupChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.axisRight.isEnabled = false
        chart.setNoDataText(getString(com.bearbeneman.soilsensor.R.string.history_empty))
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

    private fun setupLiveChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.axisRight.isEnabled = false
        chart.setNoDataText(getString(com.bearbeneman.soilsensor.R.string.live_empty))
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

    private fun updateChart(points: List<HistoryPoint>) {
        if (points.isEmpty()) {
            binding.historyChart.clear()
            binding.historyChart.setNoDataText(getString(com.bearbeneman.soilsensor.R.string.history_empty))
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

    private fun updateLiveChart(samples: List<LiveSample>) {
        if (samples.isEmpty()) {
            binding.liveChart.clear()
            binding.liveChart.setNoDataText(getString(com.bearbeneman.soilsensor.R.string.live_empty))
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
            mode = LineDataSet.Mode.LINEAR
        }
        binding.liveChart.data = LineData(dataSet)
        binding.liveChart.invalidate()
    }

    private fun updateStatus(status: ConnectionStatus) {
        val (textRes, color) = when (status) {
            ConnectionStatus.CONNECTING -> com.bearbeneman.soilsensor.R.string.status_connecting to Color.YELLOW
            ConnectionStatus.ONLINE -> com.bearbeneman.soilsensor.R.string.status_online to Color.parseColor("#22c55e")
            ConnectionStatus.OFFLINE -> com.bearbeneman.soilsensor.R.string.status_offline to Color.parseColor("#ef4444")
        }
        binding.statusText.setText(textRes)
        val drawable = DrawableCompat.wrap(binding.statusDot.background)
        DrawableCompat.setTint(drawable, color)
        binding.statusDot.background = drawable
    }

    private fun animateMoistureChange(target: Int) {
        if (target == lastMoistureValue && binding.moistureValue.text != "--") {
            binding.moistureValue.text = getString(com.bearbeneman.soilsensor.R.string.moisture_percent, target)
            binding.moistureBar.progress = target
            return
        }
        val start = lastMoistureValue
        moistureAnimator?.cancel()
        moistureAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = 600
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                binding.moistureValue.text = getString(com.bearbeneman.soilsensor.R.string.moisture_percent, value)
                binding.moistureBar.progress = value
            }
            start()
        }
        lastMoistureValue = target
    }

    override fun onDestroyView() {
        moistureAnimator?.cancel()
        moistureAnimator = null
        _binding = null
        super.onDestroyView()
    }
}

