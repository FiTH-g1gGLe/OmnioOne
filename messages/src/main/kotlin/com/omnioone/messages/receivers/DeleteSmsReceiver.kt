package com.omnioone.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnioone.commons.extensions.notificationManager
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.extensions.deleteMessage
import com.omnioone.messages.extensions.updateLastConversationMessage
import com.omnioone.messages.helpers.IS_MMS
import com.omnioone.messages.helpers.MESSAGE_ID
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.refreshConversations
import com.omnioone.messages.helpers.refreshMessages

class DeleteSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
        val isMms = intent.getBooleanExtra(IS_MMS, false)
        context.notificationManager.cancel(threadId.hashCode())
        ensureBackgroundThread {
            context.deleteMessage(messageId, isMms)
            context.updateLastConversationMessage(threadId)
            refreshMessages()
            refreshConversations()
        }
    }
}
