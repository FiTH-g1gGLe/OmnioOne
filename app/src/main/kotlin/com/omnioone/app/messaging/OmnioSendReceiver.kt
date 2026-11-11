package com.omnioone.app.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import com.omnioone.messages.extensions.messagesDB
import com.omnioone.messages.helpers.refreshConversations
import com.omnioone.messages.helpers.refreshMessages
import java.io.File

/**
 * Omnio One: hears back from Android after a message is sent (or delivered) and updates it,
 * so the chat shows Sent, Delivered or "Not sent · tap to retry".
 */
class OmnioSendReceiver : BroadcastReceiver() {

    companion object {
        /** The chat on screen right now, so we don't notify about a failure you can already see. */
        @Volatile
        var openThreadId: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val result = resultCode
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    OmnioSender.ACTION_SENT -> smsSent(context, intent, result == Activity.RESULT_OK)
                    OmnioSender.ACTION_DELIVERED -> smsDelivered(context, intent)
                    OmnioSender.ACTION_MMS_SENT -> mmsSent(context, intent, result == Activity.RESULT_OK)
                }
            } catch (ignored: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun smsSent(context: Context, intent: Intent, ok: Boolean) {
        val uri = intent.data ?: return
        val type = if (ok) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_FAILED
        // a long text is sent in parts: if any part fails, keep it marked failed
        val selection = if (ok) "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_FAILED}" else null
        context.contentResolver.update(uri, ContentValues().apply { put(Sms.TYPE, type) }, selection, null)
        val id = ContentUris.parseId(uri)
        keepOldScreenInStep { context.messagesDB.updateType(id, type) }
        if (!ok) notifyFailed(context, uri)
    }

    private fun smsDelivered(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val pdu = intent.getByteArrayExtra("pdu") ?: return
        val format = intent.getStringExtra("format")
        var status = try {
            SmsMessage.createFromPdu(pdu, format)?.status ?: return
        } catch (e: Exception) {
            return
        }
        if (format == "3gpp2") {
            // older CDMA networks report differently: turn it into the usual codes
            val errorClass = status shr 24 and 0x03
            val code = status shr 16 and 0x3f
            status = when {
                errorClass == 0 && code == 0x02 -> Sms.STATUS_COMPLETE
                errorClass == 3 -> Sms.STATUS_FAILED
                else -> Sms.STATUS_PENDING
            }
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(Sms.STATUS, status)
            put(Sms.DATE_SENT, System.currentTimeMillis())
        }, null, null)
        val id = ContentUris.parseId(uri)
        keepOldScreenInStep { context.messagesDB.updateStatus(id, status) }
    }

    private fun mmsSent(context: Context, intent: Intent, ok: Boolean) {
        intent.getStringExtra("content_uri")?.let { Uri.parse(it) }?.let { uri ->
            val box = if (ok) Mms.MESSAGE_BOX_SENT else Mms.MESSAGE_BOX_FAILED
            context.contentResolver.update(uri, ContentValues().apply { put(Mms.MESSAGE_BOX, box) }, null, null)
            if (!ok) notifyFailed(context, uri)
        }
        // a resend replaces the failed original
        val retryOf = intent.getLongExtra(OmnioSender.EXTRA_RETRY_OF, -1L)
        if (retryOf > 0) try {
            context.contentResolver.delete(android.content.ContentUris.withAppendedId(Mms.CONTENT_URI, retryOf), null, null)
        } catch (ignored: Exception) {
        }
        intent.getStringExtra("file_path")?.let { File(it).delete() }
        keepOldScreenInStep { }
    }

    /** The previous Messages screens keep their own copy: update it until they're retired. */
    private fun keepOldScreenInStep(update: () -> Unit) {
        try {
            update()
        } catch (ignored: Exception) {
        }
        refreshMessages()
        refreshConversations()
    }

    private fun notifyFailed(context: Context, uri: Uri) {
        try {
            val isMms = uri.toString().startsWith(Mms.CONTENT_URI.toString())
            val columns = if (isMms) arrayOf(Mms.THREAD_ID) else arrayOf(Sms.THREAD_ID, Sms.ADDRESS)
            var threadId = 0L
            var address = ""
            context.contentResolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) {
                    threadId = it.getLong(0)
                    if (!isMms) address = it.getString(1).orEmpty()
                }
            }
            if (threadId == 0L || threadId == openThreadId) return
            if (address.isEmpty()) {
                address = OmnioMessageStore.conversations(context, onlyThread = threadId).firstOrNull()?.people?.firstOrNull()?.address.orEmpty()
            }
            OmnioNotifications.showFailed(context, threadId, nameFor(context, address))
        } catch (ignored: Exception) {
        }
    }

    private fun nameFor(context: Context, address: String): String {
        if (address.isEmpty()) return "?"
        return try {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: address
        } catch (e: Exception) {
            address
        }
    }
}
