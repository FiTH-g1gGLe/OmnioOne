package com.omnioone.app.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.omnioone.app.core.toast
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioSocials

/**
 * Omnio One: "Share → Omnio One / Link to contact" from Facebook, Instagram, TikTok etc.
 * Takes a shared profile link, or a screenshot of a profile (read on the phone), and hands the
 * address to the contact you were finding.
 */
class OmnioShareLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = OmnioSocials.getPendingLink(this)
        if (pending == null) {
            toast(R.string.omnio_share_no_contact, android.widget.Toast.LENGTH_LONG)
            finish()
            return
        }

        if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (image == null) {
                finish()
                return
            }
            readScreenshot(image, pending)
            return
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val url = OmnioSocials.firstUrl(text)
        if (url == null || OmnioSocials.networkFor(url) == null) {
            toast(R.string.omnio_share_not_profile)
        } else {
            handOver(pending, url)
        }
        finish()
    }

    /** Reads the screenshot's text on the phone and looks for a profile address in it. */
    private fun readScreenshot(image: Uri, pending: OmnioSocials.PendingLink) {
        toast(R.string.omnio_reading_screenshot)
        try {
            val input = InputImage.fromFilePath(this, image)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    // lines can split an address, so try each line and then everything joined up
                    val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                    val wanted = pending.networkKey.let { if (it == "messenger") "facebook" else it }
                    val url = lines.firstNotNullOfOrNull { OmnioSocials.profileUrlIn(it, wanted) }
                        ?: OmnioSocials.profileUrlIn(lines.joinToString(""), wanted)
                    if (url == null) {
                        toast(R.string.omnio_screenshot_no_link, android.widget.Toast.LENGTH_LONG)
                    } else {
                        handOver(pending, url)
                    }
                    recognizer.close()
                    finish()
                }
                .addOnFailureListener {
                    toast(R.string.omnio_screenshot_no_link, android.widget.Toast.LENGTH_LONG)
                    recognizer.close()
                    finish()
                }
        } catch (e: Exception) {
            toast(R.string.omnio_screenshot_no_link, android.widget.Toast.LENGTH_LONG)
            finish()
        }
    }

    private fun handOver(pending: OmnioSocials.PendingLink, url: String) {
        startActivity(OmnioSocials.linkIntent(this, pending, url))
    }
}
