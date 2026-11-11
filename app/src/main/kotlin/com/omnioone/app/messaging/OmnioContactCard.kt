package com.omnioone.app.messaging

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Omnio One: contact cards (vCards) sent in messages, read and made. */
object OmnioContactCard {

    data class Card(val name: String, val numbers: List<String>, val emails: List<String>)

    fun isCard(mime: String) = mime.contains("vcard", true) || mime == "text/directory"

    /** Reads the first contact in a vCard. */
    fun parse(text: String): Card? {
        // long vCard lines are folded onto the next line, starting with a space
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
        var name = ""
        var structured = ""
        val numbers = ArrayList<String>()
        val emails = ArrayList<String>()
        var started = false
        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:VCARD") -> started = true
                upper.startsWith("END:VCARD") -> if (started) break
                upper.startsWith("FN") && line.contains(':') -> name = clean(line.substringAfter(':'))
                upper.startsWith("N:") || upper.startsWith("N;") -> structured = line.substringAfter(':').split(';').let { parts ->
                    listOfNotNull(parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(0)).map { clean(it) }.filter { it.isNotBlank() }.joinToString(" ")
                }
                upper.startsWith("TEL") && line.contains(':') -> line.substringAfter(':').removePrefix("tel:").trim().takeIf { it.isNotEmpty() }?.let { numbers += it }
                upper.startsWith("EMAIL") && line.contains(':') -> line.substringAfter(':').trim().lowercase().takeIf { it.isNotEmpty() }?.let { emails += it }
            }
        }
        val finalName = name.ifBlank { structured }.ifBlank { numbers.firstOrNull().orEmpty() }
        if (finalName.isBlank() && emails.isEmpty()) return null
        return Card(finalName.ifBlank { emails.first() }, numbers.distinct(), emails.distinct())
    }

    private fun clean(value: String) = value.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").trim()

    fun read(context: Context, uri: Uri): Card? = try {
        context.contentResolver.openInputStream(uri)?.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (e: Exception) {
        null
    }

    /** A picked contact as something to send: its vCard straight from the phone's contacts. */
    fun attachmentFor(context: Context, contactUri: Uri): OmnioSender.Attachment? = try {
        context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use {
            if (!it.moveToFirst()) return null
            val lookup = it.getString(0) ?: return null
            val name = it.getString(1).orEmpty().ifBlank { "contact" }
            OmnioSender.Attachment(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup), "text/x-vcard", "$name.vcf")
        }
    } catch (e: Exception) {
        null
    }
}
