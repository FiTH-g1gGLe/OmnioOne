package com.omnioone.messages.extensions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.extensions.getMimeType
import com.omnioone.commons.extensions.hideKeyboard
import com.omnioone.commons.extensions.isPackageInstalled
import com.omnioone.commons.extensions.launchActivityIntent
import com.omnioone.commons.extensions.launchViewContactIntent
import com.omnioone.commons.extensions.showErrorToast
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.helpers.CONTACT_ID
import com.omnioone.commons.helpers.IS_PRIVATE
import com.omnioone.commons.helpers.PERMISSION_CALL_PHONE
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.commons.models.SimpleContact
import com.omnioone.messages.activities.ConversationDetailsActivity
import com.omnioone.messages.helpers.THREAD_ID
import java.util.Locale

fun BaseSimpleActivity.dialNumber(phoneNumber: String, callback: (() -> Unit)? = null) {
    hideKeyboard()
    handlePermission(PERMISSION_CALL_PHONE) {
        val action = if (it) Intent.ACTION_CALL else Intent.ACTION_DIAL
        Intent(action).apply {
            data = Uri.fromParts("tel", phoneNumber, null)

            try {
                startActivity(this)
                callback?.invoke()
            } catch (_: ActivityNotFoundException) {
                toast(com.omnioone.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}

fun Activity.launchViewIntent(uri: Uri, mimetype: String, filename: String) {
    Intent().apply {
        action = Intent.ACTION_VIEW
        setDataAndType(uri, mimetype.lowercase(Locale.getDefault()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            hideKeyboard()
            startActivity(this)
        } catch (_: ActivityNotFoundException) {
            val newMimetype = filename.getMimeType()
            if (newMimetype.isNotEmpty() && mimetype != newMimetype) {
                launchViewIntent(uri, newMimetype, filename)
            } else {
                toast(com.omnioone.commons.R.string.no_app_found)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Activity.startContactDetailsIntent(contact: SimpleContact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    if (
        contact.rawId > 1000000 &&
        contact.contactId > 1000000 &&
        contact.rawId == contact.contactId &&
        (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    ) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            setPackage(
                if (isPackageInstalled(simpleContacts)) {
                    simpleContacts
                } else {
                    simpleContactsDebug
                }
            )

            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )

            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey = SimpleContactsHelper(this)
                .getContactLookupKey(
                    contactId = (contact).rawId.toString()
                )

            val publicUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey
            )

            runOnUiThread {
                launchViewContactIntent(publicUri)
            }
        }
    }
}

fun Activity.launchConversationDetails(threadId: Long) {
    Intent(this, ConversationDetailsActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        startActivity(this)
    }
}
