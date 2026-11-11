package com.omnioone.app.messaging

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Telephony
import android.provider.Telephony.Sms
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import com.omnioone.messages.extensions.config
import java.io.ByteArrayOutputStream

/**
 * Omnio One sending (Messages rewrite, stage 4).
 *
 * Texts go straight through Android's SmsManager. Picture and group messages are packaged
 * by the small MMS library (the same one as before) and handed to Android to send.
 * Sent and delivered results come back to [OmnioSendReceiver].
 */
object OmnioSender {

    const val ACTION_SENT = "com.omnioone.app.action.SMS_SENT"
    const val ACTION_DELIVERED = "com.omnioone.app.action.SMS_DELIVERED"
    const val ACTION_MMS_SENT = "com.omnioone.app.action.MMS_SENT"
    const val EXTRA_SUB_ID = "omnio_sub_id"
    const val EXTRA_RETRY_OF = "omnio_retry_of"

    /** Something to send with the message: a photo, video or file. */
    data class Attachment(val uri: Uri, val mime: String, val name: String)

    /** A SIM card that can send. */
    data class Sim(val subId: Int, val slot: Int, val name: String) {
        val short get() = "SIM ${slot + 1}"
    }

    class SendException(message: String) : Exception(message)

    // ---- SIM cards ----

    fun sims(context: Context): List<Sim> = try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        @Suppress("MissingPermission")
        manager.activeSubscriptionInfoList.orEmpty()
            .sortedBy { it.simSlotIndex }
            .map { Sim(it.subscriptionId, it.simSlotIndex, it.displayName?.toString().orEmpty().ifBlank { "SIM ${it.simSlotIndex + 1}" }) }
    } catch (e: Exception) {
        emptyList()
    }

    fun defaultSubId(): Int = try {
        SmsManager.getDefaultSmsSubscriptionId()
    } catch (e: Exception) {
        -1
    }

    /** The SIM this chat last used (or -1 for the phone's default). */
    fun simFor(context: Context, threadId: Long): Int =
        context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getInt("sim_$threadId", -1)

    fun rememberSim(context: Context, threadId: Long, subId: Int) =
        context.getSharedPreferences("omnio", Context.MODE_PRIVATE).edit().putInt("sim_$threadId", subId).apply()

    /** Seconds to wait before sending, so you can undo (0 = send straight away). */
    fun undoSeconds(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getInt("undo_send", 3)

    fun setUndoSeconds(context: Context, seconds: Int) =
        context.getSharedPreferences("omnio", Context.MODE_PRIVATE).edit().putInt("undo_send", seconds).apply()

    // ---- sending ----

    /**
     * Sends [text] and [attachments] to [addresses]. Runs on a background thread.
     * Throws [SendException] if it couldn't even start sending.
     */
    fun send(context: Context, text: String, addresses: List<String>, subId: Int, attachments: List<Attachment> = emptyList()) {
        val to = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (to.isEmpty()) throw SendException("No one to send to")
        if (text.isBlank() && attachments.isEmpty()) return
        if (needsMms(context, text, to, attachments)) sendMms(context, text, to, subId, attachments, retryOf = null)
        else sendSms(context, text, to, subId, existing = null)
    }

    /** Sends a failed message again. */
    fun retry(context: Context, message: OmnioMessageStore.Message, addresses: List<String>, subId: Int) {
        if (message.isMms) {
            val attachments = message.parts.filter { it.mime != "text/plain" && it.mime != "application/smil" }
                .map { Attachment(it.uri, it.mime, "part_${it.id}") }
            sendMms(context, message.body, addresses, subId, attachments, retryOf = message.id)
        } else {
            val uri = ContentUris.withAppendedId(Sms.CONTENT_URI, message.id)
            sendSms(context, message.body, listOf(message.address.ifEmpty { addresses.first() }), subId, existing = uri)
        }
    }

    private fun needsMms(context: Context, text: String, to: List<String>, attachments: List<Attachment>): Boolean {
        val config = context.config
        if (attachments.isNotEmpty()) return true
        if (to.size > 1 && config.sendGroupMessageMMS) return true
        if (config.sendLongMessageMMS && SmsMessage.calculateLength(text, false).first() > 1) return true
        return false
    }

    // ---- SMS ----

    private fun smsManager(context: Context, subId: Int): SmsManager {
        val id = if (subId >= 0) subId else defaultSubId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (id >= 0) manager.createForSubscriptionId(id) else manager
        } else {
            @Suppress("DEPRECATION")
            if (id >= 0) SmsManager.getSmsManagerForSubscriptionId(id) else SmsManager.getDefault()
        }
    }

    private fun sendSms(context: Context, text: String, to: List<String>, subId: Int, existing: Uri?) {
        val now = System.currentTimeMillis()
        if (to.size > 1 && existing == null) {
            // the group chat gets one copy, so it shows there too; each person gets their own text
            saveSms(context, to.joinToString("|"), text, now, Telephony.Threads.getOrCreateThreadId(context, to.toSet()), subId, Sms.MESSAGE_TYPE_SENT)
        }
        val reports = context.config.enableDeliveryReports
        to.forEach { address ->
            val uri = existing?.also { markOutbox(context, it, now) }
                ?: saveSms(context, address, text, now, Telephony.Threads.getOrCreateThreadId(context, address), subId, Sms.MESSAGE_TYPE_OUTBOX)
            try {
                val destination = PhoneNumberUtils.stripSeparators(address).ifEmpty { throw SendException("Empty number") }
                val manager = smsManager(context, subId)
                val parts = manager.divideMessage(text)
                if (parts.isNullOrEmpty()) throw SendException("Nothing to send")
                val messageId = ContentUris.parseId(uri)
                val sent = ArrayList<PendingIntent>()
                val delivered = ArrayList<PendingIntent?>()
                parts.indices.forEach { index ->
                    val request = (messageId * 16 + index).toInt()
                    sent += statusIntent(context, ACTION_SENT, uri, subId, request)
                    delivered += if (reports && index == parts.lastIndex) statusIntent(context, ACTION_DELIVERED, uri, subId, request) else null
                }
                manager.sendMultipartTextMessage(destination, null, parts, sent, delivered)
            } catch (e: Exception) {
                setSmsType(context, uri, Sms.MESSAGE_TYPE_FAILED)
                throw if (e is SendException) e else SendException(e.message ?: "Couldn't send")
            }
        }
    }

    private fun statusIntent(context: Context, action: String, uri: Uri, subId: Int, request: Int): PendingIntent {
        val intent = Intent(action, uri, context, OmnioSendReceiver::class.java).putExtra(EXTRA_SUB_ID, subId)
        // mutable: Android adds the result details to it
        return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun saveSms(context: Context, address: String, text: String, time: Long, threadId: Long, subId: Int, type: Int): Uri {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.DATE, time)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.BODY, text)
            put(Sms.TYPE, type)
            put(Sms.THREAD_ID, threadId)
            if (type == Sms.MESSAGE_TYPE_SENT) put(Sms.STATUS, Sms.STATUS_COMPLETE)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        return try {
            context.contentResolver.insert(Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: throw SendException("Couldn't save the message")
    }

    private fun markOutbox(context: Context, uri: Uri, time: Long) {
        context.contentResolver.update(uri, ContentValues().apply {
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.DATE, time)
        }, null, null)
    }

    fun setSmsType(context: Context, uri: Uri, type: Int) = try {
        context.contentResolver.update(uri, ContentValues().apply { put(Sms.TYPE, type) }, null, null)
    } catch (e: Exception) {
        0
    }

    // ---- MMS ----

    private fun sendMms(context: Context, text: String, to: List<String>, subId: Int, attachments: List<Attachment>, retryOf: Long?) {
        val config = context.config
        val settings = Settings().apply {
            useSystemSending = true
            deliveryReports = config.enableDeliveryReports
            sendLongAsMms = config.sendLongMessageMMS
            sendLongAsMmsAfter = 1
            group = config.sendGroupMessageMMS
            if (subId >= 0) subscriptionId = subId
        }
        // each attachment goes on its own (carriers limit the size of one message); the text rides with the last
        val batches: List<Pair<String, Attachment?>> =
            if (attachments.isEmpty()) listOf(text to null)
            else attachments.mapIndexed { index, attachment -> (if (index == attachments.lastIndex) text else "") to attachment }

        batches.forEachIndexed { index, (body, attachment) ->
            val message = Message(body, to.toTypedArray())
            if (attachment != null) {
                val (bytes, mime) = prepare(context, attachment, config.mmsFileSizeLimit)
                val name = attachment.name.ifBlank { "attachment" }
                message.addMedia(bytes, if (mime.startsWith("text/")) "application/txt" else mime, name, name)
            }
            val sentIntent = Intent(context, OmnioSendReceiver::class.java).setAction(ACTION_MMS_SENT)
            if (retryOf != null && index == batches.lastIndex) sentIntent.putExtra(EXTRA_RETRY_OF, retryOf)
            val transaction = Transaction(context, settings)
            transaction.setExplicitBroadcastForSentMms(sentIntent)
            try {
                transaction.sendNewMessage(message)
            } catch (e: Exception) {
                throw SendException(e.message ?: "Couldn't send the picture message")
            }
        }
    }

    /** Reads an attachment, shrinking photos to fit the picture-message size limit. */
    private fun prepare(context: Context, attachment: Attachment, limit: Long): Pair<ByteArray, String> {
        val bytes = context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            ?: throw SendException("Couldn't open ${attachment.name}")
        val shrinkable = attachment.mime.startsWith("image/") && attachment.mime != "image/gif"
        if (!shrinkable || limit <= 0 || bytes.size <= limit) return bytes to attachment.mime
        return (shrink(bytes, limit) ?: bytes) to "image/jpeg"
    }

    private fun shrink(bytes: ByteArray, limit: Long): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        bitmap = upright(bitmap, bytes)
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= limit || maxOf(bitmap.width, bitmap.height) < 200) return out.toByteArray()
            if (quality > 45) {
                quality -= 10
            } else {
                bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.75f).toInt(), (bitmap.height * 0.75f).toInt(), true)
            }
        }
    }

    /** Turns a photo the right way up, from the camera's rotation tag. */
    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val degrees = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    // ---- attachment details ----

    fun attachmentFrom(context: Context, uri: Uri): Attachment {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment.orEmpty()
        return Attachment(uri, mime, name)
    }
}
