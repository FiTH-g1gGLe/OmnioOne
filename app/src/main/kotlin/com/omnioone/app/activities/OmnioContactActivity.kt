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
import com.omnioone.commons.extensions.beVisibleIf
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.app.helpers.OmnioSocials
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.commons.extensions.beVisible
import com.omnioone.commons.extensions.getFormattedDuration

/**
 * Omnio One's own contact page, so tapping a contact no longer jumps to another app.
 * Photo, name, favourite star, Call / Text / Edit, chat-app buttons, numbers (with SIM),
 * recent calls, emails, birthday and notes.
 */
class OmnioContactActivity : SimpleActivity() {

    companion object {
        const val EXTRA_CONTACT_ID = "omnio_contact_id"
        const val EXTRA_SHARED_LINK = "omnio_shared_link"
        internal const val CALLS_BLUE = OmnioColors.CALLS
        internal const val MESSAGES_GREEN = OmnioColors.MESSAGES
        internal const val MAX_RECENT = 5
    }

    internal val binding by viewBinding(ActivityOmnioContactBinding::inflate)
    internal var contact: Contact? = null

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
                    toast(com.omnioone.commons.R.string.unknown_error_occurred)
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
        // back from the accessibility settings: carry on to the search
        findAfterSettings?.let { network ->
            findAfterSettings = null
            contact?.let { current -> binding.root.postDelayed({ openFinding(current, network) }, 400) }
        }
        val textColor = getProperTextColor()
        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.contactName.setTextColor(textColor)
        binding.contactSubtitle.setTextColor(textColor)
        binding.contactNumbersLabel.setTextColor(textColor)
        binding.contactDetailsLabel.setTextColor(textColor)
        binding.contactRecentLabel.setTextColor(textColor)
        binding.contactBack.applyColorFilter(textColor)
        binding.contactFavourite.applyColorFilter(OmnioColors.FAVOURITES)
        binding.contactCall.compoundDrawablesRelative[1]?.mutate()?.setTint(OmnioColors.ON_ACCENT)
        binding.contactText.compoundDrawablesRelative[1]?.mutate()?.setTint(OmnioColors.ON_ACCENT)
        binding.contactEdit.applyColorFilter(textColor)
        binding.contactAppsLabel.setTextColor(textColor)
        binding.contactVideo.compoundDrawablesRelative[1]?.mutate()?.setTint(OmnioColors.MESSAGES_SOFT)
        binding.root.postDelayed({ if (!isFinishing) offerCapturedPhoto() }, 700)
    }

    internal fun showContact(contact: Contact) {
        val name = contact.getNameToDisplay()
        binding.contactName.text = name
        SimpleContactsHelper(this).loadContactImage(contact.photoUri, binding.contactPhoto, name)

        binding.contactSubtitle.text = contact.organization.company
        binding.contactSubtitle.beVisibleIf(contact.organization.company.isNotEmpty())

        if (!contact.isPrivate() && com.omnioone.app.helpers.OmnioFavourites.isLoaded) {
            contact.starred = if (com.omnioone.app.helpers.OmnioFavourites.isFavourite(contact.contactId)) 1 else 0
        }
        showStar(contact.starred == 1)

        binding.contactFavourite.setOnClickListener { toggleFavourite() }

        val primary = contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull()
        binding.contactCall.setOnClickListener { primary?.let { startCallWithConfirmationCheck(it.value, name) } }
        binding.contactText.setOnClickListener { primary?.let { launchSendSMSIntent(it.value) } }
        binding.contactEdit.setOnClickListener {
            if (contact.isPrivate()) {
                openContactInSystemApp(contact)
            } else {
                startActivity(Intent(this, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_CONTACT_ID, contact.rawId))
            }
        }

        // Omnio One: numbers and emails live under the name (no separate card)
        binding.contactNumbersLabel.beGone()
        binding.contactNumbersHolder.beGone()
        showContactInfo(contact, name)

        binding.contactDetailsHolder.removeAllViews()
        var detailRows = 0
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

        loadApps(contact)
        loadRecentCalls(contact)

        // opened from a link shared to Omnio One, or back from "Find on …" after being closed
        val shared = intent.getStringExtra(EXTRA_SHARED_LINK)
        if (shared != null) {
            intent.removeExtra(EXTRA_SHARED_LINK)
            val wanted = OmnioSocials.getPendingLink(this)?.networkKey
            // wait for the apps card so we know what's already linked
            binding.root.postDelayed({ handleIncomingLink(contact, wanted, shared) }, 600)
        } else {
            binding.root.post { checkCopiedLink() }
        }
    }

    internal var firstResume = true

    override fun onPostResume() {
        super.onPostResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // coming back to the page (e.g. after editing): reload everything; close if it was deleted
        contact?.let { current ->
            ensureBackgroundThread {
                val fresh = ContactsHelper(this).getContactWithId(current.rawId, current.isPrivate())
                runOnUiThread {
                    if (fresh == null) {
                        finish()
                    } else {
                        this.contact = fresh
                        showContact(fresh)
                    }
                }
            }
        }
    }

    internal fun toggleFavourite() {
        val current = contact ?: return
        val makeFavourite: Boolean
        if (current.isPrivate()) {
            makeFavourite = current.starred != 1
            ensureBackgroundThread {
                com.omnioone.commons.helpers.LocalContactsHelper(this).toggleFavorites(arrayOf(current.id), makeFavourite)
                com.omnioone.app.helpers.OmnioFavourites.notifyChanged()
            }
        } else {
            // do exactly what the star shows, whatever has or hasn't loaded yet
            makeFavourite = current.starred != 1
            com.omnioone.app.helpers.OmnioFavourites.set(this, current.contactId, makeFavourite)
        }
        current.starred = if (makeFavourite) 1 else 0
        toast(if (makeFavourite) R.string.omnio_added_favourite else R.string.omnio_removed_favourite)
        showStar(makeFavourite)
    }

    internal fun showStar(favourite: Boolean) {
        binding.contactFavourite.setImageResource(
            if (favourite) com.omnioone.commons.R.drawable.ic_star_vector else com.omnioone.commons.R.drawable.ic_star_outline_vector
        )
    }

    /** Keeps the star right if favourites change elsewhere while this page is open. */
    internal val favouritesListener: () -> Unit = {
        contact?.takeIf { !it.isPrivate() }?.let { current ->
            val favourite = com.omnioone.app.helpers.OmnioFavourites.isFavourite(current.contactId)
            current.starred = if (favourite) 1 else 0
            showStar(favourite)
        }
    }

    override fun onStart() {
        super.onStart()
        com.omnioone.app.helpers.OmnioFavourites.addListener(favouritesListener)
        com.omnioone.app.helpers.OmnioFavourites.reload(this)
    }

    override fun onStop() {
        com.omnioone.app.helpers.OmnioFavourites.removeListener(favouritesListener)
        super.onStop()
    }

    /** Number type, plus "Always SIM 2" when a SIM has been remembered for this number. */
    internal fun getNumberSubtitle(number: String, type: Int, label: String): String {
        val typeLabel = getPhoneTypeLabel(type, label)
        val handle = config.getCustomSIM(number) ?: return typeLabel
        val sim = getAvailableSIMCardLabels().firstOrNull { it.handle == handle } ?: return typeLabel
        return "$typeLabel · " + getString(R.string.omnio_always_sim, sim.label)
    }

    // ---- Omnio One: chat apps + social profiles card ----

    internal var currentSocials: List<OmnioSocials.FoundSocial> = emptyList()

    /** Network to search once they're back from switching Link helper on. */
    internal var findAfterSettings: OmnioSocials.Network? = null

    internal var linkDialogShowing = false

    /** Back from "Find on …": if a profile link was copied, offer to add it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkCopiedLink()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // a link shared to Omnio One while finding this contact
        val url = intent.getStringExtra(EXTRA_SHARED_LINK) ?: return
        val current = contact ?: return
        handleIncomingLink(current, OmnioSocials.getPendingLink(this)?.networkKey, url)
    }

    internal fun getPhoneTypeLabel(type: Int, label: String): String {
        return if (type == Phone.TYPE_CUSTOM && label.isNotEmpty()) {
            label
        } else {
            Phone.getTypeLabel(resources, type, label).toString()
        }
    }

    internal fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    internal fun addRow(
        holder: LinearLayout,
        title: String,
        subtitle: String,
        showDivider: Boolean,
        onClick: (() -> Unit)?
    ) = addRow(holder, title, subtitle, showDivider, null, 0, onClick, null, 0, null)

    internal fun addRow(
        holder: LinearLayout,
        title: String,
        subtitle: String,
        showDivider: Boolean,
        actionIcon: Int?,
        actionColor: Int,
        onAction: (() -> Unit)?,
        secondIcon: Int?,
        secondColor: Int,
        onSecond: (() -> Unit)?,
        onRowClick: (() -> Unit)? = onAction
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
            if (onRowClick != null) setOnClickListener { onRowClick() }
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
