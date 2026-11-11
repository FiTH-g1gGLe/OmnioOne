package com.omnioone.app.messaging

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.omnioone.app.messaging.messagesConfig as config
import org.json.JSONObject
import java.io.File

/** Omnio One: which chats are archived. Kept by Omnio itself, so it works on every phone. */
object OmnioArchive {

    private const val KEY = "archived_threads"

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    fun ids(context: Context): Set<Long> {
        migrate(context)
        return prefs(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun set(context: Context, threadId: Long, archived: Boolean) {
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        val id = threadId.toString()
        prefs(context).edit().putStringSet(KEY, if (archived) current + id else current - id).apply()
        OmnioMessagesBus.changed()
    }

    /** A new message brings an archived chat back, unless "Keep chats archived" is on. */
    fun onNewMessage(context: Context, threadId: Long) {
        if (context.config.keepConversationsArchived) return
        if (threadId in ids(context)) set(context, threadId, false)
    }

    /** First run: bring over chats archived in the previous Messages screens. */
    private fun migrate(context: Context) {
        if (prefs(context).getBoolean("archive_migrated", false)) return
        val old = OmnioOldData.archivedThreads(context).map { it.toString() }.toSet()
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(KEY, current + old).putBoolean("archive_migrated", true).apply()
    }
}

/**
 * Omnio One recycle bin. Deleted messages are hidden for 30 days (so they can be restored),
 * then deleted for good. Kept in Omnio's own file.
 */
object OmnioRecycleBin {

    const val DAYS = 30
    private const val FILE = "omnio_recycle_bin.json"
    private val lock = Any()

    /** One binned message: [key] is "s:id" for texts, "m:id" for picture messages. */
    data class Entry(val key: String, val threadId: Long, val deletedAt: Long) {
        val id get() = key.substringAfter(":").toLong()
        val isMms get() = key.startsWith("m:")
    }

    fun keyOf(isMms: Boolean, id: Long) = (if (isMms) "m:" else "s:") + id

    fun isOn(context: Context) = context.config.useRecycleBin

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun entries(context: Context): List<Entry> = synchronized(lock) {
        migrate(context)
        try {
            val json = JSONObject(file(context).takeIf { it.exists() }?.readText() ?: "{}")
            json.keys().asSequence().mapNotNull { key ->
                json.optJSONObject(key)?.let { Entry(key, it.optLong("thread"), it.optLong("at")) }
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun keys(context: Context): Set<String> = entries(context).map { it.key }.toSet()

    private fun save(context: Context, entries: Collection<Entry>) = synchronized(lock) {
        val json = JSONObject()
        entries.forEach { json.put(it.key, JSONObject().put("thread", it.threadId).put("at", it.deletedAt)) }
        file(context).writeText(json.toString())
    }

    // ---- deleting ----

    /** Deletes messages: into the bin if it's switched on, otherwise for good. */
    fun delete(context: Context, messages: List<OmnioMessageStore.Message>) {
        if (messages.isEmpty()) return
        if (isOn(context)) {
            val now = System.currentTimeMillis()
            val current = entries(context).associateBy { it.key }.toMutableMap()
            messages.forEach { current[keyOf(it.isMms, it.id)] = Entry(keyOf(it.isMms, it.id), it.threadId, now) }
            save(context, current.values)
        } else {
            messages.forEach { erase(context, it.isMms, it.id) }
        }
        OmnioMessagesBus.changed()
    }

    /** Deletes a whole chat. */
    fun deleteChat(context: Context, threadId: Long) {
        if (isOn(context)) {
            delete(context, OmnioMessageStore.messages(context, threadId, limit = 100_000))
        } else {
            try {
                context.contentResolver.delete(Sms.CONTENT_URI, "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
                context.contentResolver.delete(Mms.CONTENT_URI, "${Mms.THREAD_ID} = ?", arrayOf(threadId.toString()))
            } catch (ignored: Exception) {
            }
            OmnioNotifications.cancel(context, threadId)
            OmnioMessagesBus.changed()
        }
    }

    // ---- restoring and emptying ----

    fun restore(context: Context, keys: Collection<String>) {
        val remove = keys.toSet()
        save(context, entries(context).filter { it.key !in remove })
        OmnioMessagesBus.changed()
    }

    fun restoreChat(context: Context, threadId: Long) = restore(context, entries(context).filter { it.threadId == threadId }.map { it.key })

    /** Deletes binned messages for good. */
    fun purge(context: Context, keys: Collection<String>) {
        val remove = keys.toSet()
        val all = entries(context)
        all.filter { it.key in remove }.forEach { erase(context, it.isMms, it.id) }
        save(context, all.filter { it.key !in remove })
        OmnioMessagesBus.changed()
    }

    fun purgeChat(context: Context, threadId: Long) = purge(context, entries(context).filter { it.threadId == threadId }.map { it.key })

    fun empty(context: Context) = purge(context, keys(context))

    /** Clears anything older than [DAYS] days. Cheap to call often. */
    fun tidy(context: Context) {
        val cutoff = System.currentTimeMillis() - DAYS * 24 * 3600 * 1000L
        val old = entries(context).filter { it.deletedAt < cutoff }
        if (old.isNotEmpty()) purge(context, old.map { it.key })
    }

    private fun erase(context: Context, isMms: Boolean, id: Long) {
        try {
            context.contentResolver.delete(ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id), null, null)
        } catch (ignored: Exception) {
        }
    }

    /** First run: bring over messages binned in the previous Messages screens. */
    private fun migrate(context: Context) {
        val prefs = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bin_migrated", false)) return
        prefs.edit().putBoolean("bin_migrated", true).apply()
        try {
            val old = OmnioOldData.binned(context)
            if (old.isEmpty()) return
            val now = System.currentTimeMillis()
            val json = JSONObject(file(context).takeIf { it.exists() }?.readText() ?: "{}")
            old.forEach { (id, isMms, thread) -> json.put(keyOf(isMms, id), JSONObject().put("thread", thread).put("at", now)) }
            file(context).writeText(json.toString())
        } catch (ignored: Exception) {
        }
    }
}
