package com.omnioone.app.messaging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

// What opens a chat (shared by Omnio screens, notifications and shortcuts)
const val THREAD_ID = "thread_id"
const val THREAD_TITLE = "thread_title"
const val THREAD_TEXT = "thread_text"
const val THREAD_NUMBER = "thread_number"
const val THREAD_ATTACHMENT_URIS = "thread_attachment_uris"

// What message notifications show on the lock screen
const val LOCK_SCREEN_SENDER_MESSAGE = 1
const val LOCK_SCREEN_SENDER = 2
const val LOCK_SCREEN_NOTHING = 3

/** Omnio One Messages settings. Stored in the app's main settings file, so earlier choices carry over. */
class OmnioMessagesConfig(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("Prefs", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    private fun setFlag(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    var showCharacterCounter: Boolean
        get() = flag("show_character_counter")
        set(value) = setFlag("show_character_counter", value)

    var sendOnEnter: Boolean
        get() = flag("send_on_enter")
        set(value) = setFlag("send_on_enter", value)

    var enableDeliveryReports: Boolean
        get() = flag("enable_delivery_reports")
        set(value) = setFlag("enable_delivery_reports", value)

    var sendLongMessageMMS: Boolean
        get() = flag("send_long_message_mms")
        set(value) = setFlag("send_long_message_mms", value)

    var sendGroupMessageMMS: Boolean
        get() = flag("send_group_message_mms")
        set(value) = setFlag("send_group_message_mms", value)

    var useRecycleBin: Boolean
        get() = flag("use_recycle_bin")
        set(value) = setFlag("use_recycle_bin", value)

    var keepConversationsArchived: Boolean
        get() = flag("keep_conversations_archived")
        set(value) = setFlag("keep_conversations_archived", value)

    var lockScreenVisibilitySetting: Int
        get() = prefs.getInt("lock_screen_visibility", LOCK_SCREEN_SENDER_MESSAGE)
        set(value) = prefs.edit().putInt("lock_screen_visibility", value).apply()

    /** Photos bigger than this (bytes) are shrunk before sending; -1 = no limit. */
    var mmsFileSizeLimit: Long
        get() = prefs.getLong("mms_file_size_limit", 204_800L)
        set(value) = prefs.edit().putLong("mms_file_size_limit", value).apply()

    var blockedKeywords: Set<String>
        get() = prefs.getStringSet("blocked_keywords", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("blocked_keywords", value).apply()

    fun addBlockedKeyword(word: String) {
        blockedKeywords = blockedKeywords + word
    }

    fun removeBlockedKeyword(word: String) {
        blockedKeywords = blockedKeywords - word
    }

    /** Chats given their own notification sound (each has its own Android notification category). */
    val customNotifications: Set<String>
        get() = prefs.getStringSet("custom_notifications", emptySet()).orEmpty().toSet()
}

val Context.messagesConfig get() = OmnioMessagesConfig(this)

/** Tells open Messages screens that chats changed (archived, deleted, restored…) so they refresh. */
object OmnioMessagesBus {

    private val listeners = ArrayList<WeakReference<() -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun listen(listener: () -> Unit) {
        listeners.removeAll { it.get() == null }
        listeners += WeakReference(listener)
    }

    @Synchronized
    fun changed() {
        val alive = listeners.mapNotNull { it.get() }
        main.post { alive.forEach { it() } }
    }
}

/** Drafts you started typing, per chat. */
object OmnioDrafts {

    private fun prefs(context: Context) = context.getSharedPreferences("omnio_drafts", Context.MODE_PRIVATE)

    fun get(context: Context, threadId: Long): String {
        OmnioOldData.migrateDrafts(context)
        return prefs(context).getString(threadId.toString(), "").orEmpty()
    }

    fun save(context: Context, threadId: Long, text: String) = prefs(context).edit().putString(threadId.toString(), text).apply()

    fun delete(context: Context, threadId: Long) = prefs(context).edit().remove(threadId.toString()).apply()
}

/**
 * Reads what the previous Messages screens saved (archived chats, recycle bin, drafts), once,
 * so nothing is lost now that Omnio keeps these itself.
 */
object OmnioOldData {

    private fun open(context: Context): SQLiteDatabase? = try {
        val file = context.getDatabasePath("conversations.db")
        if (file.exists()) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) else null
    } catch (e: Exception) {
        null
    }

    private fun <T> read(context: Context, sql: String, row: (android.database.Cursor) -> T): List<T> = try {
        open(context)?.use { db ->
            db.rawQuery(sql, null).use { cursor ->
                val out = ArrayList<T>()
                while (cursor.moveToNext()) out += row(cursor)
                out
            }
        }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    fun archivedThreads(context: Context): Set<Long> =
        read(context, "SELECT thread_id FROM conversations WHERE archived = 1") { it.getLong(0) }.toSet()

    /** Binned messages: (id, is picture message, chat). */
    fun binned(context: Context): List<Triple<Long, Boolean, Long>> =
        read(context, "SELECT m.id, m.is_mms, m.thread_id FROM recycle_bin_messages r JOIN messages m ON m.id = r.id") {
            Triple(it.getLong(0), it.getInt(1) == 1, it.getLong(2))
        }

    fun migrateDrafts(context: Context) {
        val flags = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)
        if (flags.getBoolean("drafts_migrated", false)) return
        flags.edit().putBoolean("drafts_migrated", true).apply()
        val drafts = read(context, "SELECT thread_id, body FROM drafts") { it.getLong(0) to it.getString(1).orEmpty() }
        if (drafts.isEmpty()) return
        val editor = context.getSharedPreferences("omnio_drafts", Context.MODE_PRIVATE).edit()
        drafts.filter { it.second.isNotBlank() }.forEach { editor.putString(it.first.toString(), it.second) }
        editor.apply()
    }
}
