package com.omnioone.app.services

import android.telecom.Call
import android.telecom.CallScreeningService
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.commons.extensions.getMyContactsCursor
import com.omnioone.commons.extensions.isNumberBlocked
import com.omnioone.commons.helpers.ContactLookupResult
import com.omnioone.commons.helpers.SimpleContactsHelper

class SimpleCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        when {
            number != null && isNumberBlocked(number) -> {
                respondToCall(callDetails, isBlocked = true)
            }

            number != null && baseConfig.blockUnknownNumbers -> {
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                respondToCall(callDetails, isBlocked = result == ContactLookupResult.NotFound)
            }

            number == null && baseConfig.blockHiddenNumbers -> {
                respondToCall(callDetails, isBlocked = true)
            }

            else -> {
                respondToCall(callDetails, isBlocked = false)
            }
        }
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .build()

        respondToCall(callDetails, response)
    }
}
