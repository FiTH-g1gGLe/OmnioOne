package com.omnioone.app.helpers

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import com.omnioone.app.extensions.config
import com.omnioone.app.core.SORT_BY_DATE_CREATED
import com.omnioone.app.core.SORT_BY_SURNAME
import com.omnioone.app.core.SORT_DESCENDING
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Omnio One's own contacts reader: everyone in your phone's contacts who has a number,
 * sorted and named the way you've chosen.
 */
object OmnioPeople {

    data class Number(val value: String, val label: String, val isPrimary: Boolean)

    data class Person(
        val contactId: Int,
        val rawId: Int,
        val lookupKey: String,
        val name: String,
        val surnameFirst: String,
        val photo: String,
        val starred: Boolean,
        val numbers: List<Number>,
        val emails: List<String>,
        val nickname: String,
        val company: String,
        val jobTitle: String,
    ) {
        val mainNumber get() = (numbers.firstOrNull { it.isPrimary } ?: numbers.firstOrNull())?.value.orEmpty()
        val firstName get() = name.trim().substringBefore(" ")

        /** The letter this person is filed under in the list ("#" for numbers and symbols). */
        fun letter(sortName: String): String {
            val first = plain(sortName).trim().firstOrNull()?.uppercaseChar() ?: return "#"
            return if (first.isLetter()) first.toString() else "#"
        }
    }

    fun plain(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "")

    private class Builder(val contactId: Int, val rawId: Int, val lookup: String, val name: String, val alt: String, val photo: String, val starred: Boolean) {
        val numbers = ArrayList<Number>()
        val emails = ArrayList<String>()
        var nickname = ""
        var company = ""
        var jobTitle = ""
    }

    /**
     * Everyone with at least one number, skipping accounts hidden with the contacts filter
     * ([ignored] holds "account name:account type"). Run on a background thread.
     */
    fun load(context: Context, ignored: Set<String>): List<Person> {
        val people = LinkedHashMap<Int, Builder>()
        try {
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(
                    Data.CONTACT_ID, Data.RAW_CONTACT_ID, Data.LOOKUP_KEY, Data.DISPLAY_NAME_PRIMARY, Data.DISPLAY_NAME_ALTERNATIVE,
                    Data.PHOTO_THUMBNAIL_URI, Data.STARRED, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.IS_PRIMARY,
                    ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE, Organization.TITLE,
                ),
                "${Data.MIMETYPE} IN (?, ?, ?, ?)",
                arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (ignored.isNotEmpty()) {
                        val source = "${cursor.getString(12).orEmpty()}:${cursor.getString(13).orEmpty()}"
                        if (source in ignored) continue
                    }
                    val contactId = cursor.getInt(0)
                    val person = people.getOrPut(contactId) {
                        Builder(
                            contactId, cursor.getInt(1), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
                            cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(), cursor.getInt(6) == 1
                        )
                    }
                    val value = cursor.getString(8).orEmpty()
                    when (cursor.getString(7)) {
                        Phone.CONTENT_ITEM_TYPE -> if (value.isNotBlank() && person.numbers.none { it.value.filter(Char::isDigit) == value.filter(Char::isDigit) }) {
                            val label = Phone.getTypeLabel(context.resources, cursor.getInt(9), cursor.getString(10).orEmpty()).toString()
                            person.numbers += Number(value, label, cursor.getInt(11) == 1)
                        }
                        Email.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) person.emails += value.lowercase()
                        Nickname.CONTENT_ITEM_TYPE -> person.nickname = value
                        Organization.CONTENT_ITEM_TYPE -> {
                            person.company = value
                            person.jobTitle = cursor.getString(14).orEmpty()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return people.values.filter { it.numbers.isNotEmpty() }.map {
            val name = it.name.ifBlank { it.numbers.first().value }
            Person(
                it.contactId, it.rawId, it.lookup, name, it.alt.replace(", ", " ").ifBlank { name }, it.photo, it.starred,
                it.numbers, it.emails, it.nickname, it.company, it.jobTitle
            )
        }
    }

    /** The name to show: "First Last", or "Last First" when that's switched on. */
    fun displayName(person: Person, surnameFirst: Boolean) = if (surnameFirst) person.surnameFirst else person.name

    /** Sorted by the chosen sort order; names that don't start with a letter go last. */
    fun sort(people: List<Person>, sorting: Int): List<Person> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val sorted = if (sorting and SORT_BY_DATE_CREATED != 0) {
            people.sortedBy { it.contactId }
        } else {
            val bySurname = sorting and SORT_BY_SURNAME != 0
            people.sortedWith { a, b ->
                val nameA = if (bySurname) a.surnameFirst else a.name
                val nameB = if (bySurname) b.surnameFirst else b.name
                val letterA = plain(nameA).firstOrNull()?.isLetter() == true
                val letterB = plain(nameB).firstOrNull()?.isLetter() == true
                if (letterA != letterB) (if (letterA) -1 else 1) else collator.compare(nameA, nameB)
            }
        }
        return if (sorting and SORT_DESCENDING != 0) sorted.reversed() else sorted
    }

    /** The name the list is sorted (and lettered) by. */
    fun sortName(person: Person, sorting: Int) = if (sorting and SORT_BY_SURNAME != 0) person.surnameFirst else person.name

    /**
     * Your favourites, in the same order everywhere (the Favourites tab and the row above
     * Contacts): your dragged order if you've made one, otherwise the chosen sort order.
     */
    fun favourites(context: Context, people: List<Person>): List<Person> {
        val config = context.config
        val picked = people.filter { if (OmnioFavourites.isLoaded) OmnioFavourites.isFavourite(it.contactId) else it.starred }
        if (config.isCustomOrderSelected) {
            val order = try {
                com.google.gson.Gson().fromJson(config.favoritesContactsOrder, Array<String>::class.java)?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            if (order.isNotEmpty()) {
                val rank = order.withIndex().associate { it.value to it.index }
                return picked.sortedBy { rank[it.contactId.toString()] ?: Int.MAX_VALUE }
            }
        }
        return sort(picked, config.sorting)
    }

    /** Does this person match what's typed in search? */
    fun matches(person: Person, query: String): Boolean {
        if (query.isBlank()) return true
        val text = plain(query.trim())
        if (plain(person.name).contains(text, true) || plain(person.surnameFirst).contains(text, true)) return true
        if (person.nickname.contains(text, true) || person.company.contains(text, true) || person.jobTitle.contains(text, true)) return true
        if (person.emails.any { it.contains(text, true) }) return true
        val digits = query.filter { it.isDigit() }
        return digits.length >= 2 && query.trim().all { it.isDigit() || it == ' ' || it == '+' || it == '-' } &&
            person.numbers.any { it.value.filter(Char::isDigit).contains(digits) }
    }
}
