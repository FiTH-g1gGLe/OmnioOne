package com.omnioone.app.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import com.bumptech.glide.Glide
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioCallActivity
import com.omnioone.app.receivers.OmnioCallActionReceiver
import com.omnioone.app.core.OmnioColors
import java.util.concurrent.Executors

/**
 * Omnio One's call notification: rings on the lock screen (full-screen) for incoming calls,
 * with Answer / Decline, and stays in the shade during a call with a timer and Hang up.
 */
object OmnioCallNotification {
    private const val ID = 42
    private const val CHANNEL_RINGING = "omnio_calls_ringing"
    private const val CHANNEL_ONGOING = "omnio_calls_ongoing"

    const val ACTION_ANSWER = "com.omnioone.app.ANSWER_CALL"
    const val ACTION_DECLINE = "com.omnioone.app.DECLINE_CALL"
    const val ACTION_HANG_UP = "com.omnioone.app.HANG_UP_CALL"

    private val worker = Executors.newSingleThreadExecutor()
    private var cachedNumber: String? = null
    private var cachedInfo: OmnioCallInfo? = null
    private var cachedPhoto: Bitmap? = null

    /** When true, ringing doesn't pop up full-screen (you're using the phone and chose that). */
    @Volatile
    var quietRinging = false

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channels(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(CHANNEL_RINGING) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_RINGING, context.getString(R.string.omnio_channel_ringing), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // Android plays the ringtone itself
            })
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.omnio_channel_ongoing), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
            })
        }
    }

    private fun broadcast(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, OmnioCallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Shows or refreshes the notification for the current call. */
    fun update(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val call = OmnioCalls.primary()
            if (call == null) {
                cancel(app)
                return@execute
            }
            val state = OmnioCalls.stateOf(call)
            if (state == OmnioCalls.State.ENDED) {
                if (!OmnioCalls.hasCalls()) cancel(app)
                return@execute
            }
            val number = OmnioCalls.numberOf(call)
            if (number != cachedNumber || cachedInfo == null) {
                cachedNumber = number
                cachedInfo = try {
                    OmnioCallInfo.load(app, number)
                } catch (e: Exception) {
                    null
                }
                cachedPhoto = cachedInfo?.photoUri?.takeIf { it.isNotEmpty() }?.let { photo ->
                    try {
                        val size = (app.resources.displayMetrics.density * 64).toInt()
                        Glide.with(app).asBitmap().load(photo).circleCrop().submit(size, size).get()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val name = cachedInfo?.displayName ?: number.ifEmpty { app.getString(R.string.omnio_private_number) }
            post(app, state, name, cachedPhoto, OmnioCalls.connectedAt(call))
        }
    }

    private fun post(context: Context, state: OmnioCalls.State, name: String, photo: Bitmap?, connectedAt: Long) {
        channels(context)
        val ringing = state == OmnioCalls.State.RINGING
        val loud = ringing && !quietRinging
        val open = PendingIntent.getActivity(
            context, 0, OmnioCallActivity.startIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = context.getString(
            when (state) {
                OmnioCalls.State.RINGING -> R.string.omnio_call_incoming
                OmnioCalls.State.DIALING -> R.string.omnio_notification_calling
                OmnioCalls.State.HOLDING -> R.string.omnio_notification_on_hold
                else -> R.string.omnio_notification_on_call
            }
        )
        val builder = Notification.Builder(context, if (loud) CHANNEL_RINGING else CHANNEL_ONGOING)
            .setSmallIcon(com.omnioone.app.R.drawable.ic_phone_vector)
            .setContentTitle(name)
            .setContentText(status)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setColor(OmnioColors.CALLS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (photo != null) builder.setLargeIcon(photo)
        if (state == OmnioCalls.State.ACTIVE && connectedAt > 0) {
            builder.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
        }
        if (ringing) {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_phone_down_vector), context.getString(R.string.omnio_call_decline), broadcast(context, ACTION_DECLINE, 1)).build())
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, com.omnioone.app.R.drawable.ic_phone_vector), context.getString(R.string.omnio_call_answer), broadcast(context, ACTION_ANSWER, 2)).build())
            if (loud) builder.setFullScreenIntent(open, true)
        } else {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_phone_down_vector), context.getString(R.string.omnio_hang_up), broadcast(context, ACTION_HANG_UP, 3)).build())
        }
        try {
            manager(context).notify(ID, builder.build())
        } catch (ignored: Exception) {
        }
    }

    fun cancel(context: Context) {
        cachedNumber = null
        cachedInfo = null
        cachedPhoto = null
        try {
            manager(context).cancel(ID)
        } catch (ignored: Exception) {
        }
    }
}
