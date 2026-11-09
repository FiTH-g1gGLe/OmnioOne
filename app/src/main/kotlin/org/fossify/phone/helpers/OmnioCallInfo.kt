package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils

/**
 * Omnio One: who's calling, plus the "call prep" details shown on the Context call screen
 * (last text, birthday, notes, chat apps). Run [load] on a background thread.
 */
data class OmnioCallInfo(
    val number: String,
    val name: String,
    val photoUri: String,
    val label: String,
    val contactId: Long?,
    val lastText: String?,
    val birthday: String?,
    val notes: String?,
    val apps: List<OmnioAppTags.Badge>,
) {
    val isSaved get() = contactId != null
    val displayName get() = name.ifEmpty { number.ifEmpty { "Unknown" } }

    companion object {
        fun preview() = OmnioCallInfo(
            number = "07700 900123",
            name = "Alex Morgan",
            photoUri = "",
            label = "Mobile",
            contactId = null,
            lastText = "See you at 8 then!",
            birthday = "Birthday in 3 days",
            notes = "Owes me a coffee",
            apps = emptyList(),
        )

        fun load(context: Context, number: String): OmnioCallInfo {
            var name = ""
            var photo = ""
            var label = ""
            var contactId: Long? = null
            if (number.isNotEmpty()) {
                try {
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
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            contactId = c.getLong(0)
                            name = c.getString(1) ?: ""
                            photo = c.getString(2) ?: ""
                            label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4) ?: "").toString()
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
            val id = contactId
            return OmnioCallInfo(
                number = number,
                name = name,
                photoUri = photo,
                label = label,
                contactId = id,
                lastText = lastText(context, number),
                birthday = id?.let { birthday(context, it) },
                notes = id?.let { notes(context, it) },
                apps = id?.let { cid ->
                    OmnioSocials.findChats(context, cid.toInt()).map { OmnioAppTags.Badge(it.app.name, it.app.pkg, it.app.color) }.distinct()
                } ?: emptyList(),
            )
        }

        private fun lastText(context: Context, number: String): String? {
            if (number.isEmpty()) return null
            return try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                    null, null, "${Telephony.Sms.DATE} DESC"
                )?.use { c ->
                    var scanned = 0
                    while (c.moveToNext() && scanned++ < 800) {
                        val address = c.getString(0) ?: continue
                        if (!PhoneNumberUtils.compare(address, number)) continue
                        val body = (c.getString(1) ?: "").replace(Regex("\\s+"), " ").trim()
                        if (body.isEmpty()) continue
                        val ago = DateUtils.getRelativeTimeSpanString(c.getLong(2), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        val shown = org.fossify.messages.helpers.OmnioReactions.describe(body) ?: body
                        return@use "“${if (shown.length > 80) shown.take(80).trimEnd() + "…" else shown}” · $ago"
                    }
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /** "Birthday today", "Birthday tomorrow" or "Birthday in 5 days" when it's within a fortnight. */
        private fun birthday(context: Context, contactId: Long): String? {
            val value = try {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                    arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                    null
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (e: Exception) {
                null
            } ?: return null
            return try {
                val parts = value.removePrefix("--").split("-").map { it.toInt() }
                val (month, day) = if (value.startsWith("--")) parts[0] to parts[1] else parts[1] to parts[2]
                val today = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                val next = (today.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.MONTH, month - 1)
                    set(java.util.Calendar.DAY_OF_MONTH, day)
                    if (before(today)) add(java.util.Calendar.YEAR, 1)
                }
                val days = ((next.timeInMillis - today.timeInMillis + DateUtils.DAY_IN_MILLIS / 2) / DateUtils.DAY_IN_MILLIS).toInt()
                when {
                    days == 0 -> "🎂 Birthday today"
                    days == 1 -> "🎂 Birthday tomorrow"
                    days <= 14 -> "🎂 Birthday in $days days"
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun notes(context: Context, contactId: Long): String? = try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                var found: String? = null
                while (c.moveToNext() && found == null) found = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                found
            }
        } catch (e: Exception) {
            null
        }
    }
}
