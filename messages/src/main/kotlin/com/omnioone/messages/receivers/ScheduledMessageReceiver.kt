package com.omnioone.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.omnioone.commons.extensions.showErrorToast
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.extensions.conversationsDB
import com.omnioone.messages.extensions.deleteScheduledMessage
import com.omnioone.messages.extensions.getAddresses
import com.omnioone.messages.extensions.messagesDB
import com.omnioone.messages.helpers.SCHEDULED_MESSAGE_ID
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.refreshConversations
import com.omnioone.messages.helpers.refreshMessages
import com.omnioone.messages.messaging.sendMessageCompat
import kotlin.time.Duration.Companion.minutes

class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "simple.messenger:scheduled.message.receiver"
        )
        wakelock.acquire(1.minutes.inWholeMilliseconds)

        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                handleIntent(context, intent)
            } finally {
                try {
                    if (wakelock.isHeld) wakelock.release()
                } catch (_: Exception) {
                }

                pendingResult.finish()
            }
        }
    }

    private fun handleIntent(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(SCHEDULED_MESSAGE_ID, 0L)
        val message = try {
            context.messagesDB.getScheduledMessageWithId(threadId, messageId)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val addresses = message.participants.getAddresses()
        val attachments = message.attachment?.attachments ?: emptyList()

        try {
            Handler(Looper.getMainLooper()).post {
                context.sendMessageCompat(message.body, addresses, message.subscriptionId, attachments)
            }

            // delete temporary conversation and message as it's already persisted to the telephony db now
            context.deleteScheduledMessage(messageId)
            context.conversationsDB.deleteThreadId(messageId)
            refreshMessages()
            refreshConversations()
        } catch (e: Exception) {
            context.showErrorToast(e)
        } catch (e: Error) {
            context.showErrorToast(
                e.localizedMessage ?: context.getString(com.omnioone.commons.R.string.unknown_error_occurred)
            )
        }
    }
}
