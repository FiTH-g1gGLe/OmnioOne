package com.omnioone.app.activities

import com.omnioone.app.core.OmnioColors
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.omnioone.app.core.applyColorFilter
import com.omnioone.app.core.beGone
import com.omnioone.commons.extensions.getProperBackgroundColor
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.app.core.launchSendSMSIntent
import com.omnioone.app.core.sendEmailIntent
import com.omnioone.app.core.showErrorToast
import com.omnioone.app.core.hasPermission
import com.omnioone.app.core.copyToClipboard
import com.omnioone.app.core.toast
import com.omnioone.app.core.viewBinding
import com.omnioone.commons.helpers.ContactsHelper
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.commons.models.contacts.Contact
import com.omnioone.app.R
import com.omnioone.app.databinding.ActivityOmnioContactBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.getAvailableSIMCardLabels
import com.omnioone.app.extensions.openContactInSystemApp
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.core.beVisibleIf
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.app.helpers.OmnioSocials
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.core.beVisible
import com.omnioone.app.core.getFormattedDuration

// Omnio One contact page: Recent calls and texts with the contact.

/** One line in the contact's Recent list: a call or a text. */
internal data class ActivityItem(
    val time: Long,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val color: Int,
    val onClick: (() -> Unit)?
)

/** Recent calls and texts with this contact, newest first. */
internal fun OmnioContactActivity.loadRecentCalls(contact: Contact) {
    val numbers = contact.phoneNumbers.map { it.normalizedNumber.ifEmpty { it.value } }
    if (numbers.isEmpty()) return
    com.omnioone.app.core.ensureBackgroundThread {
        val calls = com.omnioone.app.helpers.OmnioCallLog.load(this, limit = 400, group = false).map { it.latest }
        val now = System.currentTimeMillis()
        val items = ArrayList<ActivityItem>()
        calls.filter { call -> numbers.any { PhoneNumberUtils.compare(it, call.number) } }
            .take(OmnioContactActivity.MAX_RECENT)
            .forEach { call ->
                val (title, icon, color) = when (call.type) {
                    Calls.MISSED_TYPE -> Triple(R.string.omnio_missed_call, R.drawable.ic_call_missed_vector, OmnioColors.MISSED_SOFT)
                    Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE -> Triple(R.string.omnio_declined_call, R.drawable.ic_call_missed_vector, OmnioColors.MISSED_SOFT)
                    Calls.OUTGOING_TYPE -> Triple(R.string.omnio_outgoing_call, R.drawable.ic_call_made_vector, OmnioColors.CALLS)
                    else -> Triple(R.string.omnio_incoming_call, R.drawable.ic_call_received_vector, OmnioColors.MESSAGES)
                }
                val whenText = DateUtils.getRelativeTimeSpanString(call.date, now, DateUtils.MINUTE_IN_MILLIS).toString()
                val subtitle = if (call.duration > 0) "$whenText · ${com.omnioone.app.adapters.OmnioRecentsAdapter.duration(call.duration)}" else whenText
                items.add(ActivityItem(call.date, getString(title), subtitle, icon, color, null))
            }
        items.addAll(loadRecentTexts(numbers, now))
        val newest = items.sortedByDescending { it.time }.take(OmnioContactActivity.MAX_RECENT)
        runOnUiThread { showRecentActivity(newest) }
    }
}

/** Last few SMS with any of these numbers (needs Omnio One to be allowed to read texts). */
internal fun OmnioContactActivity.loadRecentTexts(numbers: List<String>, now: Long): List<ActivityItem> {
    if (!hasPermission(com.omnioone.app.core.PERMISSION_READ_SMS)) return emptyList()
    val found = ArrayList<ActivityItem>()
    try {
        contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI,
            arrayOf(
                android.provider.Telephony.Sms.ADDRESS,
                android.provider.Telephony.Sms.BODY,
                android.provider.Telephony.Sms.DATE,
                android.provider.Telephony.Sms.TYPE
            ),
            null,
            null,
            "${android.provider.Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext() && found.size < OmnioContactActivity.MAX_RECENT && scanned++ < 1500) {
                val address = cursor.getString(0) ?: continue
                if (numbers.none { PhoneNumberUtils.compare(it, address) }) continue
                val rawBody = (cursor.getString(1) ?: "").replace(Regex("\\s+"), " ").trim()
                val body = com.omnioone.app.messaging.OmnioReactions.describe(rawBody) ?: rawBody
                val date = cursor.getLong(2)
                val received = cursor.getInt(3) == android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
                val whenText = DateUtils.getRelativeTimeSpanString(date, now, DateUtils.MINUTE_IN_MILLIS).toString()
                val label = getString(if (received) R.string.omnio_text_received else R.string.omnio_text_sent)
                val snippet = if (body.length > 60) body.take(60).trimEnd() + "…" else body
                found.add(ActivityItem(date, snippet.ifEmpty { label }, "$label · $whenText", R.drawable.ic_omnio_chat, OmnioColors.MESSAGES) {
                    launchSendSMSIntent(address)
                })
            }
        }
    } catch (ignored: Exception) {
    }
    return found
}

internal fun OmnioContactActivity.showRecentActivity(items: List<ActivityItem>) {
    val holder = binding.contactRecentHolder
    holder.removeAllViews()
    if (items.isEmpty()) {
        holder.beGone()
        binding.contactRecentLabel.beGone()
        return
    }
    holder.beVisible()
    binding.contactRecentLabel.beVisible()
    items.forEachIndexed { index, item ->
        addRow(holder, item.title, item.subtitle, index > 0, item.icon, item.color, item.onClick, null, 0, null)
    }
}
