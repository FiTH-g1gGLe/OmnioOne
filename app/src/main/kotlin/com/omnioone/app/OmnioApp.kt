package com.omnioone.app

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat

/** Omnio One's app start-up. */
class OmnioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // before anything else writes settings: people updating skip the first-run setup
        com.omnioone.app.activities.OmnioSetupActivity.skipForExistingUsers(this)
        tidyOldMessages()
    }

    /** Once: clears things the previous Messages screens left behind (chat shortcuts that now lead nowhere). */
    private fun tidyOldMessages() {
        val prefs = getSharedPreferences("omnio", MODE_PRIVATE)
        if (prefs.getBoolean("old_messages_tidied", false)) return
        prefs.edit().putBoolean("old_messages_tidied", true).apply()
        try {
            val old = ShortcutManagerCompat.getDynamicShortcuts(this).map { it.id }.filterNot { it.startsWith("omnio_") }
            if (old.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(this, old)
        } catch (ignored: Exception) {
        }
    }
}
