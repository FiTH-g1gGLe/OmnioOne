package com.omnioone.app.core

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract

/**
 * Omnio One: small, shared ways to add or remove one piece of a contact (an email, a profile
 * link, ...) without rewriting the whole contact. Used by the phone screens and by Messages.
 */
object OmnioContactData {

    /** The first raw contact (the per-account copy) behind a contact, which new data is added to. */
    fun firstRawId(context: Context, contactId: Long): Long? = try {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Adds one data row (e.g. an email or website) to the contact. Returns true when it worked. */
    fun add(context: Context, contactId: Long, mimeType: String, vararg values: Pair<String, Any>): Boolean {
        val rawId = firstRawId(context, contactId) ?: return false
        return try {
            val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
            values.forEach { (key, value) -> builder.withValue(key, value) }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(builder.build()))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Removes one data row by its id. Returns true when it worked. */
    fun remove(context: Context, dataId: Long): Boolean = try {
        val operation = ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)).build()
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
        true
    } catch (e: Exception) {
        false
    }
}
