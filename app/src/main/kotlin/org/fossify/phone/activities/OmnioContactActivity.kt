package org.fossify.phone.activities

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
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getFormattedDuration

/**
 * Omnio One's own contact page, so tapping a contact no longer jumps to another app.
 * Photo, name, favourite star, Call / Text / Edit, chat-app buttons, numbers (with SIM),
 * recent calls, emails, birthday and notes.
 */
class OmnioContactActivity : SimpleActivity() {

    companion object {
        const val EXTRA_CONTACT_ID = "omnio_contact_id"
        private const val CALLS_BLUE = 0xFF4F7CFF.toInt()
        private const val MESSAGES_GREEN = 0xFF22C55E.toInt()
        private const val MAX_RECENT = 6
    }

    private val binding by viewBinding(ActivityOmnioContactBinding::inflate)
    private var contact: Contact? = null
    private val favouriteWriter = java.util.concurrent.Executors.newSingleThreadExecutor()

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
        binding.contactRecentLabel.setTextColor(textColor)
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

        binding.contactFavourite.setOnClickListener { toggleFavourite() }

        val primary = contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull()
        binding.contactCall.setOnClickListener { primary?.let { startCallWithConfirmationCheck(it.value, name) } }
        binding.contactText.setOnClickListener { primary?.let { launchSendSMSIntent(it.value) } }
        binding.contactEdit.setOnClickListener { openContactInSystemApp(contact) }

        binding.contactNumbersHolder.removeAllViews()
        contact.phoneNumbers.forEachIndexed { index, number ->
            addRow(
                holder = binding.contactNumbersHolder,
                title = number.value,
                subtitle = getNumberSubtitle(number.value, number.type, number.label),
                showDivider = index > 0,
                actionIcon = org.fossify.commons.R.drawable.ic_phone_vector,
                actionColor = CALLS_BLUE,
                onAction = { startCallWithConfirmationCheck(number.value, name) },
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
        if (contact.notes.isNotBlank()) {
            addRow(binding.contactDetailsHolder, contact.notes, getString(R.string.omnio_notes), detailRows > 0, null)
            detailRows++
        }
        if (detailRows == 0) {
            binding.contactDetailsLabel.beGone()
            binding.contactDetailsHolder.beGone()
        }

        loadAppButtons(contact)
        loadRecentCalls(contact)
    }

    private var firstResume = true

    override fun onPostResume() {
        super.onPostResume()
        if (firstResume) {
            firstResume = false
            return
        }
        contact?.let { loadRecentCalls(it) }
    }

    private fun toggleFavourite() {
        val current = contact ?: return
        val makeFavourite = current.starred != 1
        current.starred = if (makeFavourite) 1 else 0
        toast(if (makeFavourite) R.string.omnio_added_favourite else R.string.omnio_removed_favourite)
        // One write at a time, in order, so quick add/remove/add taps can't land out of order
        favouriteWriter.execute {
            try {
                if (current.isPrivate()) {
                    org.fossify.commons.helpers.LocalContactsHelper(this).toggleFavorites(arrayOf(current.id), makeFavourite)
                } else {
                    val values = android.content.ContentValues().apply {
                        put(ContactsContract.Contacts.STARRED, if (makeFavourite) 1 else 0)
                    }
                    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, current.contactId.toString())
                    contentResolver.update(uri, values, null, null)
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorToast(e) }
            }
        }
        binding.contactFavourite.setImageResource(
            if (current.starred == 1) org.fossify.commons.R.drawable.ic_star_vector
            else org.fossify.commons.R.drawable.ic_star_outline_vector
        )
    }

    /** Number type, plus "Always SIM 2" when a SIM has been remembered for this number. */
    private fun getNumberSubtitle(number: String, type: Int, label: String): String {
        val typeLabel = getPhoneTypeLabel(type, label)
        val handle = config.getCustomSIM(number) ?: return typeLabel
        val sim = getAvailableSIMCardLabels().firstOrNull { it.handle == handle } ?: return typeLabel
        return "$typeLabel · " + getString(R.string.omnio_always_sim, sim.label)
    }

    private data class AppAction(val label: Int, val icon: Int, val tint: Int, val background: Int, val dataId: Long, val mimeType: String, val pkg: String)

    /** Shows WhatsApp, WhatsApp video, Telegram and Signal buttons for contacts that use them. */
    private fun loadAppButtons(contact: Contact) {
        ensureBackgroundThread {
            val wanted = mapOf(
                "vnd.android.cursor.item/vnd.com.whatsapp.profile" to Triple(R.string.omnio_whatsapp, R.drawable.ic_omnio_chat, "com.whatsapp"),
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call" to Triple(R.string.omnio_whatsapp_video, R.drawable.ic_omnio_video, "com.whatsapp"),
                "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile" to Triple(R.string.omnio_telegram, R.drawable.ic_omnio_chat, "org.telegram.messenger"),
                "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact" to Triple(R.string.omnio_signal, R.drawable.ic_omnio_chat, "org.thoughtcrime.securesms")
            )
            val found = LinkedHashMap<String, AppAction>()
            try {
                val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (${wanted.keys.joinToString { "?" }})"
                val args = arrayOf(contact.contactId.toString()) + wanted.keys.toTypedArray()
                contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
                    selection, args, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val mime = cursor.getString(1)
                        if (found.containsKey(mime)) continue
                        val (label, icon, pkg) = wanted[mime] ?: continue
                        val isWhatsApp = pkg == "com.whatsapp"
                        val tint = if (isWhatsApp) 0xFF5DD39E.toInt() else 0xFF6CBCF2.toInt()
                        val bg = if (isWhatsApp) 0xFF163028.toInt() else 0xFF15283A.toInt()
                        found[mime] = AppAction(label, icon, tint, bg, id, mime, pkg)
                    }
                }
            } catch (ignored: Exception) {
            }
            val ordered = wanted.keys.mapNotNull { found[it] }
            runOnUiThread { showAppButtons(ordered) }
        }
    }

    private fun showAppButtons(actions: List<AppAction>) {
        val holder = binding.contactAppsHolder
        holder.removeAllViews()
        if (actions.isEmpty()) {
            holder.beGone()
            return
        }
        holder.beVisible()
        val stacked = actions.size >= 3
        actions.forEachIndexed { index, action ->
            holder.addView(TextView(this).apply {
                text = getString(action.label)
                setTextColor(action.tint)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val icon = getDrawable(action.icon)?.mutate()
                icon?.setTint(action.tint)
                if (stacked) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
                    compoundDrawablePadding = dp(2)
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                } else {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                    compoundDrawablePadding = dp(6)
                    setPadding(dp(10), 0, dp(10), 0)
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(action.background)
                layoutParams = LinearLayout.LayoutParams(0, dp(if (stacked) 64 else 48), 1f).apply {
                    if (index > 0) marginStart = dp(8)
                }
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse("content://com.android.contacts/data/${action.dataId}"), action.mimeType)
                            setPackage(action.pkg)
                        })
                    } catch (e: Exception) {
                        toast(org.fossify.commons.R.string.no_app_found)
                    }
                }
            })
        }
    }

    /** Last few calls with this contact, newest first. */
    /** One line in the contact's Recent list: a call or a text. */
    private data class ActivityItem(
        val time: Long,
        val title: String,
        val subtitle: String,
        val icon: Int,
        val color: Int,
        val onClick: (() -> Unit)?
    )

    /** Recent calls and texts with this contact, newest first. */
    private fun loadRecentCalls(contact: Contact) {
        val numbers = contact.phoneNumbers.map { it.normalizedNumber.ifEmpty { it.value } }
        if (numbers.isEmpty()) return
        RecentsHelper(this).getRecentCalls(queryLimit = 300) { calls ->
            val now = System.currentTimeMillis()
            val items = ArrayList<ActivityItem>()
            calls.filter { call -> numbers.any { PhoneNumberUtils.compare(it, call.phoneNumber) } }
                .sortedByDescending { it.startTS }
                .take(MAX_RECENT)
                .forEach { call ->
                    val (title, icon, color) = when (call.type) {
                        Calls.MISSED_TYPE -> Triple(R.string.omnio_missed_call, R.drawable.ic_call_missed_vector, 0xFFFF8A8A.toInt())
                        Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE -> Triple(R.string.omnio_declined_call, R.drawable.ic_call_missed_vector, 0xFFFF8A8A.toInt())
                        Calls.OUTGOING_TYPE -> Triple(R.string.omnio_outgoing_call, R.drawable.ic_call_made_vector, CALLS_BLUE)
                        else -> Triple(R.string.omnio_incoming_call, R.drawable.ic_call_received_vector, MESSAGES_GREEN)
                    }
                    val whenText = DateUtils.getRelativeTimeSpanString(call.startTS, now, DateUtils.MINUTE_IN_MILLIS).toString()
                    val subtitle = if (call.duration > 0) "$whenText · ${call.duration.getFormattedDuration()}" else whenText
                    items.add(ActivityItem(call.startTS, getString(title), subtitle, icon, color, null))
                }
            items.addAll(loadRecentTexts(numbers, now))
            val newest = items.sortedByDescending { it.time }.take(MAX_RECENT)
            runOnUiThread { showRecentActivity(newest) }
        }
    }

    /** Last few SMS with any of these numbers (needs Omnio One to be allowed to read texts). */
    private fun loadRecentTexts(numbers: List<String>, now: Long): List<ActivityItem> {
        if (!hasPermission(org.fossify.commons.helpers.PERMISSION_READ_SMS)) return emptyList()
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
                while (cursor.moveToNext() && found.size < MAX_RECENT && scanned++ < 1500) {
                    val address = cursor.getString(0) ?: continue
                    if (numbers.none { PhoneNumberUtils.compare(it, address) }) continue
                    val body = (cursor.getString(1) ?: "").replace(Regex("\\s+"), " ").trim()
                    val date = cursor.getLong(2)
                    val received = cursor.getInt(3) == android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
                    val whenText = DateUtils.getRelativeTimeSpanString(date, now, DateUtils.MINUTE_IN_MILLIS).toString()
                    val label = getString(if (received) R.string.omnio_text_received else R.string.omnio_text_sent)
                    val snippet = if (body.length > 60) body.take(60).trimEnd() + "…" else body
                    found.add(ActivityItem(date, snippet.ifEmpty { label }, "$label · $whenText", R.drawable.ic_omnio_chat, MESSAGES_GREEN) {
                        launchSendSMSIntent(address)
                    })
                }
            }
        } catch (ignored: Exception) {
        }
        return found
    }

    private fun showRecentActivity(items: List<ActivityItem>) {
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
