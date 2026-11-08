package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.helpers.OmnioSocials

/**
 * Omnio One: "Share → Omnio One" from Facebook, Instagram, TikTok etc.
 * Hands the profile link to the contact you were finding, then gets out of the way.
 */
class OmnioShareLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val url = OmnioSocials.firstUrl(text)
        val pending = OmnioSocials.getPendingLink(this)
        when {
            url == null || OmnioSocials.networkFor(url) == null -> toast(R.string.omnio_share_not_profile)
            pending == null -> toast(R.string.omnio_share_no_contact, android.widget.Toast.LENGTH_LONG)
            else -> startActivity(Intent(this, OmnioContactActivity::class.java).apply {
                putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, pending.rawId)
                putExtra(OmnioContactActivity.EXTRA_SHARED_LINK, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
