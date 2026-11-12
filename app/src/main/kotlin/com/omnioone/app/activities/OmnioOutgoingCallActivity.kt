package com.omnioone.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import com.omnioone.app.R
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.core.REQUEST_CODE_SET_DEFAULT_DIALER
import com.omnioone.app.core.isDefaultDialer
import com.omnioone.app.core.telecomManager
import com.omnioone.app.core.toast
import com.omnioone.app.messaging.OmnioBlocking
import com.omnioone.app.phone.chooseSim

/**
 * Omnio One: another app (or a "tel:" link) asked to call a number. Checks it isn't blocked,
 * picks the SIM, hands the call to Android and closes. There's no screen of its own; the
 * call screen opens as soon as the call starts.
 */
class OmnioOutgoingCallActivity : OmnioActivity() {

    private var number: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.data?.takeIf { intent.action == Intent.ACTION_CALL }
        when {
            number == null -> {
                toast(R.string.unknown_error_occurred)
                finish()
            }
            isDefaultDialer() -> placeCall()
            else -> launchSetDefaultDialerIntent()
        }
    }

    private fun placeCall() {
        val target = number ?: return finish()
        if (OmnioBlocking.isBlocked(this, target.schemeSpecificPart.orEmpty())) {
            toast(R.string.calling_blocked_number)
            finish()
            return
        }
        chooseSim(intent, target.toString()) { handle ->
            try {
                if (handle != null) {
                    @Suppress("MissingPermission")
                    telecomManager.placeCall(target, Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                    })
                }
            } catch (e: Exception) {
                toast(R.string.unknown_error_occurred)
            }
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode != REQUEST_CODE_SET_DEFAULT_DIALER) return
        if (isDefaultDialer()) {
            placeCall()
            return
        }
        // still not the phone app: show where to change it
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
        } catch (ignored: Exception) {
        }
        finish()
    }
}
