package com.omnioone.app.messaging

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * Omnio One message backup (Messages rewrite, stage 6): saves every text and picture message
 * to one file, and restores from it (skipping anything already on the phone).
 * The file uses the same layout as the previous Messages backups, so older backups still restore.
 */
object OmnioMessageBackup {

    data class Result(val written: Int, val skipped: Int, val failed: Int)

    private const val PDU_FROM = 137
    private const val PDU_TO = 151

    // ---- backing up ----

    /** Writes every message to [output]. Returns how many were saved. Run on a background thread. */
    fun export(context: Context, output: OutputStream): Int {
        val binned = OmnioRecycleBin.keys(context)
        var count = 0
        JsonWriter(output.bufferedWriter()).use { json ->
            json.beginArray()
            context.contentResolver.query(Sms.CONTENT_URI, null, null, null, "${Sms.DATE} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (OmnioRecycleBin.keyOf(false, c.long(Sms._ID)) in binned) continue
                    json.beginObject()
                    json.name("subscriptionId").value(c.long(Sms.SUBSCRIPTION_ID))
                    json.name("address").value(c.str(Sms.ADDRESS).orEmpty())
                    json.name("body").value(c.str(Sms.BODY))
                    json.name("date").value(c.long(Sms.DATE))
                    json.name("dateSent").value(c.long(Sms.DATE_SENT))
                    json.name("locked").value(c.int(Sms.LOCKED))
                    json.name("protocol").value(c.str(Sms.PROTOCOL))
                    json.name("read").value(c.int(Sms.READ))
                    json.name("status").value(c.int(Sms.STATUS))
                    json.name("type").value(c.int(Sms.TYPE))
                    json.name("serviceCenter").value(c.str(Sms.SERVICE_CENTER))
                    json.name("backupType").value("sms")
                    json.endObject()
                    count++
                }
            }
            context.contentResolver.query(Mms.CONTENT_URI, null, null, null, "${Mms.DATE} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.long(Mms._ID)
                    if (OmnioRecycleBin.keyOf(true, id) in binned) continue
                    json.beginObject()
                    json.name("creator").value(c.str(Mms.CREATOR))
                    json.name("contentType").value(c.str(Mms.CONTENT_TYPE))
                    json.name("deliveryReport").value(c.int(Mms.DELIVERY_REPORT))
                    json.name("date").value(c.long(Mms.DATE))
                    json.name("dateSent").value(c.long(Mms.DATE_SENT))
                    json.name("locked").value(c.int(Mms.LOCKED))
                    json.name("messageType").value(c.int(Mms.MESSAGE_TYPE))
                    json.name("messageBox").value(c.int(Mms.MESSAGE_BOX))
                    json.name("read").value(c.int(Mms.READ))
                    json.name("readReport").value(c.int(Mms.READ_REPORT))
                    json.name("seen").value(c.int(Mms.SEEN))
                    json.name("textOnly").value(c.int(Mms.TEXT_ONLY))
                    json.name("status").value(c.str(Mms.STATUS))
                    json.name("subject").value(c.str(Mms.SUBJECT))
                    json.name("subjectCharSet").value(c.str(Mms.SUBJECT_CHARSET))
                    json.name("subscriptionId").value(c.long(Mms.SUBSCRIPTION_ID))
                    json.name("transactionId").value(c.str(Mms.TRANSACTION_ID))
                    writeAddresses(context, json, id)
                    writeParts(context, json, id)
                    json.name("backupType").value("mms")
                    json.endObject()
                    count++
                }
            }
            json.endArray()
        }
        return count
    }

    private fun writeAddresses(context: Context, json: JsonWriter, id: Long) {
        json.name("addresses").beginArray()
        context.contentResolver.query(Uri.parse("content://mms/$id/addr"), null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                json.beginObject()
                json.name("address").value(c.str(Mms.Addr.ADDRESS).orEmpty())
                json.name("type").value(c.int(Mms.Addr.TYPE))
                json.name("charset").value(c.int(Mms.Addr.CHARSET))
                json.endObject()
            }
        }
        json.endArray()
    }

    private fun writeParts(context: Context, json: JsonWriter, id: Long) {
        json.name("parts").beginArray()
        context.contentResolver.query(Uri.parse("content://mms/part"), null, "${Mms.Part.MSG_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.str(Mms.Part.CONTENT_TYPE).orEmpty()
                json.beginObject()
                json.name("contentDisposition").value(c.str(Mms.Part.CONTENT_DISPOSITION))
                json.name("charset").value(c.str(Mms.Part.CHARSET))
                json.name("contentId").value(c.str(Mms.Part.CONTENT_ID))
                json.name("contentLocation").value(c.str(Mms.Part.CONTENT_LOCATION))
                json.name("contentType").value(type)
                json.name("ctStart").value(c.str(Mms.Part.CT_START))
                json.name("ctType").value(c.str(Mms.Part.CT_TYPE))
                json.name("filename").value(c.str(Mms.Part.FILENAME))
                json.name("name").value(c.str(Mms.Part.NAME))
                json.name("sequenceOrder").value(c.int(Mms.Part.SEQ))
                json.name("text").value(c.str(Mms.Part.TEXT))
                val data = if (isText(type)) null else try {
                    context.contentResolver.openInputStream(Uri.parse("content://mms/part/${c.long(Mms.Part._ID)}"))?.use {
                        Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    null
                }
                json.name("data").value(data)
                json.endObject()
            }
        }
        json.endArray()
    }

    private fun isText(type: String) = type == "text/plain" || type == "application/smil"

    // ---- restoring ----

    /** Reads a backup and adds any messages not already on the phone. Run on a background thread. */
    fun import(context: Context, input: InputStream): Result {
        var written = 0
        var skipped = 0
        var failed = 0
        val threads = HashSet<Long>()
        JsonReader(input.bufferedReader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                val item = try {
                    readValue(reader) as? Map<*, *>
                } catch (e: Exception) {
                    failed++
                    null
                } ?: continue
                try {
                    val outcome = when (item["backupType"]) {
                        "sms" -> restoreSms(context, item, threads)
                        "mms" -> restoreMms(context, item, threads)
                        else -> null
                    }
                    when (outcome) {
                        true -> written++
                        false -> skipped++
                        null -> failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }
            reader.endArray()
        }
        // let Android refresh each chat's date and last message
        try {
            context.contentResolver.delete(Telephony.Threads.CONTENT_URI, "1 = 0", null)
        } catch (ignored: Exception) {
        }
        OmnioMessagesBus.changed()
        return Result(written, skipped, failed)
    }

    /** Older and newer phones may use different SIM numbers, so restored messages aren't tied to one. */
    private fun subId(item: Map<*, *>): Long = if (Build.VERSION.SDK_INT >= 34) -1 else item.long("subscriptionId", "sub_id")

    private fun restoreSms(context: Context, item: Map<*, *>, threads: MutableSet<Long>): Boolean? {
        val address = item.str("address") ?: return null
        val date = item.long("date")
        val type = item.int("type")
        val exists = context.contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms._ID), "${Sms.DATE} = ? AND ${Sms.ADDRESS} = ? AND ${Sms.TYPE} = ?",
            arrayOf(date.toString(), address, type.toString()), null
        )?.use { it.count > 0 } ?: false
        if (exists) return false
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Sms.SUBSCRIPTION_ID, subId(item))
            put(Sms.ADDRESS, address)
            put(Sms.BODY, item.str("body"))
            put(Sms.DATE, date)
            put(Sms.DATE_SENT, item.long("dateSent", "date_sent"))
            put(Sms.LOCKED, item.int("locked"))
            put(Sms.PROTOCOL, item.str("protocol"))
            put(Sms.READ, item.int("read"))
            put(Sms.STATUS, item.int("status"))
            put(Sms.TYPE, type)
            put(Sms.SERVICE_CENTER, item.str("serviceCenter", "service_center"))
            put(Sms.THREAD_ID, threadId)
        }
        context.contentResolver.insert(Sms.CONTENT_URI, values) ?: return null
        threads += threadId
        return true
    }

    private fun restoreMms(context: Context, item: Map<*, *>, threads: MutableSet<Long>): Boolean? {
        val box = item.int("messageBox", "msg_box")
        val addresses = (item["addresses"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val people = addresses.filter { it.int("type") == (if (box == Mms.MESSAGE_BOX_INBOX) PDU_FROM else PDU_TO) }
            .mapNotNull { it.str("address") }.filter { it.isNotBlank() && it != "insert-address-token" }
        val everyone = addresses.mapNotNull { it.str("address") }.filter { it.isNotBlank() && it != "insert-address-token" }.toSet()
        val threadAddresses = if (box == Mms.MESSAGE_BOX_INBOX && everyone.size > 2) everyone else people.toSet()
        if (threadAddresses.isEmpty()) return null
        val threadId = Telephony.Threads.getOrCreateThreadId(context, threadAddresses)
        val date = item.long("date")
        val dateSent = item.long("dateSent", "date_sent")
        val existing = context.contentResolver.query(
            Mms.CONTENT_URI, arrayOf(Mms._ID),
            "${Mms.DATE} = ? AND ${Mms.DATE_SENT} = ? AND ${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} = ?",
            arrayOf(date.toString(), dateSent.toString(), threadId.toString(), box.toString()), null
        )?.use { it.count > 0 } ?: false
        if (existing) return false

        val values = ContentValues().apply {
            put(Mms.TRANSACTION_ID, item.str("transactionId", "tr_id"))
            put(Mms.SUBSCRIPTION_ID, subId(item))
            put(Mms.SUBJECT, item.str("subject", "sub"))
            put(Mms.DATE, date)
            put(Mms.DATE_SENT, dateSent)
            put(Mms.LOCKED, item.int("locked"))
            put(Mms.READ, item.int("read"))
            put(Mms.STATUS, item.str("status", "st"))
            put(Mms.SUBJECT_CHARSET, item.str("subjectCharSet", "sub_cs"))
            put(Mms.SEEN, item.int("seen"))
            put(Mms.MESSAGE_TYPE, item.int("messageType", "m_type"))
            put(Mms.MESSAGE_BOX, box)
            put(Mms.DELIVERY_REPORT, item.int("deliveryReport", "d_rpt"))
            put(Mms.READ_REPORT, item.int("readReport", "rr"))
            put(Mms.CONTENT_TYPE, item.str("contentType", "ct_t"))
            put(Mms.TEXT_ONLY, item.int("textOnly", "text_only"))
            put(Mms.THREAD_ID, threadId)
        }
        val uri = context.contentResolver.insert(Mms.CONTENT_URI, values) ?: return null
        val messageId = uri.lastPathSegment?.toLongOrNull() ?: return null

        (item["parts"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().forEach { part ->
            val type = part.str("contentType", "ct") ?: return@forEach
            val partValues = ContentValues().apply {
                put(Mms.Part.MSG_ID, messageId)
                put(Mms.Part.CONTENT_DISPOSITION, part.str("contentDisposition", "cd"))
                put(Mms.Part.CHARSET, part.str("charset", "chset"))
                put(Mms.Part.CONTENT_ID, part.str("contentId", "cid"))
                put(Mms.Part.CONTENT_LOCATION, part.str("contentLocation", "cl"))
                put(Mms.Part.CONTENT_TYPE, type)
                put(Mms.Part.CT_START, part.str("ctStart", "ctt_s"))
                put(Mms.Part.CT_TYPE, part.str("ctType", "ctt_t"))
                put(Mms.Part.FILENAME, part.str("filename", "fn"))
                put(Mms.Part.NAME, part.str("name"))
                put(Mms.Part.SEQ, part.int("sequenceOrder", "seq"))
                put(Mms.Part.TEXT, part.str("text"))
            }
            val partUri = context.contentResolver.insert(Uri.parse("content://mms/$messageId/part"), partValues) ?: return@forEach
            val data = part.str("data")
            if (!isText(type) && !data.isNullOrEmpty()) try {
                context.contentResolver.openOutputStream(partUri)?.use { it.write(Base64.decode(data, Base64.DEFAULT)) }
            } catch (ignored: Exception) {
            }
        }
        addresses.forEach { address ->
            context.contentResolver.insert(Uri.parse("content://mms/$messageId/addr"), ContentValues().apply {
                put(Mms.Addr.MSG_ID, messageId)
                put(Mms.Addr.ADDRESS, address.str("address"))
                put(Mms.Addr.TYPE, address.int("type"))
                put(Mms.Addr.CHARSET, address.int("charset").takeIf { it != 0 } ?: 106)
            })
        }
        threads += threadId
        return true
    }

    // ---- reading helpers ----

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val map = HashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) map[reader.nextName()] = readValue(reader)
            reader.endObject()
            map
        }
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<Any?>()
            reader.beginArray()
            while (reader.hasNext()) list += readValue(reader)
            reader.endArray()
            list
        }
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NUMBER -> reader.nextString()
        else -> reader.nextString()
    }

    private fun Map<*, *>.raw(vararg keys: String) = keys.asSequence().mapNotNull { this[it] }.firstOrNull()
    private fun Map<*, *>.str(vararg keys: String) = raw(*keys)?.toString()
    private fun Map<*, *>.long(vararg keys: String) = str(*keys)?.toDoubleOrNull()?.toLong() ?: 0L
    private fun Map<*, *>.int(vararg keys: String) = long(*keys).toInt()

    private fun Cursor.index(column: String) = getColumnIndex(column)
    private fun Cursor.str(column: String) = index(column).takeIf { it >= 0 }?.let { getString(it) }
    private fun Cursor.long(column: String) = index(column).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L
    private fun Cursor.int(column: String) = index(column).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
}
