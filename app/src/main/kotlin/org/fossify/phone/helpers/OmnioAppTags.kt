package org.fossify.phone.helpers

import android.content.Context
import android.provider.ContactsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * Omnio One: works out which chat apps each contact uses, from the entries those apps
 * add to the phone's contacts (the same ones that power the WhatsApp/Telegram buttons).
 */
object OmnioAppTags {

    private data class App(val name: String, val color: Int)

    private val apps = linkedMapOf(
        "vnd.android.cursor.item/vnd.com.whatsapp.profile" to App("WhatsApp", 0xFF5DD39E.toInt()),
        "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile" to App("Telegram", 0xFF6CBCF2.toInt()),
        "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact" to App("Signal", 0xFF8FA8FF.toInt()),
    )

    /** Contact id -> coloured "WhatsApp · Telegram" text. Run on a background thread. */
    fun load(context: Context): Map<Int, CharSequence> {
        val found = HashMap<Int, LinkedHashSet<App>>()
        try {
            val selection = "${ContactsContract.Data.MIMETYPE} IN (${apps.keys.joinToString { "?" }})"
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
                selection,
                apps.keys.toTypedArray(),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getInt(0)
                    val app = apps[cursor.getString(1)] ?: continue
                    found.getOrPut(contactId) { LinkedHashSet() }.add(app)
                }
            }
        } catch (ignored: Exception) {
        }

        val order = apps.values.toList()
        return found.mapValues { (_, used) ->
            val text = SpannableStringBuilder()
            used.sortedBy { order.indexOf(it) }.forEachIndexed { index, app ->
                if (index > 0) text.append("  ·  ")
                val start = text.length
                text.append(app.name)
                text.setSpan(ForegroundColorSpan(app.color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text
        }
    }
}
