package com.bearbeneman.soilsensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bearbeneman.soilsensor.data.SoilRepositoryProvider
import com.bearbeneman.soilsensor.databinding.ActivityMainBinding
import com.bearbeneman.soilsensor.ui.DashboardFragment
import com.bearbeneman.soilsensor.ui.DashboardViewModel
import com.bearbeneman.soilsensor.ui.DashboardViewModelFactory
import com.bearbeneman.soilsensor.ui.SettingsFragment
import com.bearbeneman.soilsensor.ui.UiEvent
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(SoilRepositoryProvider.provide(applicationContext))
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.notification_permission_granted
            } else {
                R.string.notification_permission_denied
            }
            Snackbar.make(binding.viewPager, message, Snackbar.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.viewPager.adapter = DashboardPagerAdapter()
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_dashboard)
                else -> getString(R.string.tab_settings)
            }
        }.attach()

        collectEvents()
        ensureNotificationPermission()
        autoSubscribeToTopic()
    }

    private fun collectEvents() {
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is UiEvent.Message ->
                        Snackbar.make(binding.viewPager, event.text, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
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

    private inner class DashboardPagerAdapter :
        FragmentStateAdapter(this) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int) = when (position) {
            0 -> DashboardFragment()
            else -> SettingsFragment()
        }
    }

    companion object {
        const val TOPIC = "soil-alerts"
    }
}

