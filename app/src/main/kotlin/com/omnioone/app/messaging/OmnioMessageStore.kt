package com.omnioone.app.messaging

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import com.omnioone.app.helpers.OmnioCallLog
import com.omnioone.messages.extensions.getConversations
import com.omnioone.messages.extensions.getUnreadCountsByThread

/**
 * Omnio One's own message reader (stage 1 of the Messages rewrite).
 *
 * Reads conversations, texts and picture messages straight from Android's message store,
 * works out who each conversation is with, and adds Omnio's extras (pinned, archived).
 * It only reads: nothing here changes or deletes a message. Run on a background thread.
 */
object OmnioMessageStore {

    data class Person(val address: String, val name: String?, val photo: String, val contactId: Long?) {
        val label get() = name?.ifBlank { null } ?: address
    }

    data class Conversation(
        val threadId: Long,
        val people: List<Person>,
        val snippet: String,
        val date: Long,
        val unread: Int,
        val hasAttachment: Boolean,
        val isPinned: Boolean,
        val isArchived: Boolean,
    ) {
        val isGroup get() = people.size > 1
        val title get() = people.joinToString(", ") { if (isGroup) it.label.substringBefore(" ") else it.label }.ifEmpty { "?" }
        val photo get() = if (isGroup) "" else people.firstOrNull()?.photo.orEmpty()
    }

    data class Part(val id: Long, val mime: String, val text: String?, val uri: Uri)

    data class Message(
        val id: Long,
        val threadId: Long,
        val isMms: Boolean,
        val incoming: Boolean,
        val address: String,
        val body: String,
        val date: Long,
        val read: Boolean,
        val failed: Boolean,
        val pending: Boolean,
        val parts: List<Part> = emptyList(),
    ) {
        /** Unique across texts and picture messages (they share id numbers). */
        val key get() = if (isMms) -id else id
    }

    private val MMS_SMS_CONVERSATIONS: Uri get() = Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL_ADDRESSES: Uri get() = Uri.parse("content://mms-sms/canonical-addresses")
    private const val MMS_FROM = 137

    /** Picture-message dates are stored in seconds, texts in milliseconds. */
    fun toMillis(date: Long): Long = if (date in 1 until 100_000_000_000L) date * 1000 else date

    /** "12 34 56" style recipient id lists. */
    fun parseRecipientIds(ids: String?): List<Long> = ids.orEmpty().split(" ").mapNotNull { it.trim().toLongOrNull() }

    private fun addresses(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        context.contentResolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) map[cursor.getLong(0)] = cursor.getString(1).orEmpty()
        }
        return map
    }

    private fun unreadByThread(context: Context): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        fun count(uri: Uri, column: String, selection: String) {
            try {
                context.contentResolver.query(uri, arrayOf(column), selection, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) cursor.getLong(0).let { counts[it] = (counts[it] ?: 0) + 1 }
                }
            } catch (ignored: Exception) {
            }
        }
        count(Sms.CONTENT_URI, Sms.THREAD_ID, "${Sms.READ} = 0 AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}")
        count(Mms.CONTENT_URI, Mms.THREAD_ID, "${Mms.READ} = 0 AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX}")
        return counts
    }

    /** Pinned chats, shared with the current Messages screens. */
    fun pinned(context: Context): Set<Long> =
        context.getSharedPreferences("Prefs", Context.MODE_PRIVATE).getStringSet("pinned_conversations", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()

    /** Archived chats, from the current Messages database (moved into Omnio's own in a later stage). */
    fun archived(context: Context): Set<Long> = try {
        com.omnioone.messages.databases.MessagesDatabase.getInstance(context).ConversationsDao().getAllArchived().map { it.threadId }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Every conversation, newest first (or just [onlyThread]). */
    fun conversations(context: Context, onlyThread: Long? = null): List<Conversation> {
        val addressBook = addresses(context)
        val unread = unreadByThread(context)
        val pins = pinned(context)
        val archive = archived(context)
        val people = HashMap<String, Person>()
        fun person(address: String) = people.getOrPut(address) {
            val found = OmnioCallLog.lookup(context, address)
            Person(address, found?.name, found?.photoUri.orEmpty(), found?.contactId)
        }

        val list = ArrayList<Conversation>()
        try {
            context.contentResolver.query(
                MMS_SMS_CONVERSATIONS,
                arrayOf(Threads._ID, Threads.DATE, Threads.SNIPPET, Threads.RECIPIENT_IDS, Threads.HAS_ATTACHMENT, Threads.MESSAGE_COUNT),
                null, null, "${Threads.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(0)
                    if (onlyThread != null && threadId != onlyThread) continue
                    if (onlyThread == null && cursor.getInt(5) == 0) continue // empty threads (all messages deleted)
                    val members = parseRecipientIds(cursor.getString(3)).mapNotNull { addressBook[it] }.filter { it.isNotBlank() }.map { person(it) }
                    list += Conversation(
                        threadId = threadId,
                        people = members,
                        snippet = cursor.getString(2).orEmpty(),
                        date = toMillis(cursor.getLong(1)),
                        unread = unread[threadId] ?: 0,
                        hasAttachment = cursor.getInt(4) == 1,
                        isPinned = threadId in pins,
                        isArchived = threadId in archive,
                    )
                }
            }
        } catch (ignored: Exception) {
        }
        return list
    }

    /** The latest [limit] texts and picture messages in a conversation, oldest first. */
    fun messages(context: Context, threadId: Long, limit: Int = 200): List<Message> {
        val all = ArrayList<Message>()
        try {
            context.contentResolver.query(
                Sms.CONTENT_URI,
                arrayOf(Sms._ID, Sms.TYPE, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.READ, Sms.STATUS),
                "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Sms.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(1)
                    all += Message(
                        id = cursor.getLong(0),
                        threadId = threadId,
                        isMms = false,
                        incoming = type == Sms.MESSAGE_TYPE_INBOX,
                        address = cursor.getString(2).orEmpty(),
                        body = cursor.getString(3).orEmpty(),
                        date = toMillis(cursor.getLong(4)),
                        read = cursor.getInt(5) == 1,
                        failed = type == Sms.MESSAGE_TYPE_FAILED,
                        pending = type == Sms.MESSAGE_TYPE_OUTBOX || type == Sms.MESSAGE_TYPE_QUEUED,
                    )
                }
            }
        } catch (ignored: Exception) {
        }
        try {
            context.contentResolver.query(
                Mms.CONTENT_URI,
                arrayOf(Mms._ID, Mms.MESSAGE_BOX, Mms.DATE, Mms.READ),
                "${Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Mms.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val box = cursor.getInt(1)
                    val parts = mmsParts(context, id)
                    all += Message(
                        id = id,
                        threadId = threadId,
                        isMms = true,
                        incoming = box == Mms.MESSAGE_BOX_INBOX,
                        address = if (box == Mms.MESSAGE_BOX_INBOX) mmsSender(context, id) else "",
                        body = parts.filter { it.mime == "text/plain" }.joinToString("\n") { it.text.orEmpty() },
                        date = toMillis(cursor.getLong(2)),
                        read = cursor.getInt(3) == 1,
                        failed = box == Mms.MESSAGE_BOX_FAILED,
                        pending = box == Mms.MESSAGE_BOX_OUTBOX,
                        parts = parts.filter { it.mime != "application/smil" },
                    )
                }
            }
        } catch (ignored: Exception) {
        }
        return all.sortedBy { it.date }.takeLast(limit)
    }

    private fun mmsParts(context: Context, mmsId: Long): List<Part> {
        val parts = ArrayList<Part>()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(mmsId.toString()), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    parts += Part(id, cursor.getString(1).orEmpty(), cursor.getString(2), Uri.parse("content://mms/part/$id"))
                }
            }
        } catch (ignored: Exception) {
        }
        return parts
    }

    private fun mmsSender(context: Context, mmsId: Long): String = try {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type = ?", arrayOf(MMS_FROM.toString()), null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" } ?: ""
    } catch (e: Exception) {
        ""
    }

    // ---- checking the new reader against the current Messages code ----

    data class Check(val newChats: Int, val oldChats: Int, val newUnread: Int, val oldUnread: Int, val onlyNew: List<String>, val onlyOld: List<String>) {
        val matches get() = newChats == oldChats && newUnread == oldUnread && onlyNew.isEmpty() && onlyOld.isEmpty()
    }

    /** Reads everything both ways and compares, so we know the new reader is right before any screen uses it. */
    fun check(context: Context): Check {
        val mine = conversations(context)
        val old = try {
            context.getConversations()
        } catch (e: Exception) {
            arrayListOf()
        }
        val oldUnread = try {
            context.getUnreadCountsByThread().values.sum()
        } catch (e: Exception) {
            -1
        }
        val mineIds = mine.map { it.threadId }.toSet()
        val oldIds = old.map { it.threadId }.toSet()
        return Check(
            newChats = mine.size,
            oldChats = old.size,
            newUnread = mine.sumOf { it.unread },
            oldUnread = oldUnread,
            onlyNew = mine.filter { it.threadId !in oldIds }.take(5).map { it.title },
            onlyOld = old.filter { it.threadId !in mineIds }.take(5).map { it.title },
        )
    }
}
