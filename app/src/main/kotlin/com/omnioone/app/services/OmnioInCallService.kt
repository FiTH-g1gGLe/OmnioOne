package com.omnioone.app.services

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.omnioone.app.activities.OmnioCallActivity
import com.omnioone.app.phone.config
import com.omnioone.app.helpers.OmnioCallNotification
import com.omnioone.app.helpers.OmnioCalls

/**
 * Omnio One's link to Android's phone system. Android hands every call to this service;
 * it passes them to [OmnioCalls], keeps the call notification up to date and opens the
 * call screen when needed.
 */
class OmnioInCallService : InCallService() {

    private val changed: () -> Unit = { OmnioCallNotification.update(this) }

    override fun onCreate() {
        super.onCreate()
        OmnioCalls.addListener(changed)
    }

    override fun onDestroy() {
        OmnioCalls.removeListener(changed)
        OmnioCallNotification.cancel(this)
        super.onDestroy()
    }

    private fun isOutgoing(call: Call): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        } else {
            OmnioCalls.stateOf(call) == OmnioCalls.State.DIALING
        }

    private fun canPopUp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) return false
        return true
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        OmnioCalls.onCallAdded(this, call)

        // Locked phone: ring full-screen from the notification.
        // Unlocked: outgoing calls (and incoming, if "always full screen" is on) open the call screen straight away.
        val locked = !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isDeviceLocked
        val incoming = !isOutgoing(call)
        val openNow = !locked && (!incoming || config.alwaysShowFullscreen)
        OmnioCallNotification.quietRinging = openNow
        OmnioCallNotification.update(this)
        if (openNow || !canPopUp()) openCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val wasPrimary = call == OmnioCalls.primary()
        OmnioCalls.onCallRemoved(call)
        if (!OmnioCalls.hasCalls()) {
            OmnioCallNotification.cancel(this)
        } else {
            OmnioCallNotification.update(this)
            if (wasPrimary) openCallScreen()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        OmnioCalls.onAudioChanged()
    }

    private fun openCallScreen() {
        try {
            startActivity(OmnioCallActivity.startIntent(this))
        } catch (ignored: Exception) {
            // the notification is still there to open it
        }
    }
}
