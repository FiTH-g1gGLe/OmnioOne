package com.omnioone.app.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.helpers.OmnioPhotoCapture
import com.omnioone.app.helpers.OmnioSocials

/**
 * Omnio One "Link helper".
 *
 * While you're linking a contact ("Find on Facebook"), this reads the profile address shown on
 * screen in the social app (e.g. facebook.com/jones388 under "profile link") and hands it to Omnio.
 * It does nothing at all unless a link is in progress, and only the social apps listed in
 * res/xml/omnio_link_helper.xml are ever sent to it.
 */
class OmnioLinkHelperService : AccessibilityService() {

    private var lastHandedOver: String? = null
    private var photoTakenFor: String? = null
    private var capturing = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val photoLabel = Regex("profile (picture|photo|pic|image)|avatar|foto de perfil|photo de profil|profilbild", RegexOption.IGNORE_CASE)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg != null && tapCallButtonIfAsked(pkg)) return
        val pending = OmnioSocials.getPendingLink(this)
        val photoPending = OmnioPhotoCapture.getPending(this)
        if (pending == null && photoPending == null) return
        if (capturing) return
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        // the profile photo on screen, if we still want one for this contact
        val contactId = pending?.contactId ?: photoPending!!.contactId
        val prefs = getSharedPreferences("omnio", Context.MODE_PRIVATE)
        val session = "$contactId:" + (if (pending != null) prefs.getLong("link_time", 0) else prefs.getLong("photo_time", 0))
        val photo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && photoTakenFor != session) profilePhoto(root, pkg) else null

        val url = pending?.let { link ->
            val texts = ArrayList<CharSequence>()
            collectTexts(root, texts, 0)
            val wanted = link.networkKey.let { if (it == "messenger") "facebook" else it }
            texts.asSequence()
                .mapNotNull { profileUrlIn(it.toString()) }
                .firstOrNull { found ->
                    val network = OmnioSocials.networkFor(found) ?: return@firstOrNull false
                    network.key == wanted || network.key == link.networkKey
                }
        }?.takeIf { it != lastHandedOver }

        when {
            // take the photo first: once Omnio comes to the front the profile is no longer on screen
            url != null && photo != null -> capture(photo, contactId, session) { handOver(pending!!, url) }
            url != null -> handOver(pending!!, url)
            photo != null -> capture(photo, contactId, session) { if (pending == null) OmnioPhotoCapture.clearPending(this) }
        }
    }

    private fun handOver(pending: OmnioSocials.PendingLink, url: String) {
        lastHandedOver = url
        startActivity(OmnioSocials.linkIntent(this, pending, url))
    }

    /**
     * The profile photo on screen: the biggest picture labelled as a profile picture, or on
     * Facebook (which often leaves it unlabelled) the big square picture near the top of a profile.
     * Small pictures in search results and feeds are ignored.
     */
    private fun profilePhoto(root: AccessibilityNodeInfo, pkg: String?): android.graphics.Rect? {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var labelled: android.graphics.Rect? = null
        var square: android.graphics.Rect? = null
        val seen = ArrayList<String>()
        val facebook = pkg?.startsWith("com.facebook") == true
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val ratio = if (bounds.height() > 0) bounds.width().toFloat() / bounds.height() else 0f
            val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
            if (bounds.width() >= 48 * density && ratio in 0.8f..1.25f && seen.size < 15) {
                seen += "${label.take(40)} [${(bounds.width() / density).toInt()}dp ${node.className?.toString()?.substringAfterLast('.')}]"
            }
            if (label.length <= 80 && photoLabel.containsMatchIn(label) && bounds.width() >= 64 * density && ratio in 0.8f..1.25f) {
                if (labelled == null || bounds.width() > labelled!!.width()) labelled = bounds
            }
            if (facebook && ratio in 0.85f..1.15f && bounds.width() >= 120 * density && bounds.width() <= screenW * 0.6f &&
                bounds.top < screenH * 0.55f && bounds.top > 0 && label.length <= 60
            ) {
                if (square == null || bounds.width() > square!!.width()) square = bounds
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val found = labelled ?: square
        if (found == null) OmnioPhotoCapture.note(this, "No profile photo found on screen in ${pkg.orEmpty()}. Square pictures seen: " + seen.joinToString("; ").ifEmpty { "none" })
        return found
    }

    /** Screenshots the screen, crops out the profile photo and keeps it for the contact. */
    private fun capture(bounds: android.graphics.Rect, contactId: Int, session: String, then: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            then()
            return
        }
        capturing = true
        // give the picture a moment to finish loading
        handler.postDelayed({
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val screen = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (screen != null) {
                                val inset = bounds.width() / 30
                                val left = (bounds.left + inset).coerceIn(0, screen.width - 1)
                                val top = (bounds.top + inset).coerceIn(0, screen.height - 1)
                                val size = minOf(bounds.width() - inset * 2, bounds.height() - inset * 2, screen.width - left, screen.height - top)
                                if (size > 0) {
                                    var crop = android.graphics.Bitmap.createBitmap(screen, left, top, size, size)
                                    if (size > 720) crop = android.graphics.Bitmap.createScaledBitmap(crop, 720, 720, true)
                                    OmnioPhotoCapture.store(this@OmnioLinkHelperService, contactId, crop)
                                    photoTakenFor = session
                                    OmnioPhotoCapture.note(this@OmnioLinkHelperService, "Photo caught (${crop.width}×${crop.height})")
                                }
                            }
                        } catch (ignored: Exception) {
                        }
                        capturing = false
                        then()
                    }

                    override fun onFailure(errorCode: Int) {
                        OmnioPhotoCapture.note(this@OmnioLinkHelperService, "Screenshot refused (code $errorCode): switch Link helper off and on again")
                        capturing = false
                        then()
                    }
                })
            } catch (e: Exception) {
                OmnioPhotoCapture.note(this, "Screenshot not allowed: switch Link helper off and on again (${e.javaClass.simpleName})")
                capturing = false
                then()
            }
        }, 700)
    }

    /**
     * Messenger has no "start a call" link, so Omnio opens the chat and taps the call button for you.
     * Only runs for a few seconds after you tap Call / Video on a contact page.
     */
    private fun tapCallButtonIfAsked(pkg: String): Boolean {
        val tap = OmnioSocials.getAutoTap(this, pkg) ?: return false
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return true
        val wanted = when (tap) {
            OmnioSocials.AutoTap.VOICE -> Regex("(audio|voice|phone) call|start (a )?(voice|audio) call|^call$", RegexOption.IGNORE_CASE)
            OmnioSocials.AutoTap.VIDEO -> Regex("video (call|chat)|start (a )?video", RegexOption.IGNORE_CASE)
        }
        val button = findNode(root, 0) { node ->
            // header buttons are icons with a description; skip message bubbles like "Missed audio call"
            val label = node.contentDescription?.toString()?.trim().orEmpty()
            label.isNotEmpty() && label.length <= 30 && !label.contains("missed", true) && wanted.containsMatchIn(label)
        } ?: return true

        var target: AccessibilityNodeInfo? = button
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            OmnioSocials.clearAutoTap(this)
        }
        return true
    }

    private fun findNode(node: AccessibilityNodeInfo?, depth: Int, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (test(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), depth + 1, test)?.let { return it }
        }
        return null
    }

    private fun profileUrlIn(text: String): String? = if (text.length > 400) null else OmnioSocials.profileUrlIn(text)

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<CharSequence>, depth: Int) {
        if (node == null || depth > 40 || out.size > 400) return
        node.text?.let { out.add(it) }
        node.contentDescription?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${OmnioLinkHelperService::class.java.name}"
            return enabled.split(':').any { it.equals(me, true) }
        }
    }
}
