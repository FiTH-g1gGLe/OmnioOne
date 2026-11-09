package com.omnioone.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnioone.app.activities.OmnioCallActivity
import com.omnioone.app.helpers.OmnioCallNotification
import com.omnioone.app.helpers.OmnioCalls

/** Omnio One: the Answer / Decline / Hang up buttons on the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            OmnioCallNotification.ACTION_ANSWER -> {
                OmnioCalls.answer()
                try {
                    context.startActivity(OmnioCallActivity.startIntent(context))
                } catch (ignored: Exception) {
                }
            }
            OmnioCallNotification.ACTION_DECLINE -> OmnioCalls.decline()
            OmnioCallNotification.ACTION_HANG_UP -> OmnioCalls.hangUp()
        }
    }
}
