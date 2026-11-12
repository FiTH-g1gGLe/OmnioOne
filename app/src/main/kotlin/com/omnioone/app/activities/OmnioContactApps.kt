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
import com.omnioone.app.core.getProperBackgroundColor
import com.omnioone.app.core.getProperTextColor
import com.omnioone.app.core.launchSendSMSIntent
import com.omnioone.app.core.sendEmailIntent
import com.omnioone.app.core.showErrorToast
import com.omnioone.app.core.hasPermission
import com.omnioone.app.core.copyToClipboard
import com.omnioone.app.core.toast
import com.omnioone.app.core.viewBinding
import com.omnioone.app.core.OmnioContactStore
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.app.core.Contact
import com.omnioone.app.R
import com.omnioone.app.databinding.ActivityOmnioContactBinding
import com.omnioone.app.phone.config
import com.omnioone.app.phone.callSims
import com.omnioone.app.phone.openInPhoneContacts
import com.omnioone.app.phone.startCallWithConfirmationCheck
import com.omnioone.app.core.beVisibleIf
import com.omnioone.app.core.setupDialogStuff
import com.omnioone.app.core.getAlertDialogBuilder
import com.omnioone.app.helpers.OmnioSocials
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.core.beVisible
import com.omnioone.app.core.getFormattedDuration

// Omnio One contact page: Chat apps and social profiles card on the contact page (WhatsApp, Facebook, Discord, …).

internal fun OmnioContactActivity.loadApps(contact: Contact) {
    ensureBackgroundThread {
        val chats = OmnioSocials.findChats(this, contact.contactId)
        val socials = OmnioSocials.findSocials(this, contact.contactId)
        runOnUiThread { showApps(contact, chats, socials) }
    }
}

internal fun OmnioContactActivity.showApps(contact: Contact, chats: List<OmnioSocials.FoundChat>, socials: List<OmnioSocials.FoundSocial>) {
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
                    OmnioSocials.Kind.VOICE -> com.omnioone.app.R.drawable.ic_phone_vector
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
                    com.omnioone.app.R.drawable.ic_phone_vector to { messengerCall(social, OmnioSocials.AutoTap.VOICE) },
                    R.drawable.ic_omnio_video to { messengerCall(social, OmnioSocials.AutoTap.VIDEO) },
                )
            } else {
                listOf(com.omnioone.app.R.drawable.ic_arrow_right_vector to { openSocial(social) })
            }
        )
    }
    // "+ Add social" row
    addAppRow(
        holder = holder,
        showDivider = rows > 0,
        icon = getDrawable(com.omnioone.app.R.drawable.ic_plus_vector)?.mutate()?.apply { setTint(OmnioColors.CONTACTS) },
        title = getString(R.string.omnio_add_social),
        subtitle = null,
        color = OmnioColors.CONTACTS,
        onClick = { pickNetworkToAdd(contact) },
        onLongClick = null,
        actions = emptyList()
    )
}

/** The app's real icon from the phone, or a coloured letter if it isn't installed. */
internal fun OmnioContactActivity.appIcon(pkg: String, name: String, color: Int): android.graphics.drawable.Drawable? {
    return try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        val size = dp(36)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val radius = size * 0.24f
        canvas.drawRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, android.graphics.Paint().apply { isAntiAlias = true; this.color = color })
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.6) OmnioColors.ON_ACCENT else 0xFFFFFFFF.toInt()
            textSize = size * 0.48f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(name.take(1), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }
}

internal fun OmnioContactActivity.addAppRow(
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
        addView(TextView(this@addAppRow).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (!subtitle.isNullOrEmpty()) {
            addView(TextView(this@addAppRow).apply {
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
    val actionColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.08) OmnioColors.TEXT else color
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

internal fun OmnioContactActivity.openChat(chat: OmnioSocials.FoundChat) {
    OmnioSocials.clearPendingLink(this)
    try {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.android.contacts/data/${chat.dataId}"), chat.action.mimeType)
            setPackage(chat.app.pkg)
        })
    } catch (e: Exception) {
        toast(com.omnioone.app.R.string.no_app_found)
    }
}

internal fun OmnioContactActivity.openSocial(social: OmnioSocials.FoundSocial) {
    // using a linked profile ends any "Find on…": stop listening for links
    OmnioSocials.clearPendingLink(this)
    if (social.network.key == "discord" && OmnioSocials.discordId(social.url) == null) {
        // an old username-only link: Discord can't open those
        toast(R.string.omnio_discord_need_id, android.widget.Toast.LENGTH_LONG)
        tryStart(packageManager.getLaunchIntentForPackage(social.network.pkg))
        return
    }
    // with Link helper on, their profile photo is caught on the way and offered when you come back
    contact?.let { current ->
        if (com.omnioone.app.services.OmnioLinkHelperService.isEnabled(this)) {
            com.omnioone.app.helpers.OmnioPhotoCapture.setPending(this, current.contactId, current.rawId)
        }
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
            toast(com.omnioone.app.R.string.no_app_found)
        }
    }
}

/** Opens the Messenger chat; with Link helper on, it then taps Messenger's call / video button. */
internal fun OmnioContactActivity.messengerCall(social: OmnioSocials.FoundSocial, tap: OmnioSocials.AutoTap) {
    val helperOn = com.omnioone.app.services.OmnioLinkHelperService.isEnabled(this)
    if (helperOn) {
        OmnioSocials.setAutoTap(this, social.network.pkg, tap)
    } else {
        toast(R.string.omnio_messenger_call_manual, android.widget.Toast.LENGTH_LONG)
    }
    openSocial(social)
}

/** Omnio-styled bottom sheet listing the socials that can be linked. */
internal fun OmnioContactActivity.pickNetworkToAdd(contact: Contact) {
    val linked = currentSocials.map { it.network.key }.toSet()
    OmnioSheet.list(
        this,
        title = getString(R.string.omnio_add_social_for, contact.getNameToDisplay().trim().substringBefore(" ")),
        subtitle = getString(R.string.omnio_add_social_subtitle),
        items = OmnioSocials.networks.map { network ->
            OmnioSheet.Item(
                label = network.name,
                icon = appIcon(network.pkg, network.name, network.color),
                trailing = if (network.key in linked) getString(R.string.omnio_linked) else null
            )
        }
    ) { picked -> onNetworkPicked(contact, OmnioSocials.networks[picked]) }
}

internal fun OmnioContactActivity.onNetworkPicked(contact: Contact, network: OmnioSocials.Network) {
        val facebook = currentSocials.firstOrNull { it.network.key == "facebook" }
        if (network.key == "messenger" && facebook != null) {
            // build Messenger straight from the Facebook profile that's already linked
            offerMessengerFromFacebook(contact, facebook.url)
        } else {
            askUsername(contact, network)
        }
}

internal fun OmnioContactActivity.askUsername(contact: Contact, network: OmnioSocials.Network) {
    val isDiscord = network.key == "discord"
    // fill in something useful that's already been copied
    var initial = ""
    clipboardText()?.trim()?.let { clip ->
        val url = OmnioSocials.firstUrl(clip)
        when {
            url != null && network.matches(url) -> initial = url
            isDiscord && OmnioSocials.discordUrl(clip) != null -> initial = clip
        }
    }
    OmnioSheet.input(
        this,
        title = if (isDiscord) getString(R.string.omnio_add_discord_id_title) else getString(R.string.omnio_add_network_title, network.name),
        message = getString(if (isDiscord) R.string.omnio_discord_hint else R.string.omnio_username_hint),
        hint = if (isDiscord) "123456789012345678" else "@username",
        initial = initial,
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        neutral = getString(R.string.omnio_find_on, network.name),
        onNeutral = { startFinding(contact, network) }
    ) { text ->
        if (isDiscord) {
            saveDiscord(contact, text, ask = false)
            return@input
        }
        val url = OmnioSocials.buildUrl(network, text) ?: return@input
        ensureBackgroundThread {
            val ok = OmnioSocials.addSocial(this, contact.contactId, url)
            runOnUiThread {
                if (ok) loadApps(contact) else toast(com.omnioone.app.R.string.unknown_error_occurred)
            }
        }
    }
}

internal fun OmnioContactActivity.confirmRemoveSocial(contact: Contact, social: OmnioSocials.FoundSocial) {
    OmnioSheet.confirm(
        this,
        title = getString(R.string.omnio_remove_social, social.network.name),
        message = social.url,
        positive = getString(R.string.omnio_remove),
        mainColor = OmnioSheet.RED
    ) {
        ensureBackgroundThread {
            OmnioSocials.removeSocial(this, social.dataId)
            runOnUiThread { loadApps(contact) }
        }
    }
}

/** A profile photo the Link helper caught for this contact: offer it as their contact photo. */
internal fun OmnioContactActivity.offerCapturedPhoto() {
    val current = contact ?: return
    val helper = com.omnioone.app.helpers.OmnioPhotoCapture
    val bitmap = helper.captured(this, current.contactId) ?: return
    helper.discard(this, current.contactId)
    helper.clearPending(this)
    com.omnioone.app.helpers.OmnioSheet.photo(
        this,
        title = getString(R.string.omnio_photo_use_title, current.getNameToDisplay()),
        message = getString(R.string.omnio_photo_use_desc),
        bitmap = bitmap,
        positive = getString(R.string.omnio_photo_use),
        negative = getString(R.string.omnio_not_now)
    ) {
        ensureBackgroundThread {
            val ok = helper.saveToContact(this, current.rawId, bitmap)
            runOnUiThread {
                if (ok) {
                    com.bumptech.glide.Glide.with(this).load(bitmap).circleCrop().into(binding.contactPhoto)
                    toast(R.string.omnio_photo_saved)
                } else {
                    toast(com.omnioone.app.R.string.unknown_error_occurred)
                }
            }
        }
    }
}
