package org.fossify.phone.activities

import org.fossify.commons.helpers.OmnioColors
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
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.sendEmailIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityOmnioContactBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.extensions.openContactInSystemApp
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.phone.helpers.OmnioSocials
import org.fossify.phone.helpers.OmnioSheet
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getFormattedDuration

// Omnio One contact page: Number(s) and email(s) under the name: copy, share, add email and Ask for email.

/** Number(s) then email(s) under the name: tap to call / email, small Copy and Share buttons. */
internal fun OmnioContactActivity.showContactInfo(contact: Contact, name: String) {
    val holder = binding.contactInfoHolder
    holder.removeAllViews()
    val showTypes = contact.phoneNumbers.size > 1
    contact.phoneNumbers.forEach { number ->
        val label = if (showTypes) "${number.value}  ·  ${getPhoneTypeLabel(number.type, number.label)}" else number.value
        addInfoLine(holder, label, number.value) { startCallWithConfirmationCheck(number.value, name) }
    }
    contact.emails.forEach { email ->
        val address = email.value.trim().lowercase()
        addInfoLine(holder, address, address) { sendEmailIntent(address) }
    }
    if (contact.emails.isEmpty()) {
        holder.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@showContactInfo).apply {
                text = "+ " + getString(R.string.omnio_add_email)
                setTextColor(OmnioColors.CONTACTS)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { askEmail(contact) }
            })
            // Omnio One: text them asking for their email
            val number = (contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull())?.value
            if (number != null) {
                addView(TextView(this@showContactInfo).apply {
                    text = getString(R.string.omnio_ask)
                    setTextColor(OmnioColors.ON_ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(14), dp(5), dp(14), dp(5))
                    background = getDrawable(R.drawable.omnio_round_bg)
                    backgroundTintList = ColorStateList.valueOf(OmnioColors.MESSAGES)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(4)
                    }
                    setOnClickListener { askForEmailByText(contact, number) }
                })
            }
        })
    }
}

internal fun OmnioContactActivity.addInfoLine(holder: LinearLayout, label: String, value: String, onClick: () -> Unit) {
    val textColor = getProperTextColor()
    val iconColor = OmnioColors.TEXT_MUTED
    holder.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@addInfoLine).apply {
            text = label
            setTextColor(textColor)
            alpha = 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(4), dp(6), dp(4))
            setOnClickListener { onClick() }
        })
        listOf(
            org.fossify.commons.R.drawable.ic_copy_vector to { copyToClipboard(value) },
            org.fossify.commons.R.drawable.ic_share_vector to { shareText(value) }
        ).forEach { (icon, action) ->
            addView(ImageView(this@addInfoLine).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(iconColor)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { action() }
            })
        }
    })
}

/** Sends them a text asking for their email; their reply gets offered for saving in Messages. */
internal fun OmnioContactActivity.askForEmailByText(contact: Contact, number: String) {
    val firstName = contact.getNameToDisplay().trim().substringBefore(" ")
    OmnioSheet.input(
        this,
        title = getString(R.string.omnio_ask_email_title, firstName),
        message = getString(R.string.omnio_ask_email_help),
        initial = getString(R.string.omnio_ask_email_text, firstName),
        positive = getString(R.string.omnio_send),
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        multiLine = true
    ) { typed ->
        val text = typed.trim()
        if (text.isEmpty()) return@input
        try {
            sendOmnioText(text, number)
            org.fossify.messages.helpers.OmnioEmail.markAsked(this, number)
            toast(getString(R.string.omnio_ask_sent, firstName))
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

internal fun OmnioContactActivity.sendOmnioText(text: String, number: String) {
    sendMessageCompat(text, listOf(number), null, emptyList())
}

internal fun OmnioContactActivity.shareText(text: String) {
    try {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, getString(org.fossify.commons.R.string.share)))
    } catch (e: Exception) {
        toast(org.fossify.commons.R.string.no_app_found)
    }
}

/** Adds an email address to the contact without leaving Omnio. */
internal fun OmnioContactActivity.askEmail(contact: Contact) {
    OmnioSheet.input(
        this,
        title = getString(R.string.omnio_add_email),
        hint = "name@example.com",
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    ) { typed ->
        val email = typed.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(R.string.omnio_invalid_email)
            return@input
        }
        ensureBackgroundThread {
            val ok = OmnioSocials.addData(
                this, contact.contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS to email,
                ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_HOME
            )
            val reloaded = ContactsHelper(this).getContactWithId(contact.rawId, false)
            runOnUiThread {
                if (!ok) toast(org.fossify.commons.R.string.unknown_error_occurred)
                if (reloaded != null) {
                    this.contact = reloaded
                    showContact(reloaded)
                }
            }
        }
    }
}
