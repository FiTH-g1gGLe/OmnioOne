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
    /** Something small drawn over the photo (Messenger's "active 2h" badge, an online dot), to crop out. */
    private var overlay: android.graphics.Rect? = null
    private var photoNode: AccessibilityNodeInfo? = null
    private var photoPkg: String? = null
    private val badgeLabel = Regex("active|online|\\b\\d+\\s?(m|h|d|w|min|hr)s?\\b|ago", RegexOption.IGNORE_CASE)
    private val viewPhotoLabel = Regex("^(view|see|open) (profile )?(picture|photo)", RegexOption.IGNORE_CASE)
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
        var labelledNode: AccessibilityNodeInfo? = null
        var squareNode: AccessibilityNodeInfo? = null
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
                if (labelled == null || bounds.width() > labelled!!.width()) {
                    labelled = bounds
                    labelledNode = node
                }
            }
            if (facebook && ratio in 0.85f..1.15f && bounds.width() >= 120 * density && bounds.width() <= screenW * 0.6f &&
                bounds.top < screenH * 0.55f && bounds.top > 0 && label.length <= 60
            ) {
                if (square == null || bounds.width() > square!!.width()) {
                    square = bounds
                    squareNode = node
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val found = labelled ?: square
        photoNode = if (labelled != null) labelledNode else squareNode
        photoPkg = pkg
        overlay = found?.let { overlayOn(root, it, pkg) }
        if (found == null) OmnioPhotoCapture.note(this, "No profile photo found on screen in ${pkg.orEmpty()}. Square pictures seen: " + seen.joinToString("; ").ifEmpty { "none" })
        return found
    }

    /** Small views sitting on the photo's edge, like an "active" badge: their combined area, or null. */
    private fun overlayOn(root: AccessibilityNodeInfo, photo: android.graphics.Rect, pkg: String?): android.graphics.Rect? {
        val messenger = pkg == "com.facebook.orca"
        var union: android.graphics.Rect? = null
        val cx = photo.exactCenterX()
        val cy = photo.exactCenterY()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            val small = b.width() in 1 until (photo.width() * 0.45f).toInt() && b.height() in 1 until (photo.height() * 0.45f).toInt()
            val offCentre = Math.hypot((b.exactCenterX() - cx).toDouble(), (b.exactCenterY() - cy).toDouble()) > photo.width() * 0.28
            val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
            val badgeLike = badgeLabel.containsMatchIn(label) || (messenger && b.width() < photo.width() * 0.35f)
            if (small && offCentre && badgeLike && android.graphics.Rect.intersects(b, photo)) {
                val clipped = android.graphics.Rect(b).apply { intersect(photo) }
                union = union?.apply { union(clipped) } ?: clipped
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return union
    }

    /**
     * The square to keep, centred on the round photo (a little inside its edge, so frames and rings
     * are left out). When a badge sits on the edge, the circle is shrunk and slid away from it just
     * enough to leave it out, never past the edge of the photo.
     */
    private fun cropFor(photo: android.graphics.Rect, badge: android.graphics.Rect?): android.graphics.Rect {
        val cx = photo.exactCenterX()
        val cy = photo.exactCenterY()
        val radius = minOf(photo.width(), photo.height()) / 2f * 0.94f
        fun square(x: Float, y: Float, r: Float) = android.graphics.Rect((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt())
        if (badge == null) return square(cx, cy, radius)
        // direction from the centre to the badge
        var dx = badge.exactCenterX() - cx
        var dy = badge.exactCenterY() - cy
        val length = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        dx /= length
        dy /= length
        fun gap(x: Float, y: Float): Float {
            val nx = x.coerceIn(badge.left.toFloat(), badge.right.toFloat())
            val ny = y.coerceIn(badge.top.toFloat(), badge.bottom.toFloat())
            return Math.hypot((x - nx).toDouble(), (y - ny).toDouble()).toFloat()
        }
        var t = 0f
        while (t < radius * 0.4f) {
            val x = cx - dx * t
            val y = cy - dy * t
            val r = radius - t
            if (gap(x, y) >= r) return square(x, y, r)
            t += radius * 0.02f
        }
        return square(cx, cy, radius) // badge too far in to dodge: keep the whole photo
    }

    /** Takes a screenshot and keeps [area] of it as the contact's photo. Calls [done] with true if it worked. */
    private fun snapshot(area: android.graphics.Rect, contactId: Int, done: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            done(false)
            return
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var ok = false
                    try {
                        val screen = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (screen != null) {
                            val left = area.left.coerceIn(0, screen.width - 1)
                            val top = area.top.coerceIn(0, screen.height - 1)
                            val size = minOf(area.width(), area.height(), screen.width - left, screen.height - top)
                            if (size > 0) {
                                var crop = android.graphics.Bitmap.createBitmap(screen, left, top, size, size)
                                if (size > 720) crop = android.graphics.Bitmap.createScaledBitmap(crop, 720, 720, true)
                                OmnioPhotoCapture.store(this@OmnioLinkHelperService, contactId, crop)
                                OmnioPhotoCapture.note(this@OmnioLinkHelperService, "Photo caught (${crop.width}×${crop.height})")
                                ok = true
                            }
                        }
                    } catch (ignored: Exception) {
                    }
                    done(ok)
                }

                override fun onFailure(errorCode: Int) {
                    OmnioPhotoCapture.note(this@OmnioLinkHelperService, "Screenshot refused (code $errorCode): switch Link helper off and on again")
                    done(false)
                }
            })
        } catch (e: Exception) {
            OmnioPhotoCapture.note(this, "Screenshot not allowed: switch Link helper off and on again (${e.javaClass.simpleName})")
            done(false)
        }
    }

    /** Catches the photo on the profile, then (if "Full-size photos" is on) a sharper one from the picture viewer. */
    private fun capture(bounds: android.graphics.Rect, contactId: Int, session: String, then: () -> Unit) {
        capturing = true
        val badge = overlay
        val node = bounds
        val finish = {
            capturing = false
            then()
        }
        // give the picture a moment to finish loading
        handler.postDelayed({
            snapshot(cropFor(bounds, badge), contactId) { ok ->
                if (ok) photoTakenFor = session
                val fullSize = getSharedPreferences("omnio", Context.MODE_PRIVATE).getBoolean("photo_full_size", false)
                if (ok && fullSize) fullSizePhoto(node, contactId, finish) else finish()
            }
        }, 700)
    }

    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var target = node
        var steps = 0
        while (target != null && !target.isClickable && steps < 6) {
            target = target.parent
            steps++
        }
        return target?.takeIf { it.isClickable }
    }

    /** Taps the middle of [bounds] like a finger would (works even when the app doesn't mark the picture as tappable). */
    private fun tap(bounds: android.graphics.Rect, done: (Boolean) -> Unit) {
        val path = android.graphics.Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val sent = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = done(true)
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = done(false)
            }, handler)
        } catch (e: Exception) {
            false
        }
        if (!sent) done(false)
    }

    /**
     * Taps the profile picture to open it full size (choosing "View profile picture" if a menu
     * appears), keeps a sharper snapshot, then goes back. If anything unexpected opens, it goes
     * back and the first snapshot stays. Each step is noted in Settings → Last photo capture.
     */
    private fun fullSizePhoto(photo: android.graphics.Rect, contactId: Int, done: () -> Unit) {
        var opened = false
        fun goBack() {
            if (opened) performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed(done, 600)
        }
        fun shoot() {
            val root = try {
                rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            val area = root?.let { biggestPicture(it) }
            if (area == null) {
                OmnioPhotoCapture.note(this, "Full size: the picture didn't open (kept the profile snapshot)")
                goBack()
                return
            }
            opened = true
            snapshot(area, contactId) { ok ->
                if (ok) OmnioPhotoCapture.note(this, "Full-size photo caught (${area.width()}px)")
                goBack()
            }
        }
        tap(photo) { tapped ->
            if (!tapped) {
                OmnioPhotoCapture.note(this, "Full size: Android didn't allow the tap. Switch Link helper off and on again (kept the profile snapshot)")
                done()
                return@tap
            }
            handler.postDelayed({
                val root = try {
                    rootInActiveWindow
                } catch (e: Exception) {
                    null
                }
                // some profiles show a menu first: pick "View profile picture"
                val menuItem = root?.let {
                    findNode(it, 0) { node -> (node.text ?: node.contentDescription)?.toString()?.let { label -> label.length <= 40 && viewPhotoLabel.containsMatchIn(label) } == true }
                }
                if (menuItem != null) {
                    opened = true
                    val bounds = android.graphics.Rect().also { menuItem.getBoundsInScreen(it) }
                    val target = clickable(menuItem)
                    val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (clicked) {
                        opened = false // the menu closed itself; the viewer will set this again
                        handler.postDelayed({ shoot() }, 1400)
                    } else {
                        tap(bounds) { ok ->
                            if (ok) opened = false
                            handler.postDelayed({ shoot() }, 1400)
                        }
                    }
                } else {
                    shoot()
                }
            }, 1400)
        }
    }

    /**
     * The picture in a full-screen photo viewer: a big square across the screen, or, when the viewer
     * fills the whole screen, the square in its middle where a profile picture is shown.
     */
    private fun biggestPicture(root: AccessibilityNodeInfo): android.graphics.Rect? {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var square: android.graphics.Rect? = null
        var fullScreen = false
        var pictureLike = false
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            val ratio = if (b.height() > 0) b.width().toFloat() / b.height() else 0f
            if (b.width() >= screenW * 0.7f && ratio in 0.85f..1.18f && (square == null || b.width() > square!!.width())) square = b
            val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
            val cls = node.className?.toString().orEmpty()
            if (b.width() >= screenW * 0.95f && b.height() >= screenH * 0.6f && (cls.contains("Image") || photoLabel.containsMatchIn(label) || label.contains("photo", true))) {
                fullScreen = true
            }
            if (photoLabel.containsMatchIn(label) || label.contains("photo", true)) pictureLike = true
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        square?.let { return android.graphics.Rect(it.left + it.width() / 100, it.top + it.width() / 100, it.right - it.width() / 100, it.bottom - it.width() / 100) }
        if (fullScreen || pictureLike) {
            val top = (screenH - screenW) / 2
            return android.graphics.Rect(0, top, screenW, top + screenW)
        }
        return null
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
