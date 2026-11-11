package com.omnioone.app.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioSheet

/** A choice in a list (id, label, and any value to hand back). */
data class RadioItem(val id: Int, val title: String, val value: Any = id)

/** Omnio-styled pop-up builder for the few screens that still use classic dialogs. */
fun Activity.getAlertDialogBuilder(): AlertDialog.Builder = MaterialAlertDialogBuilder(this)

/** Shows a classic dialog with [view] inside. */
fun Activity.setupDialogStuff(
    view: View,
    builder: AlertDialog.Builder,
    titleId: Int = 0,
    titleText: String = "",
    cancelOnTouchOutside: Boolean = true,
    callback: ((AlertDialog) -> Unit)? = null,
) {
    if (isDestroyed || isFinishing) return
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    builder.setView(view)
    if (titleId != 0) builder.setTitle(titleId) else if (titleText.isNotEmpty()) builder.setTitle(titleText)
    val dialog = builder.create()
    dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
    dialog.show()
    callback?.invoke(dialog)
}

/** Pick one of [items]; the chosen item's value goes to [onPick]. */
fun Activity.pickOne(title: String, items: List<RadioItem>, checkedId: Int, onPick: (Any) -> Unit) {
    OmnioSheet.list(this, title, null, items.map { OmnioSheet.Item(it.title, trailing = if (it.id == checkedId) "✓" else null) }) { index ->
        onPick(items[index].value)
    }
}

/** "Are you sure?" with yes and no. */
fun Activity.confirm(message: String, positive: String = getString(R.string.yes), onYes: () -> Unit) {
    OmnioSheet.confirm(this, title = message, message = null, positive = positive) { onYes() }
}

/** Explains why a permission or setting is needed, with a button to open the right settings. */
fun Activity.explainPermission(textId: Int, onOpenSettings: () -> Unit, onNotNow: () -> Unit = {}) {
    OmnioSheet.confirm(
        this,
        title = getString(R.string.omnio_permission_needed),
        message = getString(textId),
        positive = getString(R.string.omnio_open_settings),
        onNegative = onNotNow,
    ) { onOpenSettings() }
}

/** "Call Alex?" before calling, when that setting is on. */
fun Activity.confirmCall(name: String, onCall: () -> Unit) {
    OmnioSheet.confirm(this, title = getString(R.string.omnio_call_question, name), message = null, positive = getString(R.string.call), mainColor = OmnioColors.CALLS) { onCall() }
}

/** Starts a call to [recipient] (through Omnio's own calling screen when Omnio is the phone app). */
fun OmnioActivity.launchCallIntent(recipient: String, handle: PhoneAccountHandle? = null) {
    handlePermission(PERMISSION_CALL_PHONE) { granted ->
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", recipient, null))
        if (handle != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        if (isDefaultDialer()) intent.setClassName(packageName, "com.omnioone.app.activities.DialerActivity")
        launchActivityIntent(intent)
    }
}

/** Calls a contact: straight away if they have one number, or lets you pick which. */
fun OmnioActivity.initiateCall(contact: Contact, onNumber: (String) -> Unit) {
    val numbers = contact.phoneNumbers
    when {
        numbers.isEmpty() -> toast(R.string.omnio_no_number)
        numbers.size == 1 -> onNumber(numbers.first().value)
        else -> {
            val primary = numbers.firstOrNull { it.isPrimary }
            if (primary != null) {
                onNumber(primary.value)
                return
            }
            OmnioSheet.list(this, contact.getNameToDisplay(), null, numbers.map {
                OmnioSheet.Item(it.value, trailing = getPhoneNumberTypeText(it.type, it.label))
            }) { index -> onNumber(numbers[index].value) }
        }
    }
}
