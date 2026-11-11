package com.omnioone.app

import android.app.NotificationManager
import androidx.core.content.pm.ShortcutManagerCompat
import com.omnioone.commons.FossifyApp

/** Omnio One's app start-up. */
class OmnioApp : FossifyApp() {

    override fun onCreate() {
        super.onCreate()
        tidyOldMessages()
    }

    /** Once: clears things the previous Messages screens left behind (chat shortcuts that now lead nowhere, an unused notification category). */
    private fun tidyOldMessages() {
        val prefs = getSharedPreferences("omnio", MODE_PRIVATE)
        if (prefs.getBoolean("old_messages_tidied", false)) return
        prefs.edit().putBoolean("old_messages_tidied", true).apply()
        try {
            val old = ShortcutManagerCompat.getDynamicShortcuts(this).map { it.id }.filterNot { it.startsWith("omnio_") }
            if (old.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(this, old)
        } catch (ignored: Exception) {
        }
        try {
            getSystemService(NotificationManager::class.java).deleteNotificationChannel("fossify_messages")
        } catch (ignored: Exception) {
        }
    }
}
