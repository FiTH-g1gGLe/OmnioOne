package com.omnioone.app.helpers

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.activities.OmnioEditContactActivity
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.extensions.areMultipleSIMsAvailable
import com.omnioone.app.extensions.callContactWithSimWithConfirmationCheck
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.core.launchSendSMSIntent
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.PERMISSION_WRITE_CONTACTS
import com.omnioone.app.core.ensureBackgroundThread

/** Omnio One: what you can do with a contact from a list (long-press menu, calling, opening). */
object OmnioContactActions {

    fun open(activity: SimpleActivity, person: OmnioPeople.Person) {
        activity.startActivity(Intent(activity, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, person.rawId))
    }

    /** Calls their number, asking which one first if they have several. */
    fun call(activity: SimpleActivity, person: OmnioPeople.Person) {
        pickNumber(activity, person, R.string.omnio_call) { number -> activity.startCallWithConfirmationCheck(number, person.name) }
    }

    fun text(activity: SimpleActivity, person: OmnioPeople.Person) {
        pickNumber(activity, person, R.string.omnio_text) { number -> activity.launchSendSMSIntent(number) }
    }

    private fun pickNumber(activity: SimpleActivity, person: OmnioPeople.Person, titleRes: Int, use: (String) -> Unit) {
        when (person.numbers.size) {
            0 -> return
            1 -> use(person.numbers.first().value)
            else -> OmnioSheet.list(
                activity,
                activity.getString(titleRes) + " " + person.firstName,
                null,
                person.numbers.map { OmnioSheet.Item(it.value, trailing = it.label, trailingColor = OmnioSheet.TEXT) }
            ) { picked -> use(person.numbers[picked].value) }
        }
    }

    fun showMenu(activity: SimpleActivity, person: OmnioPeople.Person) {
        val actions = ArrayList<Pair<OmnioSheet.Item, () -> Unit>>()
        fun add(label: String, icon: Int, colour: Int, labelColour: Int = OmnioSheet.TEXT, action: () -> Unit) {
            val drawable = ContextCompat.getDrawable(activity, icon)?.mutate()?.apply { setTint(colour) }
            actions += OmnioSheet.Item(label, icon = drawable, labelColor = labelColour) to action
        }
        val favourite = if (OmnioFavourites.isLoaded) OmnioFavourites.isFavourite(person.contactId) else person.starred

        add(activity.getString(R.string.omnio_call), com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS) { call(activity, person) }
        if (activity.areMultipleSIMsAvailable()) {
            add(activity.getString(R.string.omnio_call_sim_1), com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS) {
                pickNumber(activity, person, R.string.omnio_call) { activity.callContactWithSimWithConfirmationCheck(it, person.name, useMainSIM = true) }
            }
            add(activity.getString(R.string.omnio_call_sim_2), com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS) {
                pickNumber(activity, person, R.string.omnio_call) { activity.callContactWithSimWithConfirmationCheck(it, person.name, useMainSIM = false) }
            }
        }
        add(activity.getString(R.string.omnio_text), R.drawable.ic_omnio_chat, OmnioColors.MESSAGES) { text(activity, person) }
        add(activity.getString(R.string.omnio_open_contact), com.omnioone.commons.R.drawable.ic_person_vector, OmnioColors.CONTACTS) { open(activity, person) }
        add(activity.getString(R.string.omnio_edit_contact), com.omnioone.commons.R.drawable.ic_edit_vector, OmnioColors.CONTACTS) {
            activity.startActivity(Intent(activity, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_CONTACT_ID, person.rawId))
        }
        add(
            activity.getString(if (favourite) R.string.omnio_remove_favourite else R.string.omnio_add_favourite),
            if (favourite) com.omnioone.commons.R.drawable.ic_star_vector else com.omnioone.commons.R.drawable.ic_star_outline_vector,
            OmnioColors.FAVOURITES
        ) { OmnioFavourites.set(activity, person.contactId, !favourite) }
        add(activity.getString(R.string.omnio_share_contact), com.omnioone.commons.R.drawable.ic_share_vector, OmnioColors.TEXT_MUTED) { share(activity, person) }
        add(activity.getString(R.string.omnio_add_to_home), com.omnioone.commons.R.drawable.ic_add_person_vector, OmnioColors.TEXT_MUTED) { addShortcut(activity, person) }
        add(activity.getString(R.string.omnio_block_number), com.omnioone.commons.R.drawable.ic_block_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmBlock(activity, person) }
        add(activity.getString(R.string.omnio_delete_contact), com.omnioone.commons.R.drawable.ic_delete_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmDelete(activity, person) }

        val subtitle = listOfNotNull(person.mainNumber.ifEmpty { null }, person.company.ifEmpty { null }).joinToString("  ·  ").ifEmpty { null }
        OmnioSheet.list(activity, person.name, subtitle, actions.map { it.first }) { picked -> actions[picked].second() }
    }

    private fun share(activity: SimpleActivity, person: OmnioPeople.Person) {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, person.lookupKey)
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, activity.getString(R.string.omnio_share_contact)))
        } catch (e: Exception) {
            activity.toast(com.omnioone.commons.R.string.no_app_found)
        }
    }

    /** A home-screen icon that calls them in one tap. */
    private fun addShortcut(activity: SimpleActivity, person: OmnioPeople.Person) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            activity.toast(R.string.omnio_shortcut_unsupported)
            return
        }
        pickNumber(activity, person, R.string.omnio_add_to_home) { number ->
            ensureBackgroundThread {
                val size = (activity.resources.displayMetrics.density * 96).toInt()
                val image: Bitmap = try {
                    Glide.with(activity).asBitmap().load(person.photo.ifEmpty { null }).circleCrop().submit(size, size).get()
                } catch (e: Exception) {
                    OmnioDisplay.letterTile(activity, person.name, size.toFloat())
                }
                val shortcut = ShortcutInfo.Builder(activity, "call_${person.contactId}_${number.filter { it.isDigit() }}")
                    .setShortLabel(person.firstName.ifEmpty { person.name })
                    .setLongLabel(person.name)
                    .setIcon(Icon.createWithBitmap(image))
                    .setIntent(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
                    .build()
                activity.runOnUiThread {
                    try {
                        manager.requestPinShortcut(shortcut, null)
                    } catch (e: Exception) {
                        activity.toast(R.string.omnio_shortcut_unsupported)
                    }
                }
            }
        }
    }

    private fun confirmBlock(activity: SimpleActivity, person: OmnioPeople.Person) {
        OmnioSheet.confirm(
            activity,
            title = activity.getString(R.string.omnio_block_question, person.name),
            message = activity.getString(R.string.omnio_block_desc),
            positive = activity.getString(R.string.omnio_block_number),
            mainColor = OmnioColors.MISSED
        ) {
            ensureBackgroundThread {
                val blocked = person.numbers.count { number ->
                    try {
                        activity.contentResolver.insert(
                            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                            ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number.value) }
                        ) != null
                    } catch (e: Exception) {
                        false
                    }
                }
                activity.runOnUiThread { activity.toast(if (blocked > 0) R.string.omnio_blocked else R.string.omnio_block_failed) }
            }
        }
    }

    private fun confirmDelete(activity: SimpleActivity, person: OmnioPeople.Person) {
        OmnioSheet.confirm(
            activity,
            title = activity.getString(R.string.omnio_delete_contact_question, person.name),
            message = activity.getString(R.string.omnio_delete_contact_desc),
            positive = activity.getString(com.omnioone.commons.R.string.delete),
            mainColor = OmnioColors.MISSED
        ) {
            activity.handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
                if (!granted) return@handlePermission
                ensureBackgroundThread {
                    try {
                        activity.contentResolver.delete(
                            ContactsContract.RawContacts.CONTENT_URI,
                            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                            arrayOf(person.contactId.toString())
                        )
                    } catch (ignored: Exception) {
                    }
                    activity.runOnUiThread { activity.toast(R.string.omnio_contact_deleted) }
                }
            }
        }
    }
}
