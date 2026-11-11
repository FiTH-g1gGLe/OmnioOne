package com.omnioone.app.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.messaging.messagesConfig as config
import java.io.File

/**
 * Omnio One message notifications (Messages rewrite, stage 5): the sender's photo, the chat so far,
 * picture previews, quick reply, mark as read, and a conversation shortcut so Android
 * shows it in the Conversations section.
 */
object OmnioNotifications {

    private const val CHANNEL = "omnio_messages"
    private const val CHANNEL_FAILED = "omnio_messages_not_sent"
    const val REPLY_KEY = "omnio_reply"
    const val ACTION_REPLY = "com.omnioone.app.action.MESSAGE_REPLY"
    const val ACTION_READ = "com.omnioone.app.action.MESSAGE_READ"
    const val ACTION_DELETE = "com.omnioone.app.action.MESSAGE_DELETE"
    const val ACTION_COPY = "com.omnioone.app.action.MESSAGE_COPY_CODE"
    const val EXTRA_CODE = "omnio_code"
    const val EXTRA_MESSAGE_ID = "omnio_message_id"
    const val EXTRA_IS_MMS = "omnio_is_mms"

    fun idFor(threadId: Long) = threadId.hashCode()

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channelFor(context: Context, threadId: Long): String {
        // chats given their own sound in the old settings keep it
        if (context.config.customNotifications.contains(threadId.toString())) return threadId.toString()
        makeChannel(context, CHANNEL, context.getString(R.string.omnio_notify_channel))
        return CHANNEL
    }

    private fun makeChannel(context: Context, id: String, name: String) {
        val manager = manager(context)
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            enableLights(true)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        })
    }

    /** Opens the chat in whichever conversation screen is switched on. */
    fun threadIntent(context: Context, threadId: Long): Intent {
        val screen = OmnioThreadActivity::class.java
        return Intent(context, screen).putExtra(THREAD_ID, threadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    // ---- incoming ----

    /** Shows (or adds to) the notification for a chat. Call on a background thread. */
    fun showIncoming(context: Context, message: OmnioMessageStore.Message, code: String? = null) {
        val threadId = message.threadId
        if (threadId == OmnioSendReceiver.openThreadId) return // you're looking at it
        val chat = OmnioMessageStore.conversations(context, onlyThread = threadId).firstOrNull()
        val sender = chat?.people?.firstOrNull { samePerson(it.address, message.address) } ?: chat?.people?.singleOrNull()
        val senderName = sender?.label ?: message.address
        val senderPhoto = avatar(context, sender?.photo, senderName)
        val text = previewText(context, message)
        val picture = message.parts.firstOrNull { it.mime.startsWith("image/") }?.let { sharePicture(context, it) }
        val allowReply = message.address.none { it.isLetter() } && chat != null

        val them = Person.Builder().setName(senderName).setKey(message.address).setIcon(IconCompat.createWithBitmap(senderPhoto)).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.omnio_notify_you)).build())
        if (chat?.isGroup == true) {
            style.conversationTitle = chat.title
            style.isGroupConversation = true
        }
        earlierMessages(context, threadId).forEach { style.addMessage(it) }
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, message.date, them).apply {
            if (picture != null) setData("image/jpeg", picture)
        })

        val builder = baseBuilder(context, threadId, channelFor(context, threadId))
            .setStyle(style)
            .setLargeIcon(if (chat?.isGroup == true) avatar(context, chat.photo, chat.title) else senderPhoto)
            .setWhen(message.date)
        lockScreenVersion(context, builder, chat?.title ?: senderName, threadId)
        if (code != null) {
            val copy = Intent(context, OmnioNotificationActions::class.java).setAction(ACTION_COPY).putExtra(THREAD_ID, threadId).putExtra(EXTRA_CODE, code)
            val pending = PendingIntent.getBroadcast(context, idFor(threadId) + 1, copy, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(com.omnioone.commons.R.drawable.ic_copy_vector, context.getString(R.string.omnio_code_copy, code), pending).setShowsUserInterface(false).build())
            if (OmnioCodes.isOn(context)) builder.setSubText(context.getString(R.string.omnio_code_copied_short))
        }
        if (allowReply) builder.addAction(replyAction(context, threadId))
        builder.addAction(action(context, ACTION_READ, threadId, com.omnioone.commons.R.drawable.ic_check_vector, R.string.omnio_notify_mark_read))
        if (!allowReply) {
            builder.addAction(action(context, ACTION_DELETE, threadId, com.omnioone.commons.R.drawable.ic_delete_vector, R.string.omnio_notify_delete) {
                putExtra(EXTRA_MESSAGE_ID, message.id).putExtra(EXTRA_IS_MMS, message.isMms)
            })
        }
        shortcut(context, threadId, chat?.title ?: senderName, if (chat?.isGroup == true) avatar(context, chat.photo, chat.title) else senderPhoto, them)
            ?.let { builder.setShortcutId(it) }
        notify(context, idFor(threadId), builder.build())
    }

    /** After a quick reply: add your message to the notification, quietly. */
    fun addReply(context: Context, threadId: Long, text: String) {
        val active = manager(context).activeNotifications.firstOrNull { it.id == idFor(threadId) } ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification) ?: return
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), null as Person?))
        val chat = OmnioMessageStore.conversations(context, onlyThread = threadId).firstOrNull()
        val builder = NotificationCompat.Builder(context, active.notification.channelId)
        chat?.let { builder.setLargeIcon(avatar(context, it.photo, it.title)) }
        baseBuilder(context, threadId, active.notification.channelId, builder)
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(replyAction(context, threadId))
            .addAction(action(context, ACTION_READ, threadId, com.omnioone.commons.R.drawable.ic_check_vector, R.string.omnio_notify_mark_read))
        active.notification.shortcutId?.let { builder.setShortcutId(it) }
        notify(context, idFor(threadId), builder.build())
    }

    /** "Message not sent" for chats that aren't open. */
    fun showFailed(context: Context, threadId: Long, name: String) {
        makeChannel(context, CHANNEL_FAILED, context.getString(R.string.omnio_notify_failed_channel))
        val text = context.getString(R.string.omnio_notify_failed_text, name)
        val builder = baseBuilder(context, threadId, CHANNEL_FAILED)
            .setContentTitle(context.getString(R.string.omnio_notify_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setLargeIcon(avatar(context, null, name))
        notify(context, (threadId.hashCode() + 7919), builder.build())
    }

    fun cancel(context: Context, threadId: Long) = manager(context).cancel(idFor(threadId))

    // ---- building blocks ----

    private fun baseBuilder(context: Context, threadId: Long, channel: String, existing: NotificationCompat.Builder? = null) =
        (existing ?: NotificationCompat.Builder(context, channel))
            .setSmallIcon(R.drawable.ic_omnio_message_small)
            .setColor(OmnioColors.MESSAGES)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, idFor(threadId), threadIntent(context, threadId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    /** What shows on the lock screen, from the "Lock screen" Messages setting. */
    private fun lockScreenVersion(context: Context, builder: NotificationCompat.Builder, title: String, threadId: Long) {
        when (context.config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                baseBuilder(context, threadId, CHANNEL).setContentTitle(title).setContentText(context.getString(R.string.omnio_notify_new_message)).build()
            )
            LOCK_SCREEN_NOTHING -> builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            else -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        }
    }

    private fun replyAction(context: Context, threadId: Long): NotificationCompat.Action {
        val intent = Intent(context, OmnioNotificationActions::class.java).setAction(ACTION_REPLY).putExtra(THREAD_ID, threadId)
        val pending = PendingIntent.getBroadcast(context, idFor(threadId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder(REPLY_KEY).setLabel(context.getString(R.string.omnio_notify_reply)).build()
        return NotificationCompat.Action.Builder(com.omnioone.commons.R.drawable.ic_send_vector, context.getString(R.string.omnio_notify_reply), pending)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun action(context: Context, action: String, threadId: Long, icon: Int, label: Int, extras: Intent.() -> Unit = {}): NotificationCompat.Action {
        val intent = Intent(context, OmnioNotificationActions::class.java).setAction(action).putExtra(THREAD_ID, threadId).apply(extras)
        val pending = PendingIntent.getBroadcast(context, idFor(threadId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(icon, context.getString(label), pending)
            .setSemanticAction(if (action == ACTION_READ) NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ else NotificationCompat.Action.SEMANTIC_ACTION_DELETE)
            .setShowsUserInterface(false)
            .build()
    }

    private fun earlierMessages(context: Context, threadId: Long): List<NotificationCompat.MessagingStyle.Message> = try {
        manager(context).activeNotifications.firstOrNull { it.id == idFor(threadId) }
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages.orEmpty() }
            .orEmpty().takeLast(6)
    } catch (e: Exception) {
        emptyList()
    }

    private fun notify(context: Context, id: Int, notification: Notification) = try {
        manager(context).notify(id, notification)
    } catch (ignored: SecurityException) {
        // notifications permission not given
    }

    private fun previewText(context: Context, message: OmnioMessageStore.Message): String {
        if (message.body.isNotBlank()) return message.body
        val part = message.parts.firstOrNull { it.mime != "text/plain" && it.mime != "application/smil" }
        return when {
            part == null -> context.getString(R.string.omnio_notify_new_message)
            part.mime.startsWith("image/") -> "📷 " + context.getString(R.string.omnio_notify_photo)
            part.mime.startsWith("video/") -> "🎥 " + context.getString(R.string.omnio_notify_video)
            part.mime.startsWith("audio/") -> "🎤 " + context.getString(R.string.omnio_notify_audio)
            part.mime.contains("vcard") -> "👤 " + context.getString(R.string.omnio_notify_contact)
            else -> "📎 " + context.getString(R.string.omnio_notify_attachment)
        }
    }

    private fun samePerson(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit).takeLast(9)
        val y = b.filter(Char::isDigit).takeLast(9)
        return if (x.isNotEmpty() && y.isNotEmpty()) x == y else a.equals(b, ignoreCase = true)
    }

    /** The contact's photo as a circle, or a letter tile. */
    private fun avatar(context: Context, photo: String?, name: String): Bitmap {
        val size = (context.resources.displayMetrics.density * 96).toInt()
        val source = photo?.takeIf { it.isNotEmpty() }?.let {
            try {
                context.contentResolver.openInputStream(Uri.parse(it))?.use { stream -> BitmapFactory.decodeStream(stream) }
            } catch (e: Exception) {
                null
            }
        } ?: OmnioDisplay.letterTile(context, name, 0f)
        val square = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - square) / 2, (source.height - square) / 2, square, square)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    /** Copies a received photo somewhere the notification can show it from. */
    private fun sharePicture(context: Context, part: OmnioMessageStore.Part): Uri? = try {
        val folder = File(context.cacheDir, "attachments/notify").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3 * 24 * 3600 * 1000L }?.forEach { it.delete() }
        val file = File(folder, "part_${part.id}.jpg")
        if (!file.exists()) {
            val bytes = context.contentResolver.openInputStream(part.uri)?.use { it.readBytes() } ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri
    } catch (e: Exception) {
        null
    }

    /** A conversation shortcut, so the chat sits in Android's Conversations section and can be prioritised. */
    private fun shortcut(context: Context, threadId: Long, title: String, icon: Bitmap, person: Person): String? = try {
        val id = "omnio_thread_$threadId"
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title)
            .setLongLived(true)
            .setPerson(person)
            .setIcon(IconCompat.createWithAdaptiveBitmap(padForAdaptive(icon)))
            .setIntent(threadIntent(context, threadId).setAction(Intent.ACTION_VIEW))
            .setCategories(setOf("android.shortcut.conversation"))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, info)
        id
    } catch (e: Exception) {
        null
    }

    private fun padForAdaptive(icon: Bitmap): Bitmap {
        val size = (icon.width * 1.5f).toInt()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(OmnioColors.CARD)
        canvas.drawBitmap(icon, (size - icon.width) / 2f, (size - icon.height) / 2f, null)
        return out
    }
}
