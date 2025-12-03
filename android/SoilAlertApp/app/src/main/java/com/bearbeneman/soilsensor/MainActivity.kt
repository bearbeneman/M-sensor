package com.bearbeneman.soilsensor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bearbeneman.soilsensor.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
    }

    companion object {
        const val TOPIC = "soil-alerts"
    }
}

