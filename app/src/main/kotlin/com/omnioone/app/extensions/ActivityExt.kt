package com.omnioone.app.extensions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.omnioone.commons.extensions.isPackageInstalled
import com.omnioone.commons.extensions.launchActivityIntent
import com.omnioone.commons.extensions.launchViewContactIntent
import com.omnioone.commons.helpers.CONTACT_ID
import com.omnioone.commons.helpers.FIRST_CONTACT_ID
import com.omnioone.commons.helpers.IS_PRIVATE
import com.omnioone.commons.helpers.ON_CLICK_CALL_CONTACT
import com.omnioone.commons.helpers.ON_CLICK_VIEW_CONTACT
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.commons.models.contacts.Contact
import com.omnioone.app.activities.SimpleActivity

fun SimpleActivity.handleGenericContactClick(contact: Contact) {
    when (config.onContactClick) {
        ON_CLICK_CALL_CONTACT -> startCallWithConfirmationCheck(contact)
        ON_CLICK_VIEW_CONTACT -> startContactDetailsIntent(contact)
    }
}

// Omnio One: new contacts are made in Omnio's own editor
fun SimpleActivity.launchCreateNewContactIntent(number: String? = null) {
    startActivity(Intent(this, com.omnioone.app.activities.OmnioEditContactActivity::class.java).apply {
        if (number != null) putExtra(com.omnioone.app.activities.OmnioEditContactActivity.EXTRA_NUMBER, number)
    })
}

// handle private contacts differently, only Simple Contacts Pro can open them
/** Omnio One: contacts now open in our own contact page. */
fun Activity.startContactDetailsIntent(contact: Contact) {
    Intent(this, com.omnioone.app.activities.OmnioContactActivity::class.java).apply {
        putExtra(com.omnioone.app.activities.OmnioContactActivity.EXTRA_CONTACT_ID, contact.rawId)
        startActivity(this)
    }
}

/** Opens the contact in the phone's contacts app, used by the Edit button. */
fun Activity.openContactInSystemApp(contact: Contact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    val isPrivateContact = contact.rawId > FIRST_CONTACT_ID
            && contact.contactId > FIRST_CONTACT_ID
            && contact.rawId == contact.contactId
            && (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    if (isPrivateContact) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` =
                if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey =
                SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
            val publicUri =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            runOnUiThread {
                launchViewContactIntent(publicUri)
            }
        }
    }
}
