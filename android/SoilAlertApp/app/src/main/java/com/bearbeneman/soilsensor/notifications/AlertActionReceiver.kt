package com.bearbeneman.soilsensor.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(context).cancel(notificationId)
        Toast.makeText(context, context.getString(com.bearbeneman.soilsensor.R.string.notification_acknowledged), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_ACK = "com.bearbeneman.soilsensor.ACTION_ACK_ALERT"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

