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
        const val EXTRA_SHARED_LINK = "omnio_shared_link"
        private const val CALLS_BLUE = 0xFF4F7CFF.toInt()
        private const val MESSAGES_GREEN = 0xFF22C55E.toInt()
        private const val MAX_RECENT = 10
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
        binding.contactFavourite.applyColorFilter(0xFFFFC85A.toInt())
        binding.contactCall.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFF0F1115.toInt())
        binding.contactText.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFF0F1115.toInt())
        binding.contactEdit.applyColorFilter(textColor)
        binding.contactAppsLabel.setTextColor(textColor)
        binding.contactVideo.compoundDrawablesRelative[1]?.mutate()?.setTint(0xFF5DD39E.toInt())
    }

    private fun showContact(contact: Contact) {
        val name = contact.getNameToDisplay()
        binding.contactName.text = name
        SimpleContactsHelper(this).loadContactImage(contact.photoUri, binding.contactPhoto, name)

        binding.contactSubtitle.text = contact.organization.company
        binding.contactSubtitle.beVisibleIf(contact.organization.company.isNotEmpty())

        binding.contactFavourite.setImageResource(
            if (contact.starred == 1) org.fossify.commons.R.drawable.ic_star_vector
            else org.fossify.commons.R.drawable.ic_star_outline_vector
        )

        binding.contactFavourite.setOnClickListener { toggleFavourite() }

        val primary = contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull()
        binding.contactCall.setOnClickListener { primary?.let { startCallWithConfirmationCheck(it.value, name) } }
        binding.contactText.setOnClickListener { primary?.let { launchSendSMSIntent(it.value) } }
        binding.contactEdit.setOnClickListener { openContactInSystemApp(contact) }

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

    // ---- Omnio One: chat apps + social profiles card ----

    private fun loadApps(contact: Contact) {
        ensureBackgroundThread {
            val chats = OmnioSocials.findChats(this, contact.contactId)
            val socials = OmnioSocials.findSocials(this, contact.contactId)
            runOnUiThread { showApps(contact, chats, socials) }
        }
    }

    private var currentSocials: List<OmnioSocials.FoundSocial> = emptyList()

    private fun showApps(contact: Contact, chats: List<OmnioSocials.FoundChat>, socials: List<OmnioSocials.FoundSocial>) {
        currentSocials = socials
        // Big Video button: WhatsApp video first, then the other apps that can video call
        val video = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.tachyon")
            .firstNotNullOfOrNull { pkg -> chats.firstOrNull { it.app.pkg == pkg && it.action.kind == OmnioSocials.Kind.VIDEO } }
        binding.contactVideo.apply {
            beVisibleIf(video != null)
            setOnClickListener { video?.let { openChat(it) } }
        }

        val holder = binding.contactAppsHolder
        holder.removeAllViews()
        var rows = 0
        chats.groupBy { it.app }.forEach { (app, found) ->
            val actions = found.sortedBy { it.action.kind.ordinal }
            val primary = actions.first()
            addAppRow(
                holder = holder,
                showDivider = rows++ > 0,
                icon = appIcon(app.pkg, app.name, app.color),
                title = app.name,
                subtitle = null,
                color = app.color,
                onClick = { openChat(primary) },
                onLongClick = null,
                actions = actions.map { chat ->
                    val iconRes = when (chat.action.kind) {
                        OmnioSocials.Kind.CHAT -> R.drawable.ic_omnio_chat
                        OmnioSocials.Kind.VOICE -> org.fossify.commons.R.drawable.ic_phone_vector
                        OmnioSocials.Kind.VIDEO -> R.drawable.ic_omnio_video
                    }
                    iconRes to { openChat(chat) }
                }
            )
        }
        socials.forEach { social ->
            val network = social.network
            addAppRow(
                holder = holder,
                showDivider = rows++ > 0,
                icon = appIcon(network.pkg, network.name, network.color),
                title = network.name,
                subtitle = network.displayHandle(social.url).ifEmpty { getString(R.string.omnio_profile_linked) },
                color = network.color,
                onClick = { openSocial(social) },
                onLongClick = { confirmRemoveSocial(contact, social) },
                actions = if (network.key == "messenger") {
                    // like WhatsApp: message, voice call, video call
                    listOf(
                        R.drawable.ic_omnio_chat to { openSocial(social) },
                        org.fossify.commons.R.drawable.ic_phone_vector to { messengerCall(social, OmnioSocials.AutoTap.VOICE) },
                        R.drawable.ic_omnio_video to { messengerCall(social, OmnioSocials.AutoTap.VIDEO) },
                    )
                } else {
                    listOf(org.fossify.commons.R.drawable.ic_arrow_right_vector to { openSocial(social) })
                }
            )
        }
        // "+ Add social" row
        addAppRow(
            holder = holder,
            showDivider = rows > 0,
            icon = getDrawable(org.fossify.commons.R.drawable.ic_plus_vector)?.mutate()?.apply { setTint(0xFFFF8A3D.toInt()) },
            title = getString(R.string.omnio_add_social),
            subtitle = null,
            color = 0xFFFF8A3D.toInt(),
            onClick = { pickNetworkToAdd(contact) },
            onLongClick = null,
            actions = emptyList()
        )
    }

    /** The app's real icon from the phone, or a coloured letter if it isn't installed. */
    private fun appIcon(pkg: String, name: String, color: Int): android.graphics.drawable.Drawable? {
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            val size = dp(36)
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, android.graphics.Paint().apply { isAntiAlias = true; this.color = color })
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.6) 0xFF0F1115.toInt() else 0xFFFFFFFF.toInt()
                textSize = size * 0.48f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText(name.take(1), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun addAppRow(
        holder: LinearLayout,
        showDivider: Boolean,
        icon: android.graphics.drawable.Drawable?,
        title: String,
        subtitle: String?,
        color: Int,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        actions: List<Pair<Int, () -> Unit>>
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
            minimumHeight = dp(60)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@OmnioContactActivity).apply {
                text = title
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (!subtitle.isNullOrEmpty()) {
                addView(TextView(this@OmnioContactActivity).apply {
                    text = subtitle
                    setTextColor(textColor)
                    alpha = 0.65f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
        })
        // light colours (Snapchat yellow, X white) are fine on the dark card; very dark ones get lifted
        val actionColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.08) 0xFFECEFF4.toInt() else color
        actions.forEach { (iconRes, action) ->
            row.addView(ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(actionColor)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { action() }
            })
        }
        holder.addView(row)
    }

    private fun openChat(chat: OmnioSocials.FoundChat) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.contacts/data/${chat.dataId}"), chat.action.mimeType)
                setPackage(chat.app.pkg)
            })
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.no_app_found)
        }
    }

    private fun openSocial(social: OmnioSocials.FoundSocial) {
        if (social.network.key == "discord" && OmnioSocials.discordId(social.url) == null) {
            // an old username-only link: Discord can't open those
            toast(R.string.omnio_discord_need_id, android.widget.Toast.LENGTH_LONG)
            tryStart(packageManager.getLaunchIntentForPackage(social.network.pkg))
            return
        }
        val uri = Uri.parse(if (social.url.contains("://")) social.url else "https://${social.url}")
        // the app if it's installed, otherwise the browser
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage(social.network.pkg)
        try {
            startActivity(inApp)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                toast(org.fossify.commons.R.string.no_app_found)
            }
        }
    }

    /** Opens the Messenger chat; with Link helper on, it then taps Messenger's call / video button. */
    private fun messengerCall(social: OmnioSocials.FoundSocial, tap: OmnioSocials.AutoTap) {
        val helperOn = org.fossify.phone.services.OmnioLinkHelperService.isEnabled(this)
        if (helperOn) {
            OmnioSocials.setAutoTap(this, social.network.pkg, tap)
        } else {
            toast(R.string.omnio_messenger_call_manual, android.widget.Toast.LENGTH_LONG)
        }
        openSocial(social)
    }

    private fun pickNetworkToAdd(contact: Contact) {
        val items = ArrayList(OmnioSocials.networks.mapIndexed { index, network -> org.fossify.commons.models.RadioItem(index, network.name) })
        org.fossify.commons.dialogs.RadioGroupDialog(this, items, titleId = R.string.omnio_add_social) { picked ->
            val network = OmnioSocials.networks.getOrNull(picked as Int) ?: return@RadioGroupDialog
            val facebook = currentSocials.firstOrNull { it.network.key == "facebook" }
            if (network.key == "messenger" && facebook != null) {
                // build Messenger straight from the Facebook profile that's already linked
                offerMessengerFromFacebook(contact, facebook.url) { askUsername(contact, network) }
            } else {
                askUsername(contact, network)
            }
        }
    }

    private fun askUsername(contact: Contact, network: OmnioSocials.Network) {
        val input = android.widget.EditText(this).apply {
            hint = getString(if (network.key == "discord") R.string.omnio_discord_hint else R.string.omnio_username_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getProperTextColor())
        }
        // fill in something useful that's already been copied
        clipboardText()?.trim()?.let { clip ->
            val url = OmnioSocials.firstUrl(clip)
            when {
                url != null && network.matches(url) -> input.setText(url)
                network.key == "discord" && OmnioSocials.discordUrl(clip) != null -> input.setText(clip)
            }
        }
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                if (network.key == "discord") {
                    saveDiscord(contact, input.text.toString(), ask = false)
                    return@setPositiveButton
                }
                val url = OmnioSocials.buildUrl(network, input.text.toString()) ?: return@setPositiveButton
                ensureBackgroundThread {
                    val ok = OmnioSocials.addSocial(this, contact.contactId, url)
                    runOnUiThread {
                        if (ok) loadApps(contact) else toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setNeutralButton(getString(R.string.omnio_find_on, network.name)) { _, _ -> startFinding(contact, network) }
            .apply {
                setupDialogStuff(wrapper, this, titleText = if (network.key == "discord") "Discord ID" else network.name) { dialog ->
                    input.requestFocus()
                    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
            }
    }

    /** Opens the network's people search for this contact; the profile link is caught when they come back. */
    private fun startFinding(contact: Contact, network: OmnioSocials.Network) {
        val helperOn = org.fossify.phone.services.OmnioLinkHelperService.isEnabled(this)
        val prefs = getSharedPreferences("omnio", MODE_PRIVATE)
        if (!helperOn && !prefs.getBoolean("link_helper_dont_ask", false)) {
            getAlertDialogBuilder()
                .setMessage(getString(R.string.omnio_link_helper_explain, contact.getNameToDisplay()))
                .setPositiveButton(R.string.omnio_turn_on) { _, _ ->
                    OmnioSocials.setPendingLink(this, OmnioSocials.PendingLink(contact.contactId, contact.rawId, contact.getNameToDisplay(), network.key))
                    findAfterSettings = network
                    tryStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    toast(R.string.omnio_link_helper_name)
                }
                .setNegativeButton(R.string.omnio_skip) { _, _ -> openFinding(contact, network) }
                .setNeutralButton(R.string.omnio_dont_ask) { _, _ ->
                    prefs.edit().putBoolean("link_helper_dont_ask", true).apply()
                    openFinding(contact, network)
                }
                .apply { setupDialogStuff(TextView(this@OmnioContactActivity), this, R.string.omnio_link_helper_title) }
            return
        }
        openFinding(contact, network)
    }

    /** Network to search once they're back from switching Link helper on. */
    private var findAfterSettings: OmnioSocials.Network? = null

    private fun openFinding(contact: Contact, network: OmnioSocials.Network) {
        val name = contact.getNameToDisplay()
        OmnioSocials.setPendingLink(this, OmnioSocials.PendingLink(contact.contactId, contact.rawId, name, network.key))
        val search = network.searchUrl(name)
        val opened = search != null && tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(search)).setPackage(network.pkg)) ||
            tryStart(packageManager.getLaunchIntentForPackage(network.pkg)) ||
            search != null && tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(search)))
        if (opened) {
            val helperOn = org.fossify.phone.services.OmnioLinkHelperService.isEnabled(this)
            val tip = when {
                network.key == "discord" -> R.string.omnio_find_instructions_discord
                helperOn -> R.string.omnio_find_instructions_helper
                else -> R.string.omnio_find_instructions
            }
            toast(getString(tip, name), android.widget.Toast.LENGTH_LONG)
        } else {
            OmnioSocials.clearPendingLink(this)
            toast(org.fossify.commons.R.string.no_app_found)
        }
    }

    private fun tryStart(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private var linkDialogShowing = false

    /** Back from "Find on …": if a profile link was copied, offer to add it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkCopiedLink()
    }

    private fun checkCopiedLink() {
        if (linkDialogShowing || !hasWindowFocus()) return
        val current = contact ?: return
        val pending = OmnioSocials.getPendingLink(this) ?: return
        if (pending.contactId != current.contactId) return
        val clip = try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)
        } catch (e: Exception) {
            null
        }
        val url = OmnioSocials.firstUrl(clip)
        if (url == null) {
            // Discord's "Copy ID" / "Copy username" copy plain text, not a link
            val text = clip?.toString()?.trim().orEmpty()
            if (pending.networkKey == "discord" && OmnioSocials.discordUrl(text) != null) {
                saveDiscord(current, text, ask = true)
            }
            return
        }
        handleIncomingLink(current, pending.networkKey, url)
    }

    private fun clipboardText(): String? = try {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }

    /** A copied or shared link: work out what it's for and offer it once. */
    private fun handleIncomingLink(contact: Contact, wantedKey: String?, url: String) {
        val network = OmnioSocials.networkFor(url) ?: return
        if (wantedKey == "messenger" && network.key == "facebook") {
            OmnioSocials.clearPendingLink(this)
            offerMessengerFromFacebook(contact, url, null)
            return
        }
        // already on this contact: nothing to ask
        if (currentSocials.any { it.url.equals(url, true) }) {
            OmnioSocials.clearPendingLink(this)
            return
        }
        offerLink(contact, network, url)
    }

    private fun offerMessengerFromFacebook(contact: Contact, facebookUrl: String, onFail: (() -> Unit)?) {
        toast(R.string.omnio_checking_profile)
        ensureBackgroundThread {
            val resolved = OmnioSocials.resolveFacebook(facebookUrl)
            val messengerUrl = resolved?.let { OmnioSocials.messengerFromFacebook(it) }
            runOnUiThread {
                if (messengerUrl == null) {
                    // Facebook hid the address: ask for the username shown under "profile link"
                    askFacebookUsername(contact, facebookUrl)
                    return@runOnUiThread
                }
                if (currentSocials.any { it.url.equals(messengerUrl, true) }) return@runOnUiThread
                org.fossify.commons.dialogs.ConfirmationDialog(
                    this,
                    message = getString(R.string.omnio_add_messenger_too, contact.getNameToDisplay()) + "\n\n" + messengerUrl,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        OmnioSocials.addSocial(this, contact.contactId, messengerUrl)
                        // swap an opaque facebook.com/share link for the real profile address
                        val oldFacebook = currentSocials.firstOrNull { it.url.equals(facebookUrl, true) && it.network.key == "facebook" }
                        if (oldFacebook != null && resolved != null && resolved != facebookUrl &&
                            OmnioSocials.network("facebook").usernameFrom(facebookUrl).isEmpty()
                        ) {
                            OmnioSocials.addSocial(this, contact.contactId, resolved)
                            OmnioSocials.removeSocial(this, oldFacebook.dataId)
                        }
                        runOnUiThread { loadApps(contact) }
                    }
                }
            }
        }
    }

    /** Saves a Discord ID (replacing any older Discord link on the contact). */
    private fun saveDiscord(contact: Contact, input: String, ask: Boolean) {
        val existing = currentSocials.filter { it.network.key == "discord" }
        val url = OmnioSocials.discordUrl(input) ?: run {
            toast(R.string.omnio_discord_bad_input, android.widget.Toast.LENGTH_LONG)
            return
        }
        if (existing.any { it.url.equals(url, true) }) {
            OmnioSocials.clearPendingLink(this)
            return
        }
        val save = {
            OmnioSocials.clearPendingLink(this)
            ensureBackgroundThread {
                val ok = OmnioSocials.addSocial(this, contact.contactId, url)
                if (ok) existing.forEach { OmnioSocials.removeSocial(this, it.dataId) }
                runOnUiThread { if (ok) loadApps(contact) else toast(org.fossify.commons.R.string.unknown_error_occurred) }
            }
        }
        if (!ask) {
            save()
            return
        }
        linkDialogShowing = true
        org.fossify.commons.dialogs.ConfirmationDialog(
            this,
            message = getString(R.string.omnio_add_discord_question, contact.getNameToDisplay(), OmnioSocials.discordId(url) ?: "")
        , positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel
        ) {
            linkDialogShowing = false
            save()
        }
        binding.root.postDelayed({ linkDialogShowing = false }, 1500)
    }

    /** Called with a link that was copied or shared to Omnio One. */
    fun offerLink(contact: Contact, network: OmnioSocials.Network, url: String) {
        linkDialogShowing = true
        OmnioSocials.clearPendingLink(this)
        org.fossify.commons.dialogs.ConfirmationDialog(
            this,
            message = getString(R.string.omnio_add_link_question, network.name, contact.getNameToDisplay(), url),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel
        ) {
            ensureBackgroundThread {
                val existing = OmnioSocials.findSocials(this, contact.contactId)
                // facebook.com/share/… links hide the username, so look up the real profile address
                val saveUrl = if (network.key == "facebook") OmnioSocials.resolveFacebook(url) ?: url else url
                val ok = existing.any { it.url.equals(saveUrl, true) } || OmnioSocials.addSocial(this, contact.contactId, saveUrl)
                val messengerHandle = if (network.key == "facebook") network.usernameFrom(saveUrl) else ""
                val hasMessenger = existing.any { it.network.key == "messenger" }
                runOnUiThread {
                    linkDialogShowing = false
                    if (!ok) {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                        return@runOnUiThread
                    }
                    loadApps(contact)
                    if (!hasMessenger && network.key == "facebook") {
                        if (messengerHandle.isNotEmpty()) offerMessenger(contact, messengerHandle) else askFacebookUsername(contact, saveUrl)
                    }
                }
            }
        }
        // if the dialog is cancelled, let a later copy be offered again
        binding.root.postDelayed({ linkDialogShowing = false }, 1500)
    }

    /**
     * Facebook's "Copy link" gives facebook.com/share/…, so ask for the username it shows above the
     * button (e.g. jones388). Sets up Messenger and swaps the share link for the real address.
     */
    private fun askFacebookUsername(contact: Contact, facebookUrl: String?) {
        val name = contact.getNameToDisplay()
        val input = android.widget.EditText(this).apply {
            hint = "jones388"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getProperTextColor())
        }
        val explanation = TextView(this).apply {
            text = getString(R.string.omnio_facebook_username_help, name)
            setTextColor(getProperTextColor())
            alpha = 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(8))
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(explanation)
            addView(input)
        }
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val facebook = OmnioSocials.network("facebook")
                val typed = input.text.toString().trim()
                val username = (if (facebook.matches(typed)) facebook.usernameFrom(typed) else typed.removePrefix("@")).replace(" ", "")
                if (username.isEmpty()) return@setPositiveButton
                ensureBackgroundThread {
                    val existing = OmnioSocials.findSocials(this, contact.contactId)
                    val messengerUrl = OmnioSocials.network("messenger").urlFor(username)
                    if (existing.none { it.url.equals(messengerUrl, true) }) OmnioSocials.addSocial(this, contact.contactId, messengerUrl)
                    val profileUrl = facebook.urlFor(username)
                    if (existing.none { it.url.equals(profileUrl, true) }) {
                        OmnioSocials.addSocial(this, contact.contactId, profileUrl)
                        // drop the opaque share link now we have the real address
                        existing.filter { it.network.key == "facebook" && facebook.usernameFrom(it.url).isEmpty() }
                            .forEach { OmnioSocials.removeSocial(this, it.dataId) }
                    }
                    runOnUiThread { loadApps(contact) }
                }
            }
            .setNegativeButton(R.string.omnio_not_now, null)
            .apply {
                setupDialogStuff(wrapper, this, titleText = getString(R.string.omnio_facebook_username_title, name))
            }
    }

    private fun offerMessenger(contact: Contact, handle: String) {
        val messenger = OmnioSocials.networks.first { it.key == "messenger" }
        org.fossify.commons.dialogs.ConfirmationDialog(
            this,
            message = getString(R.string.omnio_add_messenger_too, contact.getNameToDisplay()),
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no
        ) {
            ensureBackgroundThread {
                OmnioSocials.addSocial(this, contact.contactId, messenger.urlFor(handle))
                runOnUiThread { loadApps(contact) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // a link shared to Omnio One while finding this contact
        val url = intent.getStringExtra(EXTRA_SHARED_LINK) ?: return
        val current = contact ?: return
        handleIncomingLink(current, OmnioSocials.getPendingLink(this)?.networkKey, url)
    }

    private fun confirmRemoveSocial(contact: Contact, social: OmnioSocials.FoundSocial) {
        org.fossify.commons.dialogs.ConfirmationDialog(
            this,
            message = getString(R.string.omnio_remove_social, social.network.name)
        ) {
            ensureBackgroundThread {
                OmnioSocials.removeSocial(this, social.dataId)
                runOnUiThread { loadApps(contact) }
            }
        }
    }

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
                    val rawBody = (cursor.getString(1) ?: "").replace(Regex("\\s+"), " ").trim()
                    val body = org.fossify.messages.helpers.OmnioReactions.describe(rawBody) ?: rawBody
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

    /** Number(s) then email(s) under the name: tap to call / email, small Copy and Share buttons. */
    private fun showContactInfo(contact: Contact, name: String) {
        val holder = binding.contactInfoHolder
        holder.removeAllViews()
        val showTypes = contact.phoneNumbers.size > 1
        contact.phoneNumbers.forEach { number ->
            val label = if (showTypes) "${number.value}  ·  ${getPhoneTypeLabel(number.type, number.label)}" else number.value
            addInfoLine(holder, label, number.value) { startCallWithConfirmationCheck(number.value, name) }
        }
        contact.emails.forEach { email ->
            addInfoLine(holder, email.value, email.value) { sendEmailIntent(email.value) }
        }
        if (contact.emails.isEmpty()) {
            holder.addView(TextView(this).apply {
                text = "+ " + getString(R.string.omnio_add_email)
                setTextColor(0xFFFF8A3D.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { askEmail(contact) }
            })
        }
    }

    private fun addInfoLine(holder: LinearLayout, label: String, value: String, onClick: () -> Unit) {
        val textColor = getProperTextColor()
        val iconColor = 0xFF9AA3AF.toInt()
        holder.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@OmnioContactActivity).apply {
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
                addView(ImageView(this@OmnioContactActivity).apply {
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

    private fun shareText(text: String) {
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
    private fun askEmail(contact: Contact) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.omnio_email)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(getProperTextColor())
        }
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val email = input.text.toString().trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    toast(R.string.omnio_invalid_email)
                    return@setPositiveButton
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
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(wrapper, this, R.string.omnio_add_email) { dialog ->
                    input.requestFocus()
                    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
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
