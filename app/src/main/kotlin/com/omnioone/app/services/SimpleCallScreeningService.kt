package com.omnioone.app.services

import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import com.omnioone.app.core.baseConfig

/** Omnio One: turns away blocked numbers, and unknown or hidden callers if you've chosen that. */
class SimpleCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val block = when {
            number.isEmpty() -> baseConfig.blockHiddenNumbers
            isBlocked(number) -> true
            baseConfig.blockUnknownNumbers -> !isSaved(number)
            else -> false
        }
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(block)
                .setRejectCall(block)
                .setSkipCallLog(block)
                .setSkipNotification(block)
                .build()
        )
    }

    private fun isBlocked(number: String) = try {
        BlockedNumberContract.isBlocked(this, number)
    } catch (e: Exception) {
        false
    }

    private fun isSaved(number: String) = try {
        contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        true // if we can't check, don't block
    }
}
