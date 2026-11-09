package com.omnioone.app.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

/**
 * Omnio One's own call history: reads Android's call log, works out who each number is,
 * and groups back-to-back calls with the same person on the same day into one entry.
 */
object OmnioCallLog {

    data class Call(
        val id: Long,
        val number: String,
        val type: Int,
        val date: Long,
        val duration: Long,
        val accountId: String?,
    ) {
        val isMissed get() = type == Calls.MISSED_TYPE
        val isIncoming get() = type == Calls.INCOMING_TYPE || type == Calls.ANSWERED_EXTERNALLY_TYPE
        val isOutgoing get() = type == Calls.OUTGOING_TYPE
        val isDeclined get() = type == Calls.REJECTED_TYPE || type == Calls.BLOCKED_TYPE
    }

    data class Person(val name: String, val photoUri: String, val contactId: Long, val label: String)

    /** One row in Recents: a person (or number) and the calls grouped under it, newest first. */
    data class Entry(val number: String, val person: Person?, val calls: List<Call>) {
        val latest get() = calls.first()
        val name get() = person?.name?.ifEmpty { null } ?: number.ifEmpty { "?" }
        val isSaved get() = person != null
        val isPrivate get() = number.isEmpty()
        val key get() = latest.id
        val hasMissed get() = calls.any { it.isMissed }
    }

    private fun sameNumber(a: String, b: String) = a == b || (a.isNotEmpty() && b.isNotEmpty() && PhoneNumberUtils.compare(a, b))

    private fun dayOf(time: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return calendar.get(java.util.Calendar.YEAR) * 1000L + calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Reads up to [limit] calls. Run on a background thread. */
    @SuppressLint("MissingPermission")
    fun load(context: Context, limit: Int = 600, group: Boolean = true): List<Entry> {
        val calls = ArrayList<Call>()
        try {
            context.contentResolver.query(
                Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build(),
                arrayOf(Calls._ID, Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.PHONE_ACCOUNT_ID),
                null, null, "${Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    calls += Call(
                        id = cursor.getLong(0),
                        number = cursor.getString(1).orEmpty(),
                        type = cursor.getInt(2),
                        date = cursor.getLong(3),
                        duration = cursor.getLong(4),
                        accountId = cursor.getString(5),
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val people = HashMap<String, Person?>()
        fun personFor(number: String): Person? = if (number.isEmpty()) null else people.getOrPut(number) { lookup(context, number) }

        val entries = ArrayList<Entry>()
        var current: MutableList<Call>? = null
        for (call in calls) {
            val previous = current?.first()
            if (group && previous != null && sameNumber(previous.number, call.number) && dayOf(previous.date) == dayOf(call.date)) {
                current!!.add(call)
            } else {
                current = mutableListOf(call)
                entries += Entry(call.number, personFor(call.number), current)
            }
        }
        return entries
    }

    /** Who a number belongs to in your contacts, or null. */
    fun lookup(context: Context, number: String): Person? = try {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.TYPE,
                ContactsContract.PhoneLookup.LABEL
            ),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4).orEmpty()).toString()
            Person(cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getLong(0), label)
        }
    } catch (e: Exception) {
        null
    }

    /** Removes calls from the history. Run on a background thread. */
    fun delete(context: Context, ids: Collection<Long>) {
        ids.chunked(200).forEach { chunk ->
            try {
                context.contentResolver.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null)
            } catch (ignored: Exception) {
            }
        }
    }

    /** Empties the whole call history. Run on a background thread. */
    fun clearAll(context: Context) {
        try {
            context.contentResolver.delete(Calls.CONTENT_URI, null, null)
        } catch (ignored: Exception) {
        }
    }

    /** Your call history as JSON, and how many calls it holds. Run on a background thread. */
    fun exportJson(context: Context): Pair<String, Int> {
        val array = org.json.JSONArray()
        load(context, limit = 100000, group = false).forEach { entry ->
            val call = entry.latest
            array.put(org.json.JSONObject().apply {
                put("number", call.number)
                put("type", call.type)
                put("date", call.date)
                put("duration", call.duration)
            })
        }
        return array.toString() to array.length()
    }

    /**
     * Adds calls from an export back into the history (skipping ones already there).
     * Also reads files saved by the app's earlier call history export. Returns how many were added.
     */
    fun importJson(context: Context, text: String): Int {
        val array = org.json.JSONArray(text)
        val existing = HashSet<String>()
        load(context, limit = 100000, group = false).forEach { existing += "${it.number}|${it.latest.date}" }
        var added = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val number = item.optString("number").ifEmpty { item.optString("phoneNumber") }
            val date = item.optLong("date", item.optLong("startTS", 0L))
            if (date <= 0L || "$number|$date" in existing) continue
            val values = android.content.ContentValues().apply {
                put(Calls.NUMBER, number)
                put(Calls.TYPE, item.optInt("type", Calls.INCOMING_TYPE))
                put(Calls.DATE, date)
                put(Calls.DURATION, item.optLong("duration", 0L))
                put(Calls.NEW, 0)
            }
            try {
                context.contentResolver.insert(Calls.CONTENT_URI, values)
                added++
            } catch (ignored: Exception) {
            }
        }
        return added
    }
}
