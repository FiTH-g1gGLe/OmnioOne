package org.fossify.phone.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Omnio One favourites, written from scratch.
 *
 * - One in-memory list of favourite contact ids that every screen reads from.
 * - Changing it updates that list straight away and tells every screen on the spot,
 *   then saves the change to the phone's contacts (STARRED) in the background.
 * - Saving and re-reading run one after another on a single worker thread, so a re-read can
 *   never overtake a save and undo what you just tapped.
 */
object OmnioFavourites {

    private val ids = LinkedHashSet<Int>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var isLoaded = false
        private set

    fun isFavourite(contactId: Int): Boolean = synchronized(ids) { contactId in ids }

    fun count(): Int = synchronized(ids) { ids.size }

    /** Called on the main thread whenever the favourites change. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Add or remove a favourite: every screen updates now, the phone's contacts follow. */
    fun set(context: Context, contactId: Int, favourite: Boolean) {
        val changed = synchronized(ids) { if (favourite) ids.add(contactId) else ids.remove(contactId) }
        if (changed) notifyChanged()
        val appContext = context.applicationContext
        worker.execute {
            try {
                val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (favourite) 1 else 0) }
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
                appContext.contentResolver.update(uri, values, null, null)
            } catch (ignored: Exception) {
            }
        }
    }

    fun toggle(context: Context, contactId: Int): Boolean {
        val makeFavourite = !isFavourite(contactId)
        set(context, contactId, makeFavourite)
        return makeFavourite
    }

    /** Re-read the phone's starred contacts (picks up changes made in other apps). */
    fun reload(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            val fresh = LinkedHashSet<Int>()
            try {
                appContext.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.STARRED} = 1",
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) fresh.add(cursor.getInt(0))
                }
            } catch (e: Exception) {
                return@execute
            }
            val changed = synchronized(ids) {
                if (ids == fresh) {
                    false
                } else {
                    ids.clear()
                    ids.addAll(fresh)
                    true
                }
            }
            val firstLoad = !isLoaded
            isLoaded = true
            if (changed || firstLoad) notifyChanged()
        }
    }

    /** For private (Omnio-only) contacts, which keep their own star. */
    fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
