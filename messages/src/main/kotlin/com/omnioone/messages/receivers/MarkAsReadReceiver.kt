package com.omnioone.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnioone.commons.extensions.notificationManager
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.extensions.conversationsDB
import com.omnioone.messages.extensions.markThreadMessagesRead
import com.omnioone.messages.helpers.MARK_AS_READ
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.refreshConversations

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MARK_AS_READ -> {
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                context.notificationManager.cancel(threadId.hashCode())
                ensureBackgroundThread {
                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                    refreshConversations()
                }
            }
        }
    }
}
