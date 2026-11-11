package com.omnioone.app.messaging

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.klinker.android.send_message.MmsReceivedReceiver
import com.omnioone.app.R

/** Shared steps once a new message is saved: un-archive the chat, refresh screens, notify. */
private object OmnioIncoming {

    fun arrived(context: Context, threadId: Long, find: (List<OmnioMessageStore.Message>) -> OmnioMessageStore.Message?) {
        try {
            OmnioArchive.onNewMessage(context, threadId)
        } catch (ignored: Exception) {
        }
        keepOldScreensInStep(context, threadId)
        val message = find(OmnioMessageStore.messages(context, threadId, limit = 10)) ?: return
        OmnioNotifications.showIncoming(context, message)
    }

    /** Tells open Messages screens to refresh. */
    @Suppress("UNUSED_PARAMETER")
    fun keepOldScreensInStep(context: Context, threadId: Long) = OmnioMessagesBus.changed()
}

/** Omnio One: receives texts (SMS) as the phone's messaging app. */
class OmnioSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                receive(app, intent)
            } catch (ignored: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun receive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val first = parts.first()
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        if (address.isBlank()) return
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
        if (OmnioBlocking.shouldDrop(context, address, body)) return

        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, first.timestampMillis)
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX)
            put(Sms.THREAD_ID, threadId)
            put(Sms.PROTOCOL, first.protocolIdentifier)
            put(Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
            first.serviceCenterAddress?.let { put(Sms.SERVICE_CENTER, it) }
            first.pseudoSubject?.takeIf { it.isNotBlank() }?.let { put(Sms.SUBJECT, it) }
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = context.contentResolver.insert(Sms.CONTENT_URI, values) ?: return
        val id = ContentUris.parseId(uri)
        OmnioIncoming.arrived(context, threadId) { list -> list.lastOrNull { !it.isMms && it.id == id } }
    }
}

/**
 * Omnio One: picture and group messages (MMS). The MMS library downloads the message from the
 * network and saves it; we decide whether it's blocked and handle what happens next.
 */
class OmnioMmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String) = OmnioBlocking.isBlockedSender(context, address)

    override fun isContentBlocked(context: Context, content: String) = OmnioBlocking.hasBlockedWord(context, content)

    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        val app = context.applicationContext
        Thread {
            try {
                val (id, threadId) = find(app, messageUri) ?: return@Thread
                OmnioIncoming.arrived(app, threadId) { list -> list.lastOrNull { it.isMms && it.id == id } }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    override fun onError(context: Context, error: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.omnio_notify_mms_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** The saved message's id and chat, from its address or (if missing) the newest one in the inbox. */
    private fun find(context: Context, uri: Uri?): Pair<Long, Long>? {
        val columns = arrayOf(Mms._ID, Mms.THREAD_ID)
        val cursor = if (uri != null) context.contentResolver.query(uri, columns, null, null, null)
        else context.contentResolver.query(Mms.Inbox.CONTENT_URI, columns, null, null, "${Mms.DATE} DESC LIMIT 1")
        return cursor?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
    }
}

/** Omnio One: the Reply, Mark as read and Delete buttons on message notifications. */
class OmnioNotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        if (threadId == 0L) return
        val app = context.applicationContext
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(OmnioNotifications.REPLY_KEY)?.toString()?.trim()
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    OmnioNotifications.ACTION_REPLY -> if (!reply.isNullOrEmpty()) reply(app, threadId, reply)
                    OmnioNotifications.ACTION_READ -> {
                        OmnioNotifications.cancel(app, threadId)
                        markRead(app, threadId)
                    }
                    OmnioNotifications.ACTION_DELETE -> {
                        OmnioNotifications.cancel(app, threadId)
                        delete(app, intent.getLongExtra(OmnioNotifications.EXTRA_MESSAGE_ID, 0L), intent.getBooleanExtra(OmnioNotifications.EXTRA_IS_MMS, false))
                        OmnioIncoming.keepOldScreensInStep(app, threadId)
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun reply(context: Context, threadId: Long, text: String) {
        val addresses = OmnioMessageStore.conversations(context, onlyThread = threadId).firstOrNull()?.people?.map { it.address }.orEmpty()
        val sims = OmnioSender.sims(context)
        val saved = OmnioSender.simFor(context, threadId)
        val subId = if (sims.any { it.subId == saved }) saved else -1
        try {
            OmnioSender.send(context, text, addresses, subId)
            OmnioNotifications.addReply(context, threadId, text)
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, R.string.omnio_thread_send_failed, Toast.LENGTH_LONG).show() }
        }
        markRead(context, threadId)
    }

    companion object {
        /** Marks every message in a chat as read. */
        fun markRead(context: Context, threadId: Long) {
            val values = ContentValues().apply {
                put(Sms.READ, 1)
                put(Sms.SEEN, 1)
            }
            try {
                context.contentResolver.update(Sms.CONTENT_URI, values, "${Sms.THREAD_ID} = ? AND (${Sms.READ} = 0 OR ${Sms.SEEN} = 0)", arrayOf(threadId.toString()))
                context.contentResolver.update(Mms.CONTENT_URI, values, "${Mms.THREAD_ID} = ? AND (${Mms.READ} = 0 OR ${Mms.SEEN} = 0)", arrayOf(threadId.toString()))
            } catch (ignored: Exception) {
            }
            OmnioMessagesBus.changed()
        }

        /** Marks a chat unread again (its latest received message). */
        fun markUnread(context: Context, threadId: Long) {
            val latest = OmnioMessageStore.messages(context, threadId, limit = 20).lastOrNull { it.incoming } ?: return
            val values = ContentValues().apply { put(Sms.READ, 0) }
            try {
                context.contentResolver.update(ContentUris.withAppendedId(if (latest.isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, latest.id), values, null, null)
            } catch (ignored: Exception) {
            }
            OmnioMessagesBus.changed()
        }

        fun delete(context: Context, id: Long, isMms: Boolean) {
            if (id <= 0) return
            try {
                context.contentResolver.delete(ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id), null, null)
            } catch (ignored: Exception) {
            }
        }
    }
}
