package com.omnioone.app.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioMainActivity
import com.omnioone.app.activities.OmnioOutgoingCallActivity
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.messaging.OmnioNewMessageActivity

/**
 * Omnio One's missed-call notification. As the phone app, Android hands missed calls to Omnio
 * instead of showing its own: this shows who it was (with their photo), how many you missed,
 * and Call back / Message buttons. Opening Recents clears it.
 */
object OmnioMissedCalls {

    private const val CHANNEL = "omnio_missed_calls"
    private const val NOTIFICATION_ID = 4310

    fun show(context: Context, count: Int, number: String, callBack: PendingIntent?, clear: PendingIntent?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (count <= 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.omnio_channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
            })
        }
        val person = if (number.isNotEmpty()) OmnioCallLog.lookup(context, number) else null
        val who = person?.name?.ifEmpty { null } ?: number.ifEmpty { context.getString(R.string.omnio_missed_unknown) }
        val title = if (count == 1) context.getString(R.string.omnio_missed_one, who) else context.getString(R.string.omnio_missed_many, count)
        val body = if (count == 1) {
            person?.label?.takeIf { it.isNotEmpty() }?.let { "$it · $number" } ?: number
        } else {
            context.getString(R.string.omnio_missed_latest, who)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openRecents = PendingIntent.getActivity(
            context, 0,
            Intent(context, OmnioMainActivity::class.java).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_omnio_notify_call)
            .setColor(OmnioColors.MISSED)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openRecents)
            .setShowWhen(true)
        clear?.let { builder.setDeleteIntent(it) }
        photo(context, person?.photoUri.orEmpty(), who)?.let { builder.setLargeIcon(it) }

        if (number.isNotEmpty()) {
            val call = callBack ?: PendingIntent.getActivity(
                context, 1,
                Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null), context, OmnioOutgoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
            val text = PendingIntent.getActivity(
                context, 2,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null), context, OmnioNewMessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
            builder.addAction(R.drawable.ic_omnio_notify_call, context.getString(R.string.omnio_missed_call_back), call)
            builder.addAction(R.drawable.ic_omnio_notify_message, context.getString(R.string.omnio_missed_message), text)
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (ignored: SecurityException) {
            // notifications turned off for Omnio
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Their contact photo in a circle, or their coloured letter if they haven't got one. */
    private fun photo(context: Context, uri: String, name: String): Bitmap? = try {
        val source = if (uri.isNotEmpty()) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        } else {
            null
        } ?: OmnioDisplay.letterTile(context, name, 0f)
        val size = minOf(source.width, source.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        out
    } catch (e: Exception) {
        null
    }
}

/** Android tells the phone app about missed calls here, instead of showing its own notification. */
class OmnioMissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER).orEmpty()
        @Suppress("DEPRECATION")
        val callBack = intent.getParcelableExtra<PendingIntent>(TelecomManager.EXTRA_CALL_BACK_INTENT)
        @Suppress("DEPRECATION")
        val clear = intent.getParcelableExtra<PendingIntent>(TelecomManager.EXTRA_CLEAR_MISSED_CALLS_INTENT)
        val pending = goAsync()
        Thread {
            try {
                OmnioMissedCalls.show(context.applicationContext, count, number, callBack, clear)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
