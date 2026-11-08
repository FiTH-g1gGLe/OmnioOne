package org.fossify.phone.activities

import android.content.res.ColorStateList
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
import org.fossify.commons.extensions.launchCallIntent
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.sendEmailIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityOmnioContactBinding
import org.fossify.phone.extensions.openContactInSystemApp

/**
 * Omnio One's own contact page, so tapping a contact no longer jumps to another app.
 * First version: photo, name, Call / Text / Edit, numbers, emails and birthday.
 */
class OmnioContactActivity : SimpleActivity() {

    companion object {
        const val EXTRA_CONTACT_ID = "omnio_contact_id"
        private const val CALLS_BLUE = 0xFF4F7CFF.toInt()
        private const val MESSAGES_GREEN = 0xFF22C55E.toInt()
    }

    private val binding by viewBinding(ActivityOmnioContactBinding::inflate)
    private var contact: Contact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.contactHolder), padBottomSystem = listOf(binding.contactHolder))

        binding.contactBack.setOnClickListener { finish() }
        val id = intent.getIntExtra(EXTRA_CONTACT_ID, 0)
        ensureBackgroundThread {
            val loaded = ContactsHelper(this).getContactWithId(id, false)
            runOnUiThread {
                if (loaded == null) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finish()
                } else {
                    contact = loaded
                    showContact(loaded)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val textColor = getProperTextColor()
        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.contactName.setTextColor(textColor)
        binding.contactSubtitle.setTextColor(textColor)
        binding.contactNumbersLabel.setTextColor(textColor)
        binding.contactDetailsLabel.setTextColor(textColor)
        binding.contactBack.applyColorFilter(textColor)
        binding.contactFavourite.applyColorFilter(0xFFFFC85A.toInt())
        binding.contactCall.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFF0F1115.toInt())
        binding.contactText.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFF0F1115.toInt())
        binding.contactEdit.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFFECEFF4.toInt())
    }

    private fun showContact(contact: Contact) {
        val name = contact.getNameToDisplay()
        binding.contactName.text = name
        SimpleContactsHelper(this).loadContactImage(contact.photoUri, binding.contactPhoto, name)

        binding.contactSubtitle.text = when {
            contact.organization.company.isNotEmpty() -> contact.organization.company
            contact.phoneNumbers.size > 1 -> getString(R.string.omnio_number_count, contact.phoneNumbers.size)
            else -> contact.phoneNumbers.firstOrNull()?.value ?: ""
        }

        binding.contactFavourite.setImageResource(
            if (contact.starred == 1) org.fossify.commons.R.drawable.ic_star_vector
            else org.fossify.commons.R.drawable.ic_star_outline_vector
        )

        val primary = contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull()
        binding.contactCall.setOnClickListener { primary?.let { launchCallIntent(it.value) } }
        binding.contactText.setOnClickListener { primary?.let { launchSendSMSIntent(it.value) } }
        binding.contactEdit.setOnClickListener { openContactInSystemApp(contact) }

        binding.contactNumbersHolder.removeAllViews()
        contact.phoneNumbers.forEachIndexed { index, number ->
            addRow(
                holder = binding.contactNumbersHolder,
                title = number.value,
                subtitle = getPhoneTypeLabel(number.type, number.label),
                showDivider = index > 0,
                actionIcon = org.fossify.commons.R.drawable.ic_phone_vector,
                actionColor = CALLS_BLUE,
                onAction = { launchCallIntent(number.value) },
                secondIcon = org.fossify.commons.R.drawable.ic_sms_vector,
                secondColor = MESSAGES_GREEN,
                onSecond = { launchSendSMSIntent(number.value) }
            )
        }
        if (contact.phoneNumbers.isEmpty()) {
            binding.contactNumbersLabel.beGone()
            binding.contactNumbersHolder.beGone()
        }

        binding.contactDetailsHolder.removeAllViews()
        var detailRows = 0
        contact.emails.forEach { email ->
            addRow(binding.contactDetailsHolder, email.value, getString(R.string.omnio_email), detailRows > 0) { sendEmailIntent(email.value) }
            detailRows++
        }
        contact.birthdays.forEach { birthday ->
            addRow(binding.contactDetailsHolder, birthday, getString(R.string.omnio_birthday), detailRows > 0, null)
            detailRows++
        }
        if (detailRows == 0) {
            binding.contactDetailsLabel.beGone()
            binding.contactDetailsHolder.beGone()
        }
    }

    private fun getPhoneTypeLabel(type: Int, label: String): String {
        return if (type == Phone.TYPE_CUSTOM && label.isNotEmpty()) {
            label
        } else {
            Phone.getTypeLabel(resources, type, label).toString()
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun addRow(
        holder: LinearLayout,
        title: String,
        subtitle: String,
        showDivider: Boolean,
        onClick: (() -> Unit)?
    ) = addRow(holder, title, subtitle, showDivider, null, 0, onClick, null, 0, null)

    private fun addRow(
        holder: LinearLayout,
        title: String,
        subtitle: String,
        showDivider: Boolean,
        actionIcon: Int?,
        actionColor: Int,
        onAction: (() -> Unit)?,
        secondIcon: Int?,
        secondColor: Int,
        onSecond: (() -> Unit)?
    ) {
        val textColor = getProperTextColor()
        if (showDivider) {
            holder.addView(View(this).apply {
                setBackgroundColor(0x22FFFFFF)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(10), 0, dp(10))
            if (onAction != null) setOnClickListener { onAction() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(textColor)
            alpha = 0.65f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        row.addView(texts)
        listOfNotNull(
            actionIcon?.let { Triple(it, actionColor, onAction) },
            secondIcon?.let { Triple(it, secondColor, onSecond) }
        ).forEach { (icon, color, action) ->
            row.addView(ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(color)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { action?.invoke() }
            })
        }
        holder.addView(row)
    }
}
