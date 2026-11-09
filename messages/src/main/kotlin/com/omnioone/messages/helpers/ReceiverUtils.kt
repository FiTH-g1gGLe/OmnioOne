package com.omnioone.messages.helpers

import android.content.Context
import com.omnioone.messages.extensions.config

object ReceiverUtils {

    fun isMessageFilteredOut(context: Context, body: String): Boolean {
        for (blockedKeyword in context.config.blockedKeywords) {
            if (body.contains(blockedKeyword, ignoreCase = true)) {
                return true
            }
        }

        return false
    }
}
