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

// Omnio One contact page: "Find on Facebook" and friends: catching a copied, shared or on-screen profile link and saving it.

/** Opens the network's people search for this contact; the profile link is caught when they come back. */
internal fun OmnioContactActivity.startFinding(contact: Contact, network: OmnioSocials.Network) {
    val helperOn = com.omnioone.app.services.OmnioLinkHelperService.isEnabled(this)
    val prefs = getSharedPreferences("omnio", android.content.Context.MODE_PRIVATE)
    if (!helperOn && !prefs.getBoolean("link_helper_dont_ask", false)) {
        OmnioSheet.confirm(
            this,
            title = getString(R.string.omnio_link_helper_title),
            message = getString(R.string.omnio_link_helper_explain, contact.getNameToDisplay()),
            positive = getString(R.string.omnio_turn_on),
            negative = getString(R.string.omnio_skip),
            neutral = getString(R.string.omnio_dont_ask),
            onNeutral = {
                prefs.edit().putBoolean("link_helper_dont_ask", true).apply()
                openFinding(contact, network)
            },
            onNegative = { openFinding(contact, network) },
            mainColor = OmnioSheet.GREEN
        ) {
            OmnioSocials.setPendingLink(this, OmnioSocials.PendingLink(contact.contactId, contact.rawId, contact.getNameToDisplay(), network.key))
            findAfterSettings = network
            tryStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(R.string.omnio_link_helper_name)
        }
        return
    }
    openFinding(contact, network)
}

internal fun OmnioContactActivity.openFinding(contact: Contact, network: OmnioSocials.Network) {
    val name = contact.getNameToDisplay()
    OmnioSocials.setPendingLink(this, OmnioSocials.PendingLink(contact.contactId, contact.rawId, name, network.key))
    val search = network.searchUrl(name)
    val opened = search != null && tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(search)).setPackage(network.pkg)) ||
        tryStart(packageManager.getLaunchIntentForPackage(network.pkg)) ||
        search != null && tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(search)))
    if (opened) {
        val helperOn = com.omnioone.app.services.OmnioLinkHelperService.isEnabled(this)
        val tip = when {
            network.key == "discord" -> R.string.omnio_find_instructions_discord
            helperOn -> R.string.omnio_find_instructions_helper
            else -> R.string.omnio_find_instructions
        }
        toast(getString(tip, name), android.widget.Toast.LENGTH_LONG)
    } else {
        OmnioSocials.clearPendingLink(this)
        toast(com.omnioone.commons.R.string.no_app_found)
    }
}

internal fun OmnioContactActivity.tryStart(intent: Intent?): Boolean {
    if (intent == null) return false
    return try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

internal fun OmnioContactActivity.checkCopiedLink() {
    if (linkDialogShowing || !hasWindowFocus()) return
    val current = contact ?: return
    val pending = OmnioSocials.getPendingLink(this) ?: return
    if (pending.contactId != current.contactId) return
    val clip = try {
        (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)
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

internal fun OmnioContactActivity.clipboardText(): String? = try {
    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
} catch (e: Exception) {
    null
}

/** A copied or shared link: work out what it's for and offer it once. */
internal fun OmnioContactActivity.handleIncomingLink(contact: Contact, wantedKey: String?, url: String) {
    val network = OmnioSocials.networkFor(url) ?: return
    if (wantedKey == "messenger" && network.key == "facebook") {
        OmnioSocials.clearPendingLink(this)
        offerMessengerFromFacebook(contact, url)
        return
    }
    // already on this contact: nothing to ask
    if (currentSocials.any { it.url.equals(url, true) }) {
        OmnioSocials.clearPendingLink(this)
        return
    }
    offerLink(contact, network, url)
}

internal fun OmnioContactActivity.offerMessengerFromFacebook(contact: Contact, facebookUrl: String) {
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
            OmnioSheet.confirm(
                this,
                title = getString(R.string.omnio_add_messenger_title, contact.getNameToDisplay()),
                message = messengerUrl,
                positive = getString(com.omnioone.commons.R.string.yes),
                negative = getString(com.omnioone.commons.R.string.no)
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
internal fun OmnioContactActivity.saveDiscord(contact: Contact, input: String, ask: Boolean) {
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
            runOnUiThread { if (ok) loadApps(contact) else toast(com.omnioone.commons.R.string.unknown_error_occurred) }
        }
    }
    if (!ask) {
        save()
        return
    }
    linkDialogShowing = true
    OmnioSheet.confirm(
        this,
        title = getString(R.string.omnio_add_discord_title, contact.getNameToDisplay()),
        message = OmnioSocials.discordId(url) ?: "",
        positive = getString(com.omnioone.commons.R.string.ok),
        onNegative = { linkDialogShowing = false }
    ) {
        linkDialogShowing = false
        save()
    }
    binding.root.postDelayed({ linkDialogShowing = false }, 1500)
}

/** Called with a link that was copied or shared to Omnio One. */
internal fun OmnioContactActivity.offerLink(contact: Contact, network: OmnioSocials.Network, url: String) {
    linkDialogShowing = true
    OmnioSocials.clearPendingLink(this)
    OmnioSheet.confirm(
        this,
        title = getString(R.string.omnio_add_link_title, network.name, contact.getNameToDisplay()),
        message = url,
        positive = getString(com.omnioone.commons.R.string.ok),
        onNegative = { linkDialogShowing = false }
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
                    toast(com.omnioone.commons.R.string.unknown_error_occurred)
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
internal fun OmnioContactActivity.askFacebookUsername(contact: Contact, facebookUrl: String?) {
    val name = contact.getNameToDisplay()
    OmnioSheet.input(
        this,
        title = getString(R.string.omnio_facebook_username_title, name),
        message = getString(R.string.omnio_facebook_username_help, name),
        hint = "jones388",
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        negative = getString(R.string.omnio_not_now)
    ) { typedRaw ->
        val facebook = OmnioSocials.network("facebook")
        val typed = typedRaw.trim()
        val username = (if (facebook.matches(typed)) facebook.usernameFrom(typed) else typed.removePrefix("@")).replace(" ", "")
        if (username.isEmpty()) return@input
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
}

internal fun OmnioContactActivity.offerMessenger(contact: Contact, handle: String) {
    val messenger = OmnioSocials.network("messenger")
    OmnioSheet.confirm(
        this,
        title = getString(R.string.omnio_add_messenger_title, contact.getNameToDisplay()),
        message = getString(R.string.omnio_add_messenger_help),
        positive = getString(com.omnioone.commons.R.string.yes),
        negative = getString(com.omnioone.commons.R.string.no)
    ) {
        ensureBackgroundThread {
            OmnioSocials.addSocial(this, contact.contactId, messenger.urlFor(handle))
            runOnUiThread { loadApps(contact) }
        }
    }
}
