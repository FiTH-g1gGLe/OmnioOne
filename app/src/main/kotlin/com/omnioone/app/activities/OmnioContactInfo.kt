package com.omnioone.app.activities

import com.omnioone.commons.helpers.OmnioColors
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
import com.omnioone.commons.extensions.applyColorFilter
import com.omnioone.commons.extensions.beGone
import com.omnioone.commons.extensions.getProperBackgroundColor
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.commons.extensions.launchSendSMSIntent
import com.omnioone.commons.extensions.sendEmailIntent
import com.omnioone.commons.extensions.showErrorToast
import com.omnioone.commons.extensions.hasPermission
import com.omnioone.commons.extensions.copyToClipboard
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.commons.helpers.ContactsHelper
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.commons.models.contacts.Contact
import com.omnioone.app.R
import com.omnioone.app.databinding.ActivityOmnioContactBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.getAvailableSIMCardLabels
import com.omnioone.app.extensions.openContactInSystemApp
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.helpers.RecentsHelper
import com.omnioone.commons.extensions.beVisibleIf
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.app.helpers.OmnioSocials
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.messages.messaging.sendMessageCompat
import com.omnioone.commons.extensions.beVisible
import com.omnioone.commons.extensions.getFormattedDuration

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
            com.omnioone.commons.R.drawable.ic_copy_vector to { copyToClipboard(value) },
            com.omnioone.commons.R.drawable.ic_share_vector to { shareText(value) }
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
            com.omnioone.messages.helpers.OmnioEmail.markAsked(this, number)
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
        }, getString(com.omnioone.commons.R.string.share)))
    } catch (e: Exception) {
        toast(com.omnioone.commons.R.string.no_app_found)
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
                if (!ok) toast(com.omnioone.commons.R.string.unknown_error_occurred)
                if (reloaded != null) {
                    this.contact = reloaded
                    showContact(reloaded)
                }
            }
        }
    }
}
