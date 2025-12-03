package com.bearbeneman.soilsensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bearbeneman.soilsensor.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.notification_permission_granted
            } else {
                R.string.notification_permission_denied
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topicValue.text = TOPIC

        binding.subscribeButton.setOnClickListener {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
                .addOnCompleteListener { task ->
                    val message = if (task.isSuccessful) {
                        R.string.subscription_success
                    } else {
                        R.string.subscription_failed
                    }
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                }
        }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            binding.tokenValue.text = token
        }

        ensureNotificationPermission()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(permission)
    }

    companion object {
        const val TOPIC = "soil-alerts"
    }
}

