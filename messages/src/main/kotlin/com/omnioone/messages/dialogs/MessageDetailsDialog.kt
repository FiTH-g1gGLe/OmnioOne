package com.omnioone.messages.dialogs

import android.annotation.SuppressLint
import android.telephony.SubscriptionInfo
import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.dialogs.BasePropertiesDialog
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.getTimeFormat
import com.omnioone.commons.extensions.getTimeFormatWithSeconds
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.messages.R
import com.omnioone.messages.extensions.config
import com.omnioone.messages.extensions.subscriptionManagerCompat
import com.omnioone.messages.models.Message
import org.joda.time.DateTime

class MessageDetailsDialog(val activity: BaseSimpleActivity, val message: Message) : BasePropertiesDialog(activity) {
    init {
        @SuppressLint("MissingPermission")
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()

        addProperty(message.getSenderOrReceiverLabel(), message.getSenderOrReceiverPhoneNumbers())
        if (availableSIMs.count() > 1) {
            addProperty(R.string.message_details_sim, message.getSIM(availableSIMs))
        }
        addProperty(message.getSentOrReceivedAtLabel(), message.getSentOrReceivedAt())

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok) { _, _ -> }
            .apply {
                activity.setupDialogStuff(mDialogView.root, this, R.string.message_details)
            }
    }

    private fun Message.getSenderOrReceiverLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_sender
        } else {
            R.string.message_details_receiver
        }
    }

    private fun Message.getSenderOrReceiverPhoneNumbers(): String {
        return if (isReceivedMessage()) {
            formatContactInfo(senderName, senderPhoneNumber)
        } else {
            participants.joinToString(", ") {
                formatContactInfo(it.name, it.phoneNumbers.first().value)
            }
        }
    }

    private fun formatContactInfo(name: String, phoneNumber: String): String {
        return if (name != phoneNumber) {
            "$name ($phoneNumber)"
        } else {
            phoneNumber
        }
    }

    private fun Message.getSIM(availableSIMs: List<SubscriptionInfo>): String {
        return availableSIMs.firstOrNull { it.subscriptionId == subscriptionId }?.displayName?.toString()
            ?: activity.getString(com.omnioone.commons.R.string.unknown)
    }

    private fun Message.getSentOrReceivedAtLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_received_at
        } else {
            R.string.message_details_sent_at
        }
    }

    private fun Message.getSentOrReceivedAt(): String {
        return DateTime(date * 1000L).toString("${activity.config.dateFormat} ${activity.getTimeFormatWithSeconds()}")
    }
}
