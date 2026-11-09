package org.fossify.messages.helpers

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Patterns
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R

/**
 * Omnio One: email addresses in texts.
 * - Spots an email address in a message.
 * - Offers to add it to the sender's contact, email it, add it to someone else, or copy it.
 * - Remembers who you asked for their email ("Ask" on the contact page) so their reply can be
 *   offered automatically.
 */
object OmnioEmail {

    data class SenderContact(val contactId: Long, val name: String)

    fun findEmail(text: String): String? = Patterns.EMAIL_ADDRESS.matcher(text).let { if (it.find()) it.group().lowercase() else null }

    /** Opens the sender's Omnio contact page (found by their number). */
    fun openContactPage(activity: Activity, number: String): Boolean {
        val rawId = try {
            val contactId = findContact(activity, number)?.contactId ?: return false
            activity.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(contactId.toString()),
                null
            )?.use { if (it.moveToFirst()) it.getInt(0) else null }
        } catch (e: Exception) {
            null
        } ?: return false
        return try {
            activity.startActivity(Intent().apply {
                setClassName(activity.packageName, "org.fossify.phone.activities.OmnioContactActivity")
                putExtra("omnio_contact_id", rawId)
            })
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- "Ask for email" memory ----

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    private fun key(number: String) = "asked_email_" + number.filter { it.isDigit() }.takeLast(9)

    fun markAsked(context: Context, number: String) =
        prefs(context).edit().putLong(key(number), System.currentTimeMillis()).apply()

    /** When you asked this number for their email (within the last 14 days), or null. */
    fun askedAt(context: Context, number: String): Long? {
        val at = prefs(context).getLong(key(number), 0)
        return if (at > 0 && System.currentTimeMillis() - at < 14L * 24 * 60 * 60 * 1000) at else null
    }

    fun clearAsked(context: Context, number: String) = prefs(context).edit().remove(key(number)).apply()

    // ---- contacts ----

    fun findContact(context: Context, number: String): SenderContact? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { if (it.moveToFirst()) SenderContact(it.getLong(0), it.getString(1) ?: number) else null }
    } catch (e: Exception) {
        null
    }

    fun contactHasEmail(context: Context, contactId: Long, email: String): Boolean = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(0)?.equals(email, true) == true) found = true
            found
        } ?: false
    } catch (e: Exception) {
        false
    }

    fun addEmail(context: Context, contactId: Long, email: String): Boolean = try {
        val rawId = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        if (rawId == null) {
            false
        } else {
            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build()
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op))
            true
        }
    } catch (e: Exception) {
        false
    }

    // ---- the choices ----

    /** Add to the sender / email it / add to someone else / copy. */
    fun showActions(activity: Activity, email: String, senderNumber: String) {
        ensureBackgroundThread {
            val sender = findContact(activity, senderNumber)
            val alreadySaved = sender != null && contactHasEmail(activity, sender.contactId, email)
            activity.runOnUiThread {
                val labels = ArrayList<String>()
                val actions = ArrayList<() -> Unit>()
                if (sender != null && !alreadySaved) {
                    labels.add(activity.getString(R.string.omnio_email_add_to, sender.name))
                    actions.add { addAndConfirm(activity, sender, email, senderNumber) }
                } else if (sender == null) {
                    labels.add(activity.getString(R.string.omnio_email_save_new))
                    actions.add { saveAsNewContact(activity, email, senderNumber) }
                }
                labels.add(activity.getString(R.string.omnio_email_send))
                actions.add { emailIt(activity, email) }
                labels.add(activity.getString(R.string.omnio_email_add_other))
                actions.add { addToAnother(activity, email) }
                labels.add(activity.getString(org.fossify.commons.R.string.copy))
                actions.add { activity.copyToClipboard(email) }

                val title = if (alreadySaved) activity.getString(R.string.omnio_email_already_saved, email) else email
                activity.getAlertDialogBuilder()
                    .setTitle(title)
                    .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** After "Ask": their reply has an email, so offer it straight away. */
    fun offerFromReply(activity: Activity, email: String, senderNumber: String) {
        ensureBackgroundThread {
            val sender = findContact(activity, senderNumber) ?: return@ensureBackgroundThread
            if (contactHasEmail(activity, sender.contactId, email)) {
                clearAsked(activity, senderNumber)
                return@ensureBackgroundThread
            }
            activity.runOnUiThread {
                activity.getAlertDialogBuilder()
                    .setTitle(R.string.omnio_email_reply_title)
                    .setMessage(activity.getString(R.string.omnio_email_reply_message, sender.name, email))
                    .setPositiveButton(R.string.omnio_email_add) { _, _ -> addAndConfirm(activity, sender, email, senderNumber) }
                    .setNeutralButton(R.string.omnio_email_more) { _, _ -> showActions(activity, email, senderNumber) }
                    .setNegativeButton(R.string.omnio_email_not_now) { _, _ -> clearAsked(activity, senderNumber) }
                    .show()
            }
        }
    }

    private fun addAndConfirm(activity: Activity, sender: SenderContact, email: String, senderNumber: String) {
        ensureBackgroundThread {
            val ok = addEmail(activity, sender.contactId, email)
            if (ok) clearAsked(activity, senderNumber)
            activity.runOnUiThread {
                activity.toast(if (ok) activity.getString(R.string.omnio_email_added, sender.name) else activity.getString(org.fossify.commons.R.string.unknown_error_occurred))
            }
        }
    }

    private fun emailIt(activity: Activity, email: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
        } catch (e: Exception) {
            activity.toast(org.fossify.commons.R.string.no_app_found)
        }
    }

    /** The phone's own "pick a contact to add this to" screen, with the email filled in. */
    private fun addToAnother(activity: Activity, email: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            })
        } catch (e: Exception) {
            activity.toast(org.fossify.commons.R.string.no_app_found)
        }
    }

    private fun saveAsNewContact(activity: Activity, email: String, number: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            })
        } catch (e: Exception) {
            activity.toast(org.fossify.commons.R.string.no_app_found)
        }
    }
}
