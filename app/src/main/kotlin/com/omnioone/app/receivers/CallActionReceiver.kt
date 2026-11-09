package com.omnioone.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnioone.app.activities.CallActivity
import com.omnioone.app.helpers.ACCEPT_CALL
import com.omnioone.app.helpers.CallManager
import com.omnioone.app.helpers.DECLINE_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()
        }
    }
}
