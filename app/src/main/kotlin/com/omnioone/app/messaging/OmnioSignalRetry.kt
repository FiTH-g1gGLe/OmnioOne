package com.omnioone.app.messaging

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms

/**
 * Omnio One: texts that failed because there was no signal (or flight mode was on) are tried
 * again by themselves once the phone is back on the mobile network. Each text gets a few
 * tries, then it's left as "Not sent" with a notification as usual.
 */
object OmnioSignalRetry {

    private const val PREFS = "omnio_signal_retry"
    private const val JOB_ID = 4207
    private const val MAX_TRIES = 5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Texts waiting for signal, with how many times each has been tried. */
    private fun waiting(context: Context): Map<String, Int> =
        prefs(context).all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()

    fun isWaiting(context: Context, uri: Uri) = prefs(context).contains(uri.toString())

    /** The ids of the texts in this list that are waiting for signal. */
    fun waitingIds(context: Context): Set<Long> =
        waiting(context).keys.mapNotNull { Uri.parse(it).lastPathSegment?.toLongOrNull() }.toSet()

    fun add(context: Context, uri: Uri) {
        val tries = (prefs(context).getInt(uri.toString(), 0)) + 1
        if (tries > MAX_TRIES) {
            prefs(context).edit().remove(uri.toString()).apply()
            return
        }
        prefs(context).edit().putInt(uri.toString(), tries).apply()
        schedule(context, tries)
    }

    fun remove(context: Context, uri: Uri) = prefs(context).edit().remove(uri.toString()).apply()

    private fun schedule(context: Context, tries: Int) {
        try {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, OmnioSignalRetryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR)
                .setMinimumLatency(20_000L * tries)
                // even with mobile data switched off, check again after a while
                .setOverrideDeadline(10 * 60_000L * tries)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        } catch (ignored: Exception) {
        }
    }

    /** Sends everything that's waiting. Texts that fail again for lack of signal come back here via [add]. */
    fun resendAll(context: Context) {
        waiting(context).keys.forEach { key ->
            val uri = Uri.parse(key)
            var stillFailed = false
            try {
                context.contentResolver.query(uri, arrayOf(Sms.TYPE), null, null, null)?.use {
                    stillFailed = it.moveToFirst() && it.getInt(0) == Sms.MESSAGE_TYPE_FAILED
                }
            } catch (ignored: Exception) {
            }
            if (!stillFailed) {
                // already resent by hand, deleted, or gone through
                remove(context, uri)
                return@forEach
            }
            OmnioSender.queue {
                try {
                    OmnioSender.resend(context, uri)
                } catch (ignored: Exception) {
                }
            }
        }
        OmnioMessagesBus.changed()
    }
}

class OmnioSignalRetryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            OmnioSignalRetry.resendAll(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
