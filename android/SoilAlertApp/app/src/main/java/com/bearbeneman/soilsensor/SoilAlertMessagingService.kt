package com.bearbeneman.soilsensor

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bearbeneman.soilsensor.data.NotificationPrefs
import com.bearbeneman.soilsensor.notifications.AlertActionReceiver
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.random.Random

class SoilAlertMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val title = remoteMessage.notification?.title
            ?: getString(R.string.notification_default_title)
        val body = remoteMessage.notification?.body
            ?: getString(R.string.notification_default_body)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationId = Random.nextInt()

        val ackIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            Intent(this, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_ACK
                putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = NotificationPrefs.getSoundUri(this)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 120, 250))
            .addAction(
                0,
                getString(R.string.acknowledge_alert),
                ackIntent
            )
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
        triggerHaptic()
    }

    override fun onNewToken(token: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(MainActivity.TOPIC)
    }

    companion object {
        const val CHANNEL_ID = "soil_alert_channel"
    }

    private fun triggerHaptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 250, 120, 250), -1)
        }
    }
}

