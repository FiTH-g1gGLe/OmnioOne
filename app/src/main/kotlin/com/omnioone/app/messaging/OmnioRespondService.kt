package com.omnioone.app.messaging

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager

/**
 * Omnio One: sends the quick "Can't talk now" style replies you pick when declining a call.
 * Android asks the phone's messaging app to do this.
 */
class OmnioRespondService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val numbers = numbersFrom(intent.data)
            if (text.isNotBlank() && numbers.isNotEmpty()) {
                Thread {
                    try {
                        OmnioSender.send(applicationContext, text, numbers, -1)
                    } catch (ignored: Exception) {
                    }
                    stopSelf(startId)
                }.start()
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun numbersFrom(uri: Uri?): List<String> =
        uri?.schemeSpecificPart?.substringBefore("?")?.let { Uri.decode(it) }?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
