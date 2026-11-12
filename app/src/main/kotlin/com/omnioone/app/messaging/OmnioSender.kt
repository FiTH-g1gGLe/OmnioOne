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
import android.telephony.CarrierConfigManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import com.omnioone.app.messaging.messagesConfig as config
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

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
    const val EXTRA_GROUP_COPY = "omnio_group_copy"

    /** Every send goes through this one line, so messages always go out in the order you sent them. */
    private val line = Executors.newSingleThreadExecutor()

    fun queue(work: () -> Unit) {
        line.execute {
            try {
                work()
            } catch (ignored: Throwable) {
            }
        }
    }

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

    /** Seconds to wait before sending, so you can undo (0 = send straight away, the default). */
    fun undoSeconds(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getInt("undo_send", 0)

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
            if (message.address.contains('|')) {
                // the group chat's copy: send to everyone again and reuse that copy
                sendSms(context, message.body, message.address.split('|').filter { it.isNotBlank() }, subId, existing = null, groupCopy = uri)
            } else {
                sendSms(context, message.body, listOf(message.address.ifEmpty { addresses.first() }), subId, existing = uri)
            }
        }
    }

    /** Sends a failed text again from just its saved copy (used when signal comes back). */
    fun resend(context: Context, uri: Uri) {
        var address = ""
        var body = ""
        var subId = -1
        var type = 0
        context.contentResolver.query(uri, arrayOf(Sms.ADDRESS, Sms.BODY, Sms.SUBSCRIPTION_ID, Sms.TYPE), null, null, null)?.use {
            if (it.moveToFirst()) {
                address = it.getString(0).orEmpty()
                body = it.getString(1).orEmpty()
                subId = it.getInt(2)
                type = it.getInt(3)
            }
        }
        if (address.isEmpty() || body.isEmpty() || type != Sms.MESSAGE_TYPE_FAILED) return
        sendSms(context, body, listOf(address), subId, existing = uri)
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

    private fun sendSms(context: Context, text: String, to: List<String>, subId: Int, existing: Uri?, groupCopy: Uri? = null) {
        val now = System.currentTimeMillis()
        // a group of separate texts: the group chat gets one copy so it shows there too, and each person gets their own text
        val group = when {
            groupCopy != null -> groupCopy.also { setSmsType(context, it, Sms.MESSAGE_TYPE_SENT) }
            to.size > 1 && existing == null ->
                saveSms(context, to.joinToString("|"), text, now, Telephony.Threads.getOrCreateThreadId(context, to.toSet()), subId, Sms.MESSAGE_TYPE_SENT)
            else -> null
        }
        val reports = context.config.enableDeliveryReports
        var failures = 0
        var firstError: SendException? = null
        // one number failing doesn't stop the others
        to.forEach { address ->
            var uri: Uri? = null
            try {
                uri = existing?.also { markOutbox(context, it, now) }
                    ?: saveSms(context, address, text, now, Telephony.Threads.getOrCreateThreadId(context, address), subId, Sms.MESSAGE_TYPE_OUTBOX)
                val destination = PhoneNumberUtils.stripSeparators(address).ifEmpty { throw SendException("That number looks empty") }
                val manager = smsManager(context, subId)
                val parts = manager.divideMessage(text)
                if (parts.isNullOrEmpty()) throw SendException("There's nothing to send")
                val messageId = ContentUris.parseId(uri)
                val sent = ArrayList<PendingIntent>()
                val delivered = ArrayList<PendingIntent?>()
                parts.indices.forEach { index ->
                    val request = (messageId * 16 + index).toInt()
                    sent += statusIntent(context, ACTION_SENT, uri, subId, request, group)
                    delivered += if (reports && index == parts.lastIndex) statusIntent(context, ACTION_DELIVERED, uri, subId, request, null) else null
                }
                manager.sendMultipartTextMessage(destination, null, parts, sent, delivered)
            } catch (e: Exception) {
                uri?.let { setSmsType(context, it, Sms.MESSAGE_TYPE_FAILED) }
                failures++
                if (firstError == null) firstError = e as? SendException ?: SendException("Your phone couldn't send it")
            }
        }
        if (failures > 0) {
            group?.let { setSmsType(context, it, Sms.MESSAGE_TYPE_FAILED) }
            throw if (to.size > 1) SendException("It didn't go to $failures of ${to.size} people") else firstError!!
        }
    }

    private fun statusIntent(context: Context, action: String, uri: Uri, subId: Int, request: Int, groupCopy: Uri?): PendingIntent {
        val intent = Intent(action, uri, context, OmnioSendReceiver::class.java).putExtra(EXTRA_SUB_ID, subId)
        groupCopy?.let { intent.putExtra(EXTRA_GROUP_COPY, it.toString()) }
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
        } ?: throw SendException("Couldn't save the message on your phone")
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

    /**
     * The most one picture message can carry on this SIM, as your carrier reports it
     * (about 300 KB if it doesn't say).
     */
    fun carrierLimit(context: Context, subId: Int): Long {
        val id = if (subId >= 0) subId else defaultSubId()
        val reported = try {
            val manager = context.getSystemService(CarrierConfigManager::class.java)
            val bundle = if (id >= 0) manager?.getConfigForSubId(id) else manager?.config
            bundle?.getInt("maxMessageSize", 0) ?: 0
        } catch (e: Exception) {
            0
        }
        return (if (reported > 0) reported.toLong() else 300_000L)
    }

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
        // leave room for the text and the message wrapping
        val carrier = (carrierLimit(context, subId) * 0.9).toLong()
        val userLimit = config.mmsFileSizeLimit
        val perPhoto = if (userLimit > 0) minOf(userLimit, carrier) else carrier

        // get every attachment ready first, so a problem stops the send before anything has gone
        val ready = attachments.map { prepare(context, it, perPhoto, carrier) }

        // pack as many as fit into each picture message; the text rides with the last one
        val batches = ArrayList<ArrayList<Prepared>>()
        var size = 0L
        ready.forEach { item ->
            if (batches.isEmpty() || size + item.bytes.size > carrier) {
                batches += ArrayList<Prepared>()
                size = 0
            }
            batches.last() += item
            size += item.bytes.size
        }
        if (batches.isEmpty()) batches += ArrayList<Prepared>()

        batches.forEachIndexed { index, batch ->
            val message = Message(if (index == batches.lastIndex) text else "", to.toTypedArray())
            batch.forEach { item -> message.addMedia(item.bytes, item.mime, item.name, item.name) }
            val sentIntent = Intent(context, OmnioSendReceiver::class.java).setAction(ACTION_MMS_SENT)
            if (retryOf != null && index == batches.lastIndex) sentIntent.putExtra(EXTRA_RETRY_OF, retryOf)
            val transaction = Transaction(context, settings)
            transaction.setExplicitBroadcastForSentMms(sentIntent)
            try {
                transaction.sendNewMessage(message)
            } catch (e: Exception) {
                throw SendException("Your phone couldn't send the picture message")
            } catch (e: OutOfMemoryError) {
                throw SendException("That's too big to send by text")
            }
        }
    }

    private class Prepared(val bytes: ByteArray, val mime: String, val name: String)

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /** Reads an attachment, shrinking photos to fit. Anything else too big for a picture message is refused with a clear reason. */
    private fun prepare(context: Context, attachment: Attachment, perPhoto: Long, carrier: Long): Prepared {
        val name = attachment.name.ifBlank { "attachment" }
        val shrinkable = attachment.mime.startsWith("image/") && attachment.mime != "image/gif"
        val size = sizeOf(context, attachment.uri)
        try {
            if (shrinkable) {
                // photos are decoded straight from the file at a smaller size, so even huge ones don't fill the memory
                if (size in 1..perPhoto) {
                    val bytes = readAll(context, attachment) ?: throw SendException("Couldn't open $name")
                    return Prepared(bytes, attachment.mime, name)
                }
                val shrunk = shrinkFrom(context, attachment.uri, perPhoto) ?: throw SendException("Couldn't read the photo $name")
                return Prepared(shrunk, "image/jpeg", name)
            }
            if (size > carrier) throw SendException("$name is too big to send by text (${carrier / 1000} KB max)")
            val bytes = readAll(context, attachment) ?: throw SendException("Couldn't open $name")
            if (bytes.size > carrier) throw SendException("$name is too big to send by text (${carrier / 1000} KB max)")
            val mime = if (attachment.mime == "text/plain") "application/txt" else attachment.mime
            return Prepared(bytes, mime, name)
        } catch (e: OutOfMemoryError) {
            throw SendException("$name is too big to send by text")
        }
    }

    private fun readAll(context: Context, attachment: Attachment): ByteArray? =
        context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }

    private fun shrinkFrom(context: Context, uri: Uri, limit: Long): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use { rotationOf(ExifInterface(it)) } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (degrees != 0f) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
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

    private fun rotationOf(exif: ExifInterface) = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
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
