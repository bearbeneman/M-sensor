package com.bearbeneman.soilsensor.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.bearbeneman.soilsensor.R
import com.bearbeneman.soilsensor.data.SoilRepositoryProvider
import com.bearbeneman.soilsensor.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels {
        DashboardViewModelFactory(SoilRepositoryProvider.provide(requireContext()))
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCooldownSpinner()
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

        if (!alertLowInput.isFocused) {
            alertLowInput.setText(state.alertLow?.toString() ?: "")
        }
        if (!alertHighInput.isFocused) {
            alertHighInput.setText(state.alertHigh?.toString() ?: "")
        }

        if (!wetInput.isFocused && state.wet != null) {
            wetInput.setText(state.wet.toString())
        }
        if (!dryInput.isFocused && state.dry != null) {
            dryInput.setText(state.dry.toString())
        }
        if (!baseUrlInput.isFocused && state.baseUrl.isNotBlank()) {
            baseUrlInput.setText(state.baseUrl)
        }

        cooldownApplyButton.isEnabled = !state.isApplyingConfig
        saveCalibrationButton.isEnabled = !state.isApplyingConfig
        saveAlertSettingsButton.isEnabled = !state.isApplyingConfig
        saveBaseUrlButton.isEnabled = !state.isApplyingConfig
    }

    private fun setupCooldownSpinner() = with(binding) {
        val labels = cooldownOptions.map { it.second }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cooldownSpinner.adapter = adapter
        cooldownSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
            wetInput.setText(viewModel.uiState.value.raw?.toString() ?: "")
        }
        dryFromCurrentButton.setOnClickListener {
            dryInput.setText(viewModel.uiState.value.raw?.toString() ?: "")
        }
        saveCalibrationButton.setOnClickListener {
            val wetValue = wetInput.text?.toString()?.toIntOrNull()
            val dryValue = dryInput.text?.toString()?.toIntOrNull()
            viewModel.applyCalibration(wetValue, dryValue)
        }
        saveBaseUrlButton.setOnClickListener {
            val url = baseUrlInput.text?.toString().orEmpty()
            if (url.isNotBlank()) {
                viewModel.saveBaseUrl(url)
            }
        }
        openNotificationSettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

