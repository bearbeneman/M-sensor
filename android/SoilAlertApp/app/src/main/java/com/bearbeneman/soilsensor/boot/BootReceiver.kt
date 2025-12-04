package com.bearbeneman.soilsensor.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bearbeneman.soilsensor.MainActivity
import com.google.firebase.messaging.FirebaseMessaging

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            FirebaseMessaging.getInstance().subscribeToTopic(MainActivity.TOPIC)
        }
    }
}

