package com.bearbeneman.soilsensor.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object NotificationPrefs {
    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_SOUND_URI = "sound_uri"

    fun getSoundUri(context: Context): Uri {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SOUND_URI, null)
        return if (stored.isNullOrBlank()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            Uri.parse(stored)
        }
    }

    fun setSoundUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOUND_URI, uri?.toString())
            .apply()
    }
}

