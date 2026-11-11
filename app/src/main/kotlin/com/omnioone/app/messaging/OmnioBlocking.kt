package com.omnioone.app.messaging

import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.ContactsContract
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.messages.extensions.config

/** Omnio One: decides whether an incoming message should be dropped (blocked number, unknown sender or blocked word). */
object OmnioBlocking {

    fun shouldDrop(context: Context, address: String, body: String) =
        hasBlockedWord(context, body) || isBlockedSender(context, address)

    fun isBlockedSender(context: Context, address: String) =
        isBlocked(context, address) || (context.baseConfig.blockUnknownNumbers && !isInContacts(context, address))

    fun hasBlockedWord(context: Context, body: String) =
        context.config.blockedKeywords.any { it.isNotBlank() && body.contains(it, ignoreCase = true) }

    /** On the phone's blocked list, including patterns like +44800* */
    fun isBlocked(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        try {
            if (BlockedNumberContract.isBlocked(context, number)) return true
        } catch (ignored: Exception) {
        }
        return try {
            context.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }
                    .filter { it.contains('*') }
                    .any { pattern -> number.matches(Regex(pattern.replace("+", "\\+").replace("*", ".*"))) }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun isInContacts(context: Context, number: String): Boolean = try {
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        // if we can't check, don't drop the message
        true
    }
}
