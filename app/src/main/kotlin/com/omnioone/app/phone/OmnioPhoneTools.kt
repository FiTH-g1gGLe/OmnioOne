package com.omnioone.app.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.content.res.ColorStateList
import com.omnioone.app.BuildConfig
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.activities.OmnioEditContactActivity
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.core.Contact
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.OmnioContactStore
import com.omnioone.app.core.PERMISSION_READ_PHONE_STATE
import com.omnioone.app.core.canUseFullScreenIntent
import com.omnioone.app.core.confirmCall
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.app.core.explainPermission
import com.omnioone.app.core.initiateCall
import com.omnioone.app.core.isDefaultDialer
import com.omnioone.app.core.launchCallIntent
import com.omnioone.app.core.launchViewContactIntent
import com.omnioone.app.core.openFullScreenIntentSettings
import com.omnioone.app.core.openNotificationSettings
import com.omnioone.app.core.telecomManager
import com.omnioone.app.helpers.OmnioSheet

/** A SIM card that can make calls. [id] counts from 1 in slot order. */
data class OmnioCallSim(val id: Int, val handle: PhoneAccountHandle, val label: String, val number: String, val color: Int)

/** Your call-capable SIMs, labelled with their number when Android knows it. */
@SuppressLint("MissingPermission")
fun Context.callSims(): List<OmnioCallSim> = try {
    telecomManager.callCapablePhoneAccounts.mapIndexedNotNull { index, handle ->
        val account = telecomManager.getPhoneAccount(handle) ?: return@mapIndexedNotNull null
        val number = account.address?.schemeSpecificPart?.let { Uri.decode(it) }.orEmpty()
        val name = account.label?.toString().orEmpty().ifBlank { "SIM ${index + 1}" }
        OmnioCallSim(index + 1, handle, if (number.isNotEmpty()) "$name ($number)" else name, number, account.highlightColor)
    }
} catch (e: Exception) {
    emptyList()
}

@SuppressLint("MissingPermission")
fun Context.hasSeveralSims(): Boolean = try {
    telecomManager.callCapablePhoneAccounts.size > 1
} catch (e: Exception) {
    false
}

/** Clears the missed-call notification (and Android's missed-call count with it). */
fun Context.clearMissedCalls() = ensureBackgroundThread {
    try {
        telecomManager.cancelMissedCallsNotification()
    } catch (ignored: Exception) {
    }
}

/** Opens Omnio's editor for a new contact, with the number filled in if given. */
fun Activity.openNewContact(number: String? = null) {
    startActivity(Intent(this, OmnioEditContactActivity::class.java).apply {
        number?.let { putExtra(OmnioEditContactActivity.EXTRA_NUMBER, it) }
    })
}

fun Activity.openContactPage(contact: Contact) {
    startActivity(Intent(this, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, contact.rawId))
}

/** Opens the contact in the phone's own contacts app, for things Omnio can't edit itself. */
fun Activity.openInPhoneContacts(contact: Contact) = ensureBackgroundThread {
    val key = OmnioContactStore(this).getContactLookupKey(contact.rawId.toString())
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
    runOnUiThread { launchViewContactIntent(uri) }
}

// ---- calling ----

/** Calls a number, choosing the SIM the usual way (or asking, if [askForSim]). */
fun OmnioActivity.startCallIntent(number: String, askForSim: Boolean = false) {
    if (!isDefaultDialer()) {
        launchCallIntent(number, null)
        return
    }
    chooseSim(null, number, askForSim) { handle -> launchCallIntent(number, handle) }
}

/** Calls a number, asking "Call Mum?" first if that setting is on. */
fun OmnioActivity.startCallWithConfirmationCheck(number: String, name: String, askForSim: Boolean = false) {
    if (config.showCallConfirmation) confirmCall(name) { startCallIntent(number, askForSim) } else startCallIntent(number, askForSim)
}

fun OmnioActivity.startCallWithConfirmationCheck(contact: Contact) {
    val call = { initiateCall(contact) { launchCallIntent(it) } }
    if (config.showCallConfirmation) confirmCall(contact.getNameToDisplay()) { call() } else call()
}

/** Calls on a particular SIM: the first one or the second one. */
fun OmnioActivity.callContactWithSimWithConfirmationCheck(number: String, name: String, useMainSIM: Boolean) {
    val call = {
        handlePermission(PERMISSION_READ_PHONE_STATE) {
            launchCallIntent(number, callSims().getOrNull(if (useMainSIM) 0 else 1)?.handle)
        }
    }
    if (config.showCallConfirmation) confirmCall(name) { call() } else call()
}

/**
 * Works out which SIM to call on: one passed in by another app, the one you chose to always
 * use for this number, the phone's default, or asks you.
 */
@SuppressLint("MissingPermission")
fun OmnioActivity.chooseSim(intent: Intent?, number: String, askForSim: Boolean = false, use: (PhoneAccountHandle?) -> Unit) {
    handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
        if (!granted) return@handlePermission
        @Suppress("DEPRECATION")
        val passedIn = intent?.getParcelableExtra<PhoneAccountHandle>(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)
        val default = try {
            telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
        } catch (e: Exception) {
            null
        }
        when {
            askForSim -> pickSim(number, use)
            passedIn != null -> use(passedIn)
            config.simFor(number) != null -> use(config.simFor(number))
            default != null -> use(default)
            else -> pickSim(number, use)
        }
    }
}

/** An Omnio sheet listing your SIMs, with "Always use for this number". */
fun OmnioActivity.pickSim(number: String, use: (PhoneAccountHandle?) -> Unit) {
    val sims = callSims()
    if (sims.isEmpty()) {
        use(null)
        return
    }
    var remember = false
    var picked = false
    val rememberRow: (() -> Unit) -> View = {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(CheckBox(this@pickSim).apply {
                text = getString(R.string.always_use_this_sim)
                setTextColor(OmnioColors.TEXT)
                buttonTintList = ColorStateList.valueOf(OmnioColors.CALLS)
                setOnCheckedChangeListener { _, checked -> remember = checked }
            })
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
    }
    OmnioSheet.list(
        this,
        getString(R.string.select_sim),
        number,
        sims.map { OmnioSheet.Item("SIM ${it.id} · ${it.label}") },
        top = rememberRow,
        onCancel = { if (!picked && this is com.omnioone.app.activities.OmnioOutgoingCallActivity) finish() },
    ) { index ->
        picked = true
        val handle = sims[index].handle
        if (remember) config.rememberSim(number, handle)
        use(handle)
    }
}

/** Makes sure incoming calls can show full screen, explaining why if a permission is missing. */
fun OmnioActivity.handleFullScreenNotificationsPermission(done: (granted: Boolean) -> Unit) {
    handleNotificationPermission { granted ->
        when {
            !granted -> explainPermission(
                R.string.allow_notifications_incoming_calls,
                onOpenSettings = { openNotificationSettings() },
                onNotNow = { done(false) },
            )
            canUseFullScreenIntent() -> done(true)
            else -> explainPermission(
                R.string.allow_full_screen_notifications_incoming_calls,
                onOpenSettings = { if (Build.VERSION.SDK_INT >= 34) openFullScreenIntentSettings(BuildConfig.APPLICATION_ID) },
                onNotNow = { done(false) },
            )
        }
    }
}
