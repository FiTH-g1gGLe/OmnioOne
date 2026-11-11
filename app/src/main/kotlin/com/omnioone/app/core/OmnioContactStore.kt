package com.omnioone.app.core

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioContactPhoto

// ---------------------------------------------------------------- what a contact holds

data class PhoneNumber(var value: String, var type: Int, var label: String, var normalizedNumber: String, var isPrimary: Boolean = false)
data class Email(var value: String, var type: Int, var label: String)
data class Address(var value: String, var type: Int, var label: String)
data class Event(var value: String, var type: Int)
data class Organization(var company: String, var jobPosition: String) {
    fun isEmpty() = company.isEmpty() && jobPosition.isEmpty()
}
data class Im(var value: String, var type: Int, var label: String)
data class ContactGroup(var id: Long, var title: String)

/** Where a contact is saved: a Google account, the phone itself, and so on. */
data class ContactSource(var name: String, var type: String, var publicName: String, var count: Int = 0) {
    /** How hidden sources are remembered in settings ("name:type"). */
    fun getFullIdentifier() = "$name:$type"
}

/** One contact entry (a "raw contact") with everything Omnio shows and edits. */
data class Contact(
    var id: Int,
    var prefix: String = "",
    var firstName: String = "",
    var middleName: String = "",
    var surname: String = "",
    var suffix: String = "",
    var nickname: String = "",
    var photoUri: String = "",
    var phoneNumbers: ArrayList<PhoneNumber> = arrayListOf(),
    var emails: ArrayList<Email> = arrayListOf(),
    var addresses: ArrayList<Address> = arrayListOf(),
    var events: ArrayList<Event> = arrayListOf(),
    var source: String = "",
    var sourceType: String = "",
    var starred: Int = 0,
    var contactId: Int,
    var thumbnailUri: String = "",
    var photo: Bitmap? = null,
    var notes: String = "",
    var groups: ArrayList<ContactGroup> = arrayListOf(),
    var organization: Organization = Organization("", ""),
    var websites: ArrayList<String> = arrayListOf(),
    var IMs: ArrayList<Im> = arrayListOf(),
    var ringtone: String? = "",
) {
    val rawId get() = id
    val name get() = getNameToDisplay()
    val birthdays get() = ArrayList(events.filter { it.type == CommonDataKinds.Event.TYPE_BIRTHDAY }.map { it.value })

    /** Their name, or failing that their company, email or number. */
    fun getNameToDisplay(): String {
        val parts = if (startWithSurname) listOf(prefix, surname, firstName, middleName, suffix) else listOf(prefix, firstName, middleName, surname, suffix)
        val full = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return full.ifEmpty { nickname.trim() }
            .ifEmpty { organization.company.trim() }
            .ifEmpty { emails.firstOrNull()?.value?.trim().orEmpty() }
            .ifEmpty { phoneNumbers.firstOrNull()?.value?.trim().orEmpty() }
    }

    /** Contacts kept only inside the original app are no longer supported, so none are private. */
    fun isPrivate() = false

    companion object {
        /** Show "Surname First" order. */
        var startWithSurname = false
        /** Kept for older screens that set it; Omnio sorts with its own people list. */
        var sorting = 0
    }
}

// ---------------------------------------------------------------- reading, saving, deleting

/** Omnio One's contacts engine: reads, saves and deletes contacts in Android's address book. */
class OmnioContactStore(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    private val managedKinds = listOf(
        CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
        CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        CommonDataKinds.Im.CONTENT_ITEM_TYPE,
        CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
    )

    // ---- reading ----

    /** Everything about one contact entry, by its raw id. */
    fun getContactWithId(rawId: Int, @Suppress("UNUSED_PARAMETER") isLocalPrivate: Boolean = false): Contact? {
        if (rawId <= 0) return null
        var contactId = 0
        var account = ""
        var accountType = ""
        resolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId.toLong()),
            arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE), null, null, null
        )?.use {
            if (!it.moveToFirst()) return null
            contactId = it.getInt(0)
            account = it.getString(1).orEmpty()
            accountType = it.getString(2).orEmpty()
        } ?: return null

        val contact = Contact(id = rawId, contactId = contactId, source = account, sourceType = accountType)
        resolver.query(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId.toLong()),
            arrayOf(Contacts.STARRED, Contacts.CUSTOM_RINGTONE, Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI), null, null, null
        )?.use {
            if (it.moveToFirst()) {
                contact.starred = it.getInt(0)
                contact.ringtone = it.getString(1)
                contact.photoUri = it.getString(2).orEmpty()
                contact.thumbnailUri = it.getString(3).orEmpty()
            }
        }

        val groupTitles = groupTitles()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.IS_PRIMARY),
            "${Data.RAW_CONTACT_ID} = ?", arrayOf(rawId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val data1 = c.getString(1).orEmpty()
                val type = c.getInt(2)
                val label = c.getString(3).orEmpty()
                when (c.getString(0)) {
                    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        // DATA2 given, DATA3 family, DATA4 prefix, DATA5 middle, DATA6 suffix
                        contact.firstName = c.getString(2).orEmpty()
                        contact.surname = c.getString(3).orEmpty()
                        contact.prefix = c.getString(4).orEmpty()
                        contact.middleName = c.getString(5).orEmpty()
                        contact.suffix = c.getString(6).orEmpty()
                        if (contact.firstName.isEmpty() && contact.surname.isEmpty() && data1.isNotEmpty()) contact.firstName = data1
                    }
                    CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> contact.nickname = data1
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.phoneNumbers.add(
                        PhoneNumber(data1, type, label, c.getString(4).orEmpty().ifEmpty { android.telephony.PhoneNumberUtils.normalizeNumber(data1) ?: data1 }, c.getInt(7) == 1)
                    )
                    CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.emails.add(Email(data1, type, label))
                    CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.addresses.add(Address(data1, type, label))
                    CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.events.add(Event(data1, type))
                    CommonDataKinds.Note.CONTENT_ITEM_TYPE -> contact.notes = data1
                    CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> contact.organization = Organization(data1, c.getString(4).orEmpty())
                    CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.websites.add(data1)
                    CommonDataKinds.Im.CONTENT_ITEM_TYPE -> if (data1.isNotBlank()) contact.IMs.add(Im(data1, c.getInt(5), c.getString(6).orEmpty()))
                    CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> data1.toLongOrNull()?.let { contact.groups.add(ContactGroup(it, groupTitles[it].orEmpty())) }
                }
            }
        } ?: return null
        return contact
    }

    private fun groupTitles(): Map<Long, String> = try {
        resolver.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE), null, null, null)?.use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1).orEmpty() else null }.toMap()
        }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    /** The lookup key Android uses to open a contact in other apps. */
    fun getContactLookupKey(rawId: String): String {
        val contactId = resolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId.toLongOrNull() ?: return ""),
            arrayOf(RawContacts.CONTACT_ID), null, null, null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return ""
        return resolver.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), arrayOf(Contacts.LOOKUP_KEY), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" } ?: ""
    }

    // ---- accounts ----

    /** Every place contacts are kept on this phone, with how many each holds. */
    fun getContactSources(): List<ContactSource> {
        val found = LinkedHashMap<String, ContactSource>()
        try {
            resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE), "${RawContacts.DELETED} = 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val source = ContactSource(c.getString(0).orEmpty(), c.getString(1).orEmpty(), "")
                    val key = source.getFullIdentifier()
                    val existing = found[key]
                    if (existing == null) found[key] = source.copy(publicName = publicName(source.name, source.type), count = 1) else existing.count++
                }
            }
        } catch (ignored: Exception) {
        }
        writableAccounts().forEach { source ->
            found.getOrPut(source.getFullIdentifier()) { source }
        }
        return found.values.toList()
    }

    /** Places a new contact can be saved: accounts that sync contacts both ways, then the phone itself. */
    fun getSaveableContactSources(): List<ContactSource> =
        (writableAccounts() + ContactSource("", "", context.getString(R.string.omnio_phone_storage))).distinctBy { it.getFullIdentifier() }

    fun getSaveableContactSources(callback: (ArrayList<ContactSource>) -> Unit) = ensureBackgroundThread { callback(ArrayList(getSaveableContactSources())) }

    private fun writableAccounts(): List<ContactSource> = try {
        val types = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
        AccountManager.get(context).accounts.filter { it.type in types }.map { ContactSource(it.name, it.type, publicName(it.name, it.type)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun publicName(name: String, type: String) = when {
        name.isEmpty() && type.isEmpty() -> context.getString(R.string.omnio_phone_storage)
        type == "com.google" -> name
        type.contains("whatsapp") -> "WhatsApp"
        type.contains("telegram") -> "Telegram"
        type.contains("signal") || type.contains("securesms") -> "Signal"
        name.isEmpty() -> context.getString(R.string.omnio_phone_storage)
        else -> name
    }

    // ---- saving ----

    /** Adds a new contact. Returns true if it was saved. */
    fun insertContact(contact: Contact): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_NAME, contact.source.ifEmpty { null })
            .withValue(RawContacts.ACCOUNT_TYPE, contact.sourceType.ifEmpty { null })
            .build()
        addDetails(ops, contact, rawId = null)
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = results.firstOrNull()?.uri?.let { ContentUris.parseId(it) } ?: return false
        if (contact.starred == 1) setStarred(rawId, true)
        if (contact.photoUri.isNotEmpty()) OmnioContactPhoto.set(context, rawId, Uri.parse(contact.photoUri))
        return true
    }

    /**
     * Saves changes to a contact entry. Names, numbers, emails, addresses, dates, notes, company,
     * links, chat accounts and groups are rewritten; anything else on the entry (chat-app rows,
     * the photo) is left alone. [photoUpdateStatus] is ignored: photos are saved separately.
     */
    fun updateContact(contact: Contact, @Suppress("UNUSED_PARAMETER") photoUpdateStatus: Int = PHOTO_UNCHANGED): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${managedKinds.joinToString(",") { "?" }})",
                arrayOf(contact.id.toString()) + managedKinds
            ).build()
        addDetails(ops, contact, rawId = contact.id.toLong())
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        setStarred(contact.id.toLong(), contact.starred == 1)
        return true
    }

    private fun addDetails(ops: ArrayList<ContentProviderOperation>, contact: Contact, rawId: Long?) {
        fun row(kind: String) = ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
            if (rawId == null) withValueBackReference(Data.RAW_CONTACT_ID, 0) else withValue(Data.RAW_CONTACT_ID, rawId)
            withValue(Data.MIMETYPE, kind)
        }

        ops += row(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.StructuredName.PREFIX, contact.prefix)
            .withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
            .withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middleName)
            .withValue(CommonDataKinds.StructuredName.FAMILY_NAME, contact.surname)
            .withValue(CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
            .build()
        if (contact.nickname.isNotBlank()) ops += row(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Nickname.NAME, contact.nickname).build()
        contact.phoneNumbers.forEach {
            ops += row(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Phone.NUMBER, it.value)
                .withValue(CommonDataKinds.Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                .withValue(CommonDataKinds.Phone.TYPE, it.type)
                .withValue(CommonDataKinds.Phone.LABEL, it.label)
                .withValue(Data.IS_PRIMARY, if (it.isPrimary) 1 else 0)
                .build()
        }
        contact.emails.forEach {
            ops += row(CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Email.ADDRESS, it.value)
                .withValue(CommonDataKinds.Email.TYPE, it.type)
                .withValue(CommonDataKinds.Email.LABEL, it.label)
                .build()
        }
        contact.addresses.forEach {
            ops += row(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, it.value)
                .withValue(CommonDataKinds.StructuredPostal.TYPE, it.type)
                .withValue(CommonDataKinds.StructuredPostal.LABEL, it.label)
                .build()
        }
        contact.events.forEach {
            ops += row(CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Event.START_DATE, it.value)
                .withValue(CommonDataKinds.Event.TYPE, it.type)
                .build()
        }
        if (contact.notes.isNotBlank()) ops += row(CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Note.NOTE, contact.notes).build()
        if (!contact.organization.isEmpty()) ops += row(CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
            .withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
            .withValue(CommonDataKinds.Organization.TYPE, CommonDataKinds.Organization.TYPE_WORK)
            .build()
        contact.websites.distinct().forEach {
            ops += row(CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Website.URL, it)
                .withValue(CommonDataKinds.Website.TYPE, CommonDataKinds.Website.TYPE_HOMEPAGE)
                .build()
        }
        contact.IMs.forEach {
            ops += row(CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Im.DATA, it.value)
                .withValue(CommonDataKinds.Im.PROTOCOL, it.type)
                .withValue(CommonDataKinds.Im.CUSTOM_PROTOCOL, it.label)
                .build()
        }
        contact.groups.forEach {
            ops += row(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, it.id)
                .build()
        }
    }

    private fun setStarred(rawId: Long, starred: Boolean) = try {
        resolver.update(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            android.content.ContentValues().apply { put(RawContacts.STARRED, if (starred) 1 else 0) }, null, null
        )
    } catch (e: Exception) {
        0
    }

    // ---- deleting ----

    /** Deletes the whole contact (every linked entry), in the background. */
    fun deleteContact(contact: Contact, @Suppress("UNUSED_PARAMETER") isPartOfGroup: Boolean = false, callback: (Boolean) -> Unit = {}) = ensureBackgroundThread {
        val ok = try {
            val selection = if (contact.contactId > 0) "${RawContacts.CONTACT_ID} = ?" else "${RawContacts._ID} = ?"
            val id = if (contact.contactId > 0) contact.contactId else contact.id
            resolver.delete(RawContacts.CONTENT_URI, selection, arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
        callback(ok)
    }
}
